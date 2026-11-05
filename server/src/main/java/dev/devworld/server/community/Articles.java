package dev.devworld.server.community;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Статті «золотих яєць» (таблиця egg_article). Читаються з БД щоразу — таблицю може поповнювати генератор,
 * поки сервер працює. Порожня таблиця — яйця не з'являються.
 */
public interface Articles {

    /** {@code body} — абзаци, списки «- », блоки коду ```, `код`, **жирний**. */
    record Article(String id, String topic, String color, String title, String body) {
    }

    List<Article> all();

    Optional<Article> find(String id);

    /** Тема → колір яйця, якщо в статті колір не заданий. Без зеленого — яйце не губиться в траві. */
    Map<String, String> TOPIC_COLORS = Map.of(
            "Java", "#f97316",
            "Frontend", "#3b82f6",
            "Databases", "#06b6d4",
            "DevOps", "#a855f7",
            "Architecture", "#eab308",
            "Security", "#ef4444",
            "Testing", "#ec4899",
            "Git", "#94a3b8");
    /** Для тем поза списком — стабільний колір з цієї палітри за хешем теми. */
    List<String> FALLBACK_COLORS = List.of("#f59e0b", "#0ea5e9", "#d946ef", "#f43f5e", "#8b5cf6", "#14b8a6");

    static String colorFor(String topic, String color) {
        if (color != null && !color.isBlank()) {
            return color;
        }
        var known = TOPIC_COLORS.get(topic);
        return known != null ? known : FALLBACK_COLORS.get(Math.floorMod(topic.hashCode(), FALLBACK_COLORS.size()));
    }

    @Component
    class Jdbc implements Articles {

        private final JdbcClient jdbc;

        public Jdbc(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public List<Article> all() {
            return jdbc.sql("select id, topic, color, title, body from egg_article order by id")
                    .query((rs, n) -> article(rs.getString("id"), rs.getString("topic"), rs.getString("color"),
                            rs.getString("title"), rs.getString("body")))
                    .list();
        }

        @Override
        public Optional<Article> find(String id) {
            return jdbc.sql("select id, topic, color, title, body from egg_article where id = ?")
                    .param(id)
                    .query((rs, n) -> article(rs.getString("id"), rs.getString("topic"), rs.getString("color"),
                            rs.getString("title"), rs.getString("body")))
                    .optional();
        }

        private static Article article(String id, String topic, String color, String title, String body) {
            return new Article(id, topic, colorFor(topic, color), title, body);
        }
    }
}
