package dev.devworld.server.admin;

import dev.devworld.server.TestcontainersConfiguration;
import dev.devworld.server.community.Articles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "game.admin-token=secret-token")
class AdminArticlesTest {

    private static final String ARTICLE = """
            {"id":"cache-stampede","topic":"Architecture","subject":"Cache stampede","title":"Кеш протух — база лягла",
             "body":"## Питання\\nТекст","color":""}""";

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Articles articles;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.sql("truncate egg_article cascade").update();
    }

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        assertThat(send("GET", "/api/admin/articles", null, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/admin/articles", null, "wrong").statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/admin/articles", ARTICLE, "wrong").statusCode()).isEqualTo(403);
        assertThat(jdbc.sql("select count(*) from egg_article").query(Integer.class).single()).isZero();
    }

    @Test
    void createListReadAndUpdate() throws Exception {
        assertThat(send("POST", "/api/admin/articles", ARTICLE, "secret-token").statusCode()).isEqualTo(201);

        var list = send("GET", "/api/admin/articles", null, "secret-token");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("\"subject\":\"Cache stampede\"").doesNotContain("Текст");
        // Гра бачить нову статтю одразу; колір — за темою.
        assertThat(articles.find("cache-stampede")).hasValueSatisfying(a -> assertThat(a.color()).isEqualTo("#eab308"));

        var updated = send("PUT", "/api/admin/articles/cache-stampede", ARTICLE.replace("Текст", "Новий текст"), "secret-token");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/admin/articles/cache-stampede", null, "secret-token").body()).contains("Новий текст");
        assertThat(send("PUT", "/api/admin/articles/missing", ARTICLE, "secret-token").statusCode()).isEqualTo(404);
    }

    @Test
    void duplicateIdOrSubjectIsAConflict() throws Exception {
        send("POST", "/api/admin/articles", ARTICLE, "secret-token");

        var sameId = send("POST", "/api/admin/articles", ARTICLE.replace("Cache stampede", "Other"), "secret-token");
        var sameSubject = send("POST", "/api/admin/articles",
                ARTICLE.replace("cache-stampede", "another-id").replace("Cache stampede", "CACHE STAMPEDE"), "secret-token");

        assertThat(sameId.statusCode()).isEqualTo(409);
        assertThat(sameSubject.statusCode()).isEqualTo(409);
    }

    @Test
    void validatesFields() throws Exception {
        var bad = send("POST", "/api/admin/articles",
                ARTICLE.replace("cache-stampede", "Bad Id!").replace("\"color\":\"\"", "\"color\":\"red\""), "secret-token");

        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("id:").contains("color:");
    }

    private HttpResponse<String> send(String method, String path, String json, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
