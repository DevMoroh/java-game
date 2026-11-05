package dev.devworld.server.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class JdbcBoardStore implements BoardStore {

    private static final String ENTRY_COLUMNS = "e.id, e.board, e.post_id, e.author_token, e.author_name, e.text, e.created_at";

    private final JdbcClient jdbc;

    public JdbcBoardStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PostSummary> posts(String board, int limit) {
        return jdbc.sql("""
                        select %s,
                               (select count(*) from board_entry c where c.post_id = e.id) as comments,
                               coalesce((select max(c.id) from board_entry c where c.post_id = e.id), e.id) as last_activity
                        from board_entry e
                        where e.board = ? and e.post_id is null
                        order by last_activity desc
                        limit ?""".formatted(ENTRY_COLUMNS))
                .params(board, limit)
                .query((rs, n) -> new PostSummary(entry(rs), rs.getInt("comments"), rs.getLong("last_activity")))
                .list();
    }

    @Override
    public List<Entry> comments(long postId) {
        return jdbc.sql("select %s from board_entry e where e.post_id = ? order by e.id".formatted(ENTRY_COLUMNS))
                .param(postId)
                .query((rs, n) -> entry(rs))
                .list();
    }

    @Override
    public Optional<Entry> entry(long id) {
        return jdbc.sql("select %s from board_entry e where e.id = ?".formatted(ENTRY_COLUMNS))
                .param(id)
                .query((rs, n) -> entry(rs))
                .optional();
    }

    @Override
    public Entry add(String board, Long postId, String authorToken, String authorName, String text) {
        return jdbc.sql("""
                        insert into board_entry (board, post_id, author_token, author_name, text)
                        values (?, ?, ?, ?, ?)
                        returning id, board, post_id, author_token, author_name, text, created_at""")
                .params(board, postId, authorToken, authorName, text)
                .query((rs, n) -> entry(rs))
                .single();
    }

    @Override
    public long latestId(String board) {
        return jdbc.sql("select coalesce(max(id), 0) from board_entry where board = ?")
                .param(board)
                .query(Long.class)
                .single();
    }

    @Override
    public Map<String, Long> memberships(String token) {
        var result = new HashMap<String, Long>();
        jdbc.sql("select board, last_read_id from board_member where token = ?")
                .param(token)
                .query(rs -> {
                    result.put(rs.getString("board"), rs.getLong("last_read_id"));
                });
        return result;
    }

    @Override
    public void markRead(String token, String board, long lastReadId) {
        jdbc.sql("""
                        insert into board_member (token, board, last_read_id) values (?, ?, ?)
                        on conflict (token, board) do update
                            set last_read_id = greatest(board_member.last_read_id, excluded.last_read_id)""")
                .params(token, board, lastReadId)
                .update();
    }

    @Override
    public void leave(String token, String board) {
        jdbc.sql("delete from board_member where token = ? and board = ?").params(token, board).update();
    }

    @Override
    public Set<String> muted(String token) {
        return new HashSet<>(jdbc.sql("select board from board_member where token = ? and muted")
                .param(token)
                .query(String.class)
                .list());
    }

    @Override
    public void setMuted(String token, String board, boolean muted) {
        jdbc.sql("update board_member set muted = ? where token = ? and board = ?").params(muted, token, board).update();
    }

    @Override
    public int unreadSince(String board, long afterId, String token) {
        return jdbc.sql("""
                        select count(*) from board_entry
                        where board = ? and id > ? and author_token is distinct from ?""")
                .params(board, afterId, token)
                .query(Integer.class)
                .single();
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        return new Entry(rs.getLong("id"), rs.getString("board"), rs.getObject("post_id", Long.class),
                rs.getString("author_token"), rs.getString("author_name"), rs.getString("text"),
                rs.getTimestamp("created_at").getTime());
    }
}
