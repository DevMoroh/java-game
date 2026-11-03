package dev.devworld.server.store;

import dev.devworld.server.game.Appearance;
import dev.devworld.server.game.Gender;
import dev.devworld.server.game.Messages.ChatMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PostgreSQL-сховище (схема — db/migration). Усі записи йдуть через один фоновий потік:
 * так вони виконуються в тому порядку, в якому їх зробила гра (профіль — раніше за членство в групі),
 * а помилка бази не валить ігровий цикл — лише потрапляє в лог.
 */
@Component
public class JdbcGameStore implements GameStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcGameStore.class);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("db-writer").factory());
    private final ScheduledExecutorService cleanup =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("db-cleanup").daemon().factory());
    private final Duration chatRetention;

    public JdbcGameStore(JdbcClient jdbc, ObjectMapper json, @Value("${game.chat.retention-days:30}") int chatRetentionDays) {
        this.jdbc = jdbc;
        this.json = json;
        this.chatRetention = Duration.ofDays(chatRetentionDays);
        cleanup.scheduleAtFixedRate(this::deleteOldChat, 1, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
    }

    @Override
    public Optional<StoredProfile> loadProfile(String token) {
        return jdbc.sql("""
                        select token, name, gender, appearance, score, last_x, last_z, last_rotation, status, color
                        from guest_profile where token = ?""")
                .param(token)
                .query(this::profile)
                .optional();
    }

    @Override
    public void saveProfile(StoredProfile p) {
        write("save profile", () -> jdbc.sql("""
                        insert into guest_profile
                            (token, name, gender, appearance, score, last_x, last_z, last_rotation, status, color)
                        values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                        on conflict (token) do update set
                            score = excluded.score, last_x = excluded.last_x, last_z = excluded.last_z,
                            last_rotation = excluded.last_rotation, status = excluded.status, color = excluded.color,
                            last_seen_at = now()""")
                .params(p.token(), p.name(), p.gender().name(), json.writeValueAsString(p.appearance()), p.score(),
                        p.lastX(), p.lastZ(), p.lastRotation(), p.status(), p.color())
                .update());
    }

    // Групи чату живуть у таблицях team / team_member (name — id групи, owner_token — господар).

    @Override
    public List<StoredGroup> loadGroups() {
        record Row(String id, String color, String host, List<String> members) {
        }
        var groups = new LinkedHashMap<String, Row>();
        jdbc.sql("""
                        select t.name, t.color, t.owner_token, m.token from team t
                        left join team_member m on m.team_id = t.id
                        order by t.id, m.joined_at""")
                .query(rs -> {
                    var row = groups.computeIfAbsent(rs.getString("name"), id -> {
                        try {
                            return new Row(id, rs.getString("color"), rs.getString("owner_token"), new ArrayList<>());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    String token = rs.getString("token");
                    if (token != null) {
                        row.members.add(token);
                    }
                });
        return groups.values().stream()
                .map(r -> new StoredGroup(r.id, r.color, r.host, List.copyOf(r.members)))
                .toList();
    }

    @Override
    public void createGroup(String id, String color, String hostToken) {
        write("create group", () -> {
            jdbc.sql("insert into team (name, color, owner_token) values (?, ?, ?) on conflict do nothing")
                    .params(id, color, hostToken)
                    .update();
            moveMember(hostToken, id);
        });
    }

    @Override
    public void setGroupMember(String token, String group) {
        write("set group member", () -> moveMember(token, group));
    }

    @Override
    public void setGroupHost(String group, String hostToken, String color) {
        write("set group host", () -> jdbc.sql("update team set owner_token = ?, color = ? where name = ?")
                .params(hostToken, color, group)
                .update());
    }

    @Override
    public void deleteGroup(String group) {
        // Учасники й увесь чат групи видаляються каскадом (on delete cascade).
        write("delete group", () -> jdbc.sql("delete from team where name = ?").param(group).update());
    }

    @Override
    public List<ChatMessage> loadRecentChat(String group, int limit) {
        var newestFirst = group == null
                ? jdbc.sql("""
                        select author_name, text, created_at from chat_message
                        where team_id is null order by id desc limit ?""")
                .param(limit)
                .query((rs, n) -> message(null, rs))
                .list()
                : jdbc.sql("""
                        select c.author_name, c.text, c.created_at from chat_message c
                        join team t on t.id = c.team_id
                        where t.name = ? order by c.id desc limit ?""")
                .params(group, limit)
                .query((rs, n) -> message(group, rs))
                .list();
        return newestFirst.reversed();
    }

    @Override
    public void saveChat(ChatMessage message, String authorToken) {
        var at = Timestamp.from(Instant.ofEpochMilli(message.at()));
        write("save chat", () -> {
            if (message.group() == null) {
                jdbc.sql("""
                                insert into chat_message (team_id, author_token, author_name, text, created_at)
                                values (null, ?, ?, ?, ?)""")
                        .params(authorToken, message.name(), message.text(), at)
                        .update();
            } else {
                jdbc.sql("""
                                insert into chat_message (team_id, author_token, author_name, text, created_at)
                                select id, ?, ?, ?, ? from team where name = ?""")
                        .params(authorToken, message.name(), message.text(), at, message.group())
                        .update();
            }
        });
    }

    /** from порожній: сесії авторів з минулих запусків уже не існують. */
    private static ChatMessage message(String group, ResultSet rs) throws SQLException {
        return new ChatMessage(group, "", rs.getString("author_name"), rs.getString("text"),
                rs.getTimestamp("created_at").getTime());
    }

    /** Дочекатися, поки всі поставлені в чергу записи потраплять у базу (для тестів і зупинки). */
    public void awaitWrites() {
        try {
            writer.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Database writes did not finish", e);
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        cleanup.shutdownNow();
        writer.shutdown();
        if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("Not all database writes finished before shutdown");
        }
    }

    /** Гравець — лише в одній групі: прибираємо старе членство, додаємо нове (якщо group != null). */
    private void moveMember(String token, String group) {
        jdbc.sql("delete from team_member where token = ?").param(token).update();
        if (group != null) {
            jdbc.sql("insert into team_member (team_id, token) select id, ? from team where name = ?")
                    .params(token, group)
                    .update();
        }
    }

    private void deleteOldChat() {
        try {
            int deleted = jdbc.sql("delete from chat_message where created_at < ?")
                    .param(Timestamp.from(Instant.now().minus(chatRetention)))
                    .update();
            if (deleted > 0) {
                log.info("Deleted {} old chat messages", deleted);
            }
        } catch (RuntimeException e) {
            log.error("Failed to clean up old chat messages", e);
        }
    }

    private void write(String what, Runnable action) {
        writer.execute(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("Database: failed to {}", what, e);
            }
        });
    }

    private StoredProfile profile(ResultSet rs, int row) throws SQLException {
        return new StoredProfile(
                rs.getString("token"),
                rs.getString("name"),
                Gender.valueOf(rs.getString("gender")),
                json.readValue(rs.getString("appearance"), Appearance.class),
                rs.getInt("score"),
                rs.getObject("last_x", Double.class),
                rs.getObject("last_z", Double.class),
                rs.getDouble("last_rotation"),
                rs.getString("status"),
                rs.getString("color"));
    }
}
