package dev.devworld.server.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Закритий API для статей золотих яєць (генератор на VPS додає й редагує статті).
 * Доступ — лише з {@code Authorization: Bearer <game.admin-token>}; без налаштованого токена API вимкнений.
 * Назовні не публікується: Caddy відповідає 404 на /api/admin/*, а генератор ходить на 127.0.0.1.
 * <pre>
 * GET  /api/admin/articles          — усі статті (без тексту): id, topic, subject, title, createdAt
 * GET  /api/admin/articles/{id}     — одна стаття повністю
 * POST /api/admin/articles          — нова: {id, topic, subject, title, body, color?}; 409 — id чи subject уже є
 * PUT  /api/admin/articles/{id}     — оновити ті самі поля (крім id)
 * </pre>
 * Статті читаються з БД при кожній появі яйця — нове з'являється в грі без перезапуску.
 */
@RestController
@RequestMapping("/api/admin/articles")
public class AdminArticles {

    static final int MAX_TITLE = 200;
    static final int MAX_BODY = 20_000;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,79}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    public record ArticleSummary(String id, String topic, String subject, String title, long createdAt) {
    }

    public record ArticleData(String id, String topic, String subject, String title, String body, String color) {
    }

    private final JdbcClient jdbc;
    private final byte[] token;

    public AdminArticles(JdbcClient jdbc, @Value("${game.admin-token:}") String token) {
        this.jdbc = jdbc;
        this.token = token.strip().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return forbidden();
        }
        return ResponseEntity.ok(jdbc.sql("select id, topic, subject, title, created_at from egg_article order by created_at")
                .query((rs, n) -> new ArticleSummary(rs.getString("id"), rs.getString("topic"), rs.getString("subject"),
                        rs.getString("title"), rs.getTimestamp("created_at").getTime()))
                .list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @PathVariable String id) {
        if (!authorized(auth)) {
            return forbidden();
        }
        return jdbc.sql("select id, topic, subject, title, body, color from egg_article where id = ?")
                .param(id)
                .query((rs, n) -> new ArticleData(rs.getString("id"), rs.getString("topic"), rs.getString("subject"),
                        rs.getString("title"), rs.getString("body"), rs.getString("color")))
                .optional()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "No such article"));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody ArticleData a) {
        if (!authorized(auth)) {
            return forbidden();
        }
        var problems = validate(a, true);
        if (!problems.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, String.join("; ", problems));
        }
        try {
            jdbc.sql("insert into egg_article (id, topic, subject, title, body, color) values (?, ?, ?, ?, ?, ?)")
                    .params(a.id(), a.topic().strip(), a.subject().strip(), a.title().strip(), a.body().strip(), blankToNull(a.color()))
                    .update();
        } catch (DuplicateKeyException e) {
            return error(HttpStatus.CONFLICT, "An article with this id or subject already exists");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", a.id()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable String id, @RequestBody ArticleData a) {
        if (!authorized(auth)) {
            return forbidden();
        }
        var problems = validate(a, false);
        if (!problems.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, String.join("; ", problems));
        }
        int updated;
        try {
            updated = jdbc.sql("""
                            update egg_article set topic = ?, subject = ?, title = ?, body = ?, color = ?, updated_at = now()
                            where id = ?""")
                    .params(a.topic().strip(), a.subject().strip(), a.title().strip(), a.body().strip(), blankToNull(a.color()), id)
                    .update();
        } catch (DuplicateKeyException e) {
            return error(HttpStatus.CONFLICT, "Another article already has this subject");
        }
        return updated == 0 ? error(HttpStatus.NOT_FOUND, "No such article") : ResponseEntity.ok(Map.of("id", id));
    }

    private static List<String> validate(ArticleData a, boolean withId) {
        var problems = new ArrayList<String>();
        if (a == null) {
            return List.of("Body is required");
        }
        if (withId && (a.id() == null || !ID.matcher(a.id()).matches())) {
            problems.add("id: 2-80 chars, lowercase latin letters, digits and dashes");
        }
        if (blank(a.topic()) || a.topic().strip().length() > 40) {
            problems.add("topic: required, up to 40 chars");
        }
        if (blank(a.subject()) || a.subject().strip().length() > 120) {
            problems.add("subject: required, up to 120 chars");
        }
        if (blank(a.title()) || a.title().strip().length() > MAX_TITLE) {
            problems.add("title: required, up to " + MAX_TITLE + " chars");
        }
        if (blank(a.body()) || a.body().strip().length() > MAX_BODY) {
            problems.add("body: required, up to " + MAX_BODY + " chars");
        }
        if (!blank(a.color()) && !COLOR.matcher(a.color()).matches()) {
            problems.add("color: #rrggbb or empty");
        }
        return problems;
    }

    /** Порівняння за сталий час, щоб токен не вгадували за часом відповіді. Порожній токен — API вимкнений. */
    private boolean authorized(String header) {
        if (token.length == 0 || header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(token, header.substring(7).strip().getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<?> forbidden() {
        return error(HttpStatus.FORBIDDEN, "Forbidden");
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.strip();
    }
}
