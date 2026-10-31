package dev.judge.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.judge.api.JudgeApplication;
import dev.judge.core.submission.JudgeMetrics;
import dev.judge.core.submission.StartupReconciler;
import dev.judge.core.submission.SubmissionRepository;
import dev.judge.runner.Sandbox;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** The whole flow, with no Docker in the sandbox: HTTP, validation, persistence, queue, verdict. */
@SpringBootTest(
        classes = JudgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {IntegrationTest.ADMIN_KEY_PROPERTY, "judge.sandbox.mode=fake"})
class ApiFlowIT extends IntegrationTest {

    // FakeSandbox prints its input back, so this problem is passed by any submission.
    private UUID echoProblem() throws Exception {
        HttpResponse<String> response = postAsAdmin("/api/problems", Map.of(
                "slug", "echo-" + UUID.randomUUID(),
                "title", "Echo",
                "timeLimitMs", 1000,
                "memoryLimitMb", 64,
                "testCases", List.of(
                        Map.of("input", "visible\n", "expected", "visible\n", "hidden", false),
                        Map.of("input", "secret\n", "expected", "secret\n"))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(parse(response).get("id").asString());
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SubmissionRepository submissions;

    @Autowired
    Sandbox sandbox;

    @Autowired
    JudgeMetrics metrics;

    // --- submissions

    @Test
    void submissionGoesFromAcceptedToAVerdict() throws Exception {
        UUID problem = echoProblem();

        HttpResponse<String> accepted = post("/api/submissions", Map.of(
                "problemId", problem, "language", "PYTHON", "sourceCode", "print(input())"));

        assertThat(accepted.statusCode()).isEqualTo(202);
        String id = parse(accepted).get("id").asString();
        assertThat(accepted.headers().firstValue("Location")).contains("/api/submissions/" + id);

        JsonNode done = awaitDone(UUID.fromString(id));
        assertThat(done.get("verdict").asString()).isEqualTo("ACCEPTED");
        assertThat(done.get("results")).hasSize(2);
        assertThat(done.get("finishedAt").isNull()).isFalse();
        assertThat(done.has("sourceCode")).isFalse();
    }

    @Test
    void hiddenTestsShowIndexAndVerdictOnly() throws Exception {
        JsonNode done = judge(echoProblem(), "PYTHON", "print(input())");

        assertThat(done.get("results").get(0).get("stdoutPreview").asString()).isEqualTo("visible\n");
        assertThat(done.get("results").get(1).get("stdoutPreview").isNull()).isTrue();
        assertThat(done.get("results").get(1).get("verdict").asString()).isEqualTo("ACCEPTED");
        assertThat(done.toString()).doesNotContain("secret");
    }

    @Test
    void validationRejectsBeforeQueueing() throws Exception {
        UUID problem = echoProblem();

        assertThat(post("/api/submissions", Map.of("problemId", problem, "language", "COBOL", "sourceCode", "x")).statusCode())
                .isEqualTo(400);
        assertThat(post("/api/submissions", Map.of("problemId", problem, "language", "PYTHON", "sourceCode", "  ")).statusCode())
                .isEqualTo(400);
        assertThat(post("/api/submissions", Map.of("problemId", problem, "language", "PYTHON")).statusCode())
                .isEqualTo(400);
        assertThat(post("/api/submissions", Map.of("problemId", problem, "language", "PYTHON", "sourceCode", "x".repeat(64 * 1024 + 1))).statusCode())
                .isEqualTo(400);
        assertThat(post("/api/submissions", Map.of("problemId", problem, "language", "PYTHON", "sourceCode", "a\u0000b")).statusCode())
                .isEqualTo(400);
        assertThat(post("/api/submissions", Map.of("problemId", UUID.randomUUID(), "language", "PYTHON", "sourceCode", "x")).statusCode())
                .isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM submission WHERE problem_id = ?").param(problem).query(Long.class).single())
                .isZero();
    }

    @Test
    void anUnpairedSurrogateIsRejectedAndNotA500() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/submissions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"problemId\":\"" + echoProblem() + "\",\"language\":\"PYTHON\",\"sourceCode\":\"a\\ud800b\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void anOversizedBodyIsRejectedBeforeItIsParsed() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/submissions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1024 * 1024 + 1)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void aChunkedBodyWithNoDeclaredLengthIsRefused() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri("/api/submissions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream("{}".getBytes())))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(411);
    }

    @Test
    void unknownSubmissionIs404AndAMalformedIdIs400() throws Exception {
        assertThat(get("/api/submissions/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get("/api/submissions/not-a-uuid").statusCode()).isEqualTo(400);
    }

    // --- SSE

    @Test
    void eventsStreamEndsWithTheVerdict() throws Exception {
        UUID id = submit(echoProblem(), "PYTHON", "print(input())");

        List<String> lines = eventLines(id);

        assertThat(lines).anyMatch(line -> line.startsWith("event:") && line.contains("status"));
        assertThat(lines.stream().filter(line -> line.startsWith("data:")).toList().getLast())
                .contains("\"status\":\"DONE\"")
                .contains("\"verdict\":\"ACCEPTED\"");
    }

    @Test
    void subscribingAfterTheEndStillDeliversTheVerdict() throws Exception {
        UUID id = submit(echoProblem(), "PYTHON", "print(input())");
        awaitDone(id);

        assertThat(eventLines(id).stream().filter(line -> line.startsWith("data:")).toList())
                .singleElement()
                .asString()
                .contains("\"verdict\":\"ACCEPTED\"");
    }

    @Test
    void eventsForAnUnknownSubmissionAre404() throws Exception {
        assertThat(http.send(
                        HttpRequest.newBuilder(uri("/api/submissions/" + UUID.randomUUID() + "/events")).GET().build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode())
                .isEqualTo(404);
    }

    private List<String> eventLines(UUID id) throws Exception {
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/api/submissions/" + id + "/events")).GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).get().asString().startsWith("text/event-stream");
        // The server completes the stream at DONE, so this ends.
        return new ArrayList<>(response.body().toList());
    }

    // --- problems

    @Test
    void creatingAProblemNeedsTheAdminKey() throws Exception {
        Map<String, Object> body = Map.of(
                "slug", "guarded-" + UUID.randomUUID(),
                "title", "Guarded",
                "timeLimitMs", 1000,
                "memoryLimitMb", 64,
                "testCases", List.of(Map.of("input", "", "expected", "")));

        assertThat(post("/api/problems", body).statusCode()).isEqualTo(401);
        assertThat(http.send(
                        HttpRequest.newBuilder(uri("/api/problems"))
                                .header("Content-Type", "application/json")
                                .header("X-Admin-Key", "wrong")
                                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode())
                .isEqualTo(401);
        assertThat(postAsAdmin("/api/problems", body).statusCode()).isEqualTo(201);
    }

    @Test
    void aTakenSlugIsAConflict() throws Exception {
        Map<String, Object> body = Map.of(
                "slug", "taken-" + UUID.randomUUID(),
                "title", "Taken",
                "timeLimitMs", 1000,
                "memoryLimitMb", 64,
                "testCases", List.of(Map.of("input", "", "expected", "")));

        assertThat(postAsAdmin("/api/problems", body).statusCode()).isEqualTo(201);
        assertThat(postAsAdmin("/api/problems", body).statusCode()).isEqualTo(409);
    }

    @Test
    void problemValidationRejectsBadInput() throws Exception {
        Map<String, Object> noTests = Map.of(
                "slug", "bad-" + UUID.randomUUID(), "title", "Bad", "timeLimitMs", 1000, "memoryLimitMb", 64, "testCases", List.of());
        Map<String, Object> badLimit = Map.of(
                "slug", "bad-" + UUID.randomUUID(), "title", "Bad", "timeLimitMs", 1, "memoryLimitMb", 64,
                "testCases", List.of(Map.of("input", "", "expected", "")));
        Map<String, Object> badSlug = Map.of(
                "slug", "Not A Slug", "title", "Bad", "timeLimitMs", 1000, "memoryLimitMb", 64,
                "testCases", List.of(Map.of("input", "", "expected", "")));
        Map<String, Object> floatWithoutTolerance = Map.of(
                "slug", "bad-" + UUID.randomUUID(), "title", "Bad", "timeLimitMs", 1000, "memoryLimitMb", 64,
                "comparator", "FLOAT", "testCases", List.of(Map.of("input", "", "expected", "")));

        assertThat(postAsAdmin("/api/problems", noTests).statusCode()).isEqualTo(400);
        assertThat(postAsAdmin("/api/problems", badLimit).statusCode()).isEqualTo(400);
        assertThat(postAsAdmin("/api/problems", badSlug).statusCode()).isEqualTo(400);
        assertThat(postAsAdmin("/api/problems", floatWithoutTolerance).statusCode()).isEqualTo(400);
    }

    @Test
    void problemsAreListedAndHiddenTestsNeverAppear() throws Exception {
        UUID problem = echoProblem();

        assertThat(parse(get("/api/problems")).toString()).contains(problem.toString()).doesNotContain("secret");

        JsonNode detail = parse(get("/api/problems/" + problem));
        assertThat(detail.get("samples")).hasSize(1);
        assertThat(detail.get("samples").get(0).get("input").asString()).isEqualTo("visible\n");
        assertThat(detail.toString()).doesNotContain("secret");
    }

    @Test
    void floatComparatorIsHonoured() throws Exception {
        HttpResponse<String> created = postAsAdmin("/api/problems", Map.of(
                "slug", "float-" + UUID.randomUUID(),
                "title", "Float",
                "timeLimitMs", 1000,
                "memoryLimitMb", 64,
                "comparator", "FLOAT",
                "tolerance", 0.5,
                // The fake sandbox prints the input, which is within tolerance of the expected output.
                "testCases", List.of(Map.of("input", "1.2\n", "expected", "1.0\n", "hidden", false))));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

        JsonNode done = judge(UUID.fromString(parse(created).get("id").asString()), "PYTHON", "print(1)");

        assertThat(done.get("verdict").asString()).isEqualTo("ACCEPTED");
    }

    // --- startup sweep

    @Test
    void unfinishedSubmissionsAreSweptToInternalErrorOnStartup() throws Exception {
        UUID problem = echoProblem();
        UUID running = insertWithStatus(problem, "RUNNING");
        UUID compiling = insertWithStatus(problem, "COMPILING");
        UUID queued = insertWithStatus(problem, "QUEUED");
        UUID alreadyDone = judge(problem, "PYTHON", "print(1)").get("id").asString().transform(UUID::fromString);

        new StartupReconciler(submissions, sandbox, metrics, Path.of(System.getProperty("java.io.tmpdir"), "judge-sweep-test"))
                .reconcile();

        for (UUID id : List.of(running, compiling, queued)) {
            JsonNode swept = parse(get("/api/submissions/" + id));
            assertThat(swept.get("status").asString()).isEqualTo("DONE");
            assertThat(swept.get("verdict").asString()).isEqualTo("INTERNAL_ERROR");
            assertThat(swept.get("finishedAt").isNull()).isFalse();
        }
        assertThat(parse(get("/api/submissions/" + alreadyDone)).get("verdict").asString()).isEqualTo("ACCEPTED");
    }

    private UUID insertWithStatus(UUID problem, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO submission (id, problem_id, language, source_code, status) VALUES (?, ?, 'PYTHON', 'print(1)', ?)")
                .params(id, problem, status)
                .update();
        return id;
    }

    // --- ops

    @Test
    void healthAndMetricsAreExposed() throws Exception {
        judge(echoProblem(), "PYTHON", "print(input())");

        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/prometheus").body())
                .contains("judge_submissions_total{verdict=\"ACCEPTED\"")
                .contains("judge_queue_depth")
                .contains("judge_queue_rejected_total")
                .contains("judge_execution_duration_seconds_bucket{language=\"PYTHON\"")
                .contains("judge_container_startup_duration_seconds_bucket");
    }

    @Test
    void theFrontendIsServed() throws Exception {
        HttpResponse<String> page = get("/");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Judge Sandbox");
    }
}
