package dev.judge.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A real Postgres, shared by every test class, and a client that talks to the API over HTTP. */
abstract class IntegrationTest {

    static final String ADMIN_KEY = "test-admin-key";
    static final String ADMIN_KEY_PROPERTY = "judge.admin-key=" + ADMIN_KEY;

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final Duration VERDICT_TIMEOUT = Duration.ofSeconds(90);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @Value("${local.server.port}")
    int port;

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, Object body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postAsAdmin(String path, Object body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode parse(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    /** A problem whose tests are "1 2" giving "3" and, hidden, "40 2" giving "42". */
    UUID createSumProblem(int timeLimitMs, int memoryLimitMb) throws Exception {
        var body = java.util.Map.of(
                "slug", "sum-" + UUID.randomUUID(),
                "title", "Sum",
                "statement", "Add two integers.",
                "timeLimitMs", timeLimitMs,
                "memoryLimitMb", memoryLimitMb,
                "testCases", java.util.List.of(
                        java.util.Map.of("input", "1 2\n", "expected", "3\n", "hidden", false),
                        java.util.Map.of("input", "40 2\n", "expected", "42\n", "hidden", true)));
        HttpResponse<String> response = postAsAdmin("/api/problems", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(parse(response).get("id").asString());
    }

    UUID submit(UUID problemId, String language, String source) throws Exception {
        HttpResponse<String> response = post("/api/submissions", java.util.Map.of(
                "problemId", problemId, "language", language, "sourceCode", source));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return UUID.fromString(parse(response).get("id").asString());
    }

    JsonNode awaitDone(UUID id) throws Exception {
        Instant deadline = Instant.now().plus(VERDICT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            JsonNode submission = parse(get("/api/submissions/" + id));
            if ("DONE".equals(submission.get("status").asString())) {
                return submission;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Submission " + id + " did not finish within " + VERDICT_TIMEOUT);
    }

    JsonNode judge(UUID problemId, String language, String source) throws Exception {
        return awaitDone(submit(problemId, language, source));
    }
}
