package dev.judge.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.judge.api.JudgeApplication;
import dev.judge.runner.CompileRequest;
import dev.judge.runner.CompileResult;
import dev.judge.runner.FakeSandbox;
import dev.judge.runner.RunRequest;
import dev.judge.runner.RunResult;
import dev.judge.runner.Sandbox;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One worker held on a latch and room for two more in the queue, so saturation is deterministic. */
@SpringBootTest(
        classes = JudgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            IntegrationTest.ADMIN_KEY_PROPERTY,
            "judge.sandbox.mode=fake",
            "judge.workers=1",
            "judge.queue-capacity=2"
        })
@Import(BackpressureIT.HeldSandboxConfiguration.class)
class BackpressureIT extends IntegrationTest {

    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE = new CountDownLatch(1);

    @TestConfiguration
    static class HeldSandboxConfiguration {

        @Bean
        @Primary
        Sandbox heldSandbox() {
            return new Sandbox() {
                @Override
                public CompileResult compile(CompileRequest request) {
                    return CompileResult.notRequired();
                }

                @Override
                public RunResult run(RunRequest request) {
                    STARTED.countDown();
                    try {
                        RELEASE.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return FakeSandbox.printed(request.stdin());
                }
            };
        }
    }

    @Autowired
    JdbcClient jdbc;

    @Test
    void saturationRejectsWith429AndLeavesNoRowsBehindAndRecovers() throws Exception {
        UUID problem = createSumProblem(1000, 64);
        Map<String, Object> request = Map.of("problemId", problem, "language", "PYTHON", "sourceCode", "print(1)");

        // The first one occupies the worker, which frees its queue slot, and then two fill the queue.
        List<UUID> accepted = new ArrayList<>();
        accepted.add(submit(problem, "PYTHON", "print(1)"));
        assertThat(STARTED.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        accepted.add(submit(problem, "PYTHON", "print(2)"));
        accepted.add(submit(problem, "PYTHON", "print(3)"));

        HttpResponse<String> rejected = post("/api/submissions", request);

        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.headers().firstValue("Retry-After")).contains("1");
        assertThat(parse(rejected).get("detail").asString()).contains("queue is full");
        assertThat(jdbc.sql("SELECT count(*) FROM submission WHERE problem_id = ?").param(problem).query(Long.class).single())
                .as("a rejected request leaves nothing in the database")
                .isEqualTo(3);
        assertThat(parse(get("/api/submissions/" + accepted.get(1))).get("status").asString()).isEqualTo("QUEUED");
        assertThat(get("/actuator/prometheus").body())
                .contains("judge_queue_depth")
                .contains("judge_queue_rejected_total");

        RELEASE.countDown();

        for (UUID id : accepted) {
            assertThat(awaitDone(id).get("verdict").asString()).isEqualTo("WRONG_ANSWER");
        }
        // Room again, so the rejection was about load and not a stuck state.
        assertThat(post("/api/submissions", request).statusCode()).isEqualTo(202);
    }
}
