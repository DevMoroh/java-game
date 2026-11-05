package dev.devworld.server.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Золоті яйця гравців: які статті вже знайшов (egg_seen) і які зберіг у колекцію (egg_collection). */
public interface EggStore {

    record Collected(String articleId, long at) {
    }

    /** Від найновіших. */
    List<Collected> collection(String token);

    /** false — ця стаття вже була в колекції. */
    boolean add(String token, String articleId);

    /** Статті, які гравець уже знайшов (відкрив яйце). */
    Set<String> seen(String token);

    /** Позначити статтю знайденою; false — уже знаходив раніше. */
    boolean markSeen(String token, String articleId);

    @Component
    class Jdbc implements EggStore {

        private final JdbcClient jdbc;

        public Jdbc(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public List<Collected> collection(String token) {
            return jdbc.sql("select article_id, collected_at from egg_collection where token = ? order by collected_at desc")
                    .param(token)
                    .query((rs, n) -> new Collected(rs.getString("article_id"), rs.getTimestamp("collected_at").getTime()))
                    .list();
        }

        @Override
        public boolean add(String token, String articleId) {
            return jdbc.sql("insert into egg_collection (token, article_id) values (?, ?) on conflict do nothing")
                    .params(token, articleId)
                    .update() > 0;
        }

        @Override
        public Set<String> seen(String token) {
            return new HashSet<>(jdbc.sql("select article_id from egg_seen where token = ?")
                    .param(token)
                    .query(String.class)
                    .list());
        }

        @Override
        public boolean markSeen(String token, String articleId) {
            return jdbc.sql("insert into egg_seen (token, article_id) values (?, ?) on conflict do nothing")
                    .params(token, articleId)
                    .update() > 0;
        }
    }
}
