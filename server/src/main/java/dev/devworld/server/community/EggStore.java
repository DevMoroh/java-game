package dev.devworld.server.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** Колекції золотих яєць: які статті зберіг кожен гравець (таблиця egg_collection). */
public interface EggStore {

    record Collected(String articleId, long at) {
    }

    /** Від найновіших. */
    List<Collected> collection(String token);

    /** false — ця стаття вже була в колекції. */
    boolean add(String token, String articleId);

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
    }
}
