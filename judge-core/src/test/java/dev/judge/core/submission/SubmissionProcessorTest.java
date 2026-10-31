package dev.judge.core.submission;

import static org.assertj.core.api.Assertions.assertThat;

import dev.judge.core.problem.Problem;
import dev.judge.core.problem.TestCase;
import dev.judge.core.verdict.ComparatorType;
import dev.judge.core.verdict.Verdict;
import dev.judge.runner.CompileRequest;
import dev.judge.runner.CompileResult;
import dev.judge.runner.FakeSandbox;
import dev.judge.runner.RunRequest;
import dev.judge.runner.RunResult;
import dev.judge.runner.Sandbox;
import dev.judge.runner.SandboxException;
import dev.judge.runner.TerminationReason;
import dev.judge.runner.language.Language;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class SubmissionProcessorTest {

    private static final UUID PROBLEM = UUID.randomUUID();
    private static final UUID SUBMISSION = UUID.randomUUID();

    @TempDir
    Path workspaces;

    private final InMemoryRepositories.Submissions submissions = new InMemoryRepositories.Submissions();
    private final InMemoryRepositories.Problems problems = new InMemoryRepositories.Problems();
    private final List<SubmissionEvent> events = new ArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void acceptsWhenEveryTestPasses() {
        problem(new TestCase(0, "a\n", "a\n", false), new TestCase(1, "b\n", "b\n", true));

        process(FakeSandbox.echoing());

        Submission done = submission();
        assertThat(done.status()).isEqualTo(Status.DONE);
        assertThat(done.verdict()).isEqualTo(Verdict.ACCEPTED);
        assertThat(done.results()).extracting(TestResult::verdict).containsExactly(Verdict.ACCEPTED, Verdict.ACCEPTED);
        assertThat(registry.get("judge.submissions.total").tag("verdict", "ACCEPTED").counter().count()).isEqualTo(1);
        assertThat(registry.get("judge.execution.duration").tag("language", "PYTHON").timer().count()).isEqualTo(2);
    }

    @Test
    void walksThroughTheStatusesInOrder() {
        problem(new TestCase(0, "a\n", "a\n", false));

        process(FakeSandbox.echoing());

        assertThat(events).extracting(SubmissionEvent::status)
                .containsExactly(Status.COMPILING, Status.RUNNING, Status.DONE);
        assertThat(events.getLast().verdict()).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    void stopsAtTheFirstFailure() {
        problem(new TestCase(0, "a\n", "a\n", false), new TestCase(1, "b\n", "nope\n", false), new TestCase(2, "c\n", "c\n", false));
        AtomicInteger runs = new AtomicInteger();

        process(new FakeSandbox(request -> {
            runs.incrementAndGet();
            return FakeSandbox.printed(request.stdin());
        }));

        assertThat(runs).hasValue(2);
        assertThat(submission().verdict()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(submission().results()).extracting(TestResult::verdict)
                .containsExactly(Verdict.ACCEPTED, Verdict.WRONG_ANSWER);
    }

    @Test
    void neverStoresTheOutputOfAHiddenTest() {
        problem(new TestCase(0, "visible\n", "visible\n", false), new TestCase(1, "secret\n", "secret\n", true));

        process(FakeSandbox.echoing());

        assertThat(submission().results().get(0).stdoutPreview()).isEqualTo("visible\n");
        assertThat(submission().results().get(1).stdoutPreview()).isNull();
    }

    @Test
    void truncatesThePreview() {
        problem(new TestCase(0, "x".repeat(50), "x".repeat(50), false));

        process(FakeSandbox.echoing());

        assertThat(submission().results().getFirst().stdoutPreview()).hasSize(10);
    }

    @Test
    void reportsACompileFailureWithItsMessage() {
        problem(new TestCase(0, "a\n", "a\n", false));
        AtomicInteger runs = new AtomicInteger();

        process(new StubSandbox(
                new CompileResult(false, "Main.java:1: error: ';' expected", TerminationReason.NORMAL),
                request -> {
                    runs.incrementAndGet();
                    return FakeSandbox.printed("");
                }));

        assertThat(submission().verdict()).isEqualTo(Verdict.COMPILE_ERROR);
        assertThat(submission().compileOutput()).contains("';' expected");
        assertThat(runs).hasValue(0);
    }

    @Test
    void explainsACompilerKilledForALimit() {
        problem(new TestCase(0, "a\n", "a\n", false));

        process(new StubSandbox(new CompileResult(false, "", TerminationReason.TIMEOUT), request -> FakeSandbox.printed("")));

        assertThat(submission().verdict()).isEqualTo(Verdict.COMPILE_ERROR);
        assertThat(submission().compileOutput()).contains("time limit");
    }

    @Test
    void aSandboxFailureDuringCompileIsAnInternalError() {
        problem(new TestCase(0, "a\n", "a\n", false));

        process(new Sandbox() {
            @Override
            public CompileResult compile(CompileRequest request) {
                throw new SandboxException("daemon gone");
            }

            @Override
            public RunResult run(RunRequest request) {
                throw new AssertionError("Must not run after a failed compile");
            }
        });

        assertThat(submission().verdict()).isEqualTo(Verdict.INTERNAL_ERROR);
        assertThat(submission().status()).isEqualTo(Status.DONE);
    }

    @Test
    void aSandboxFailureDuringARunIsAnInternalErrorAndNotARuntimeError() {
        problem(new TestCase(0, "a\n", "a\n", false), new TestCase(1, "b\n", "b\n", false));
        AtomicInteger runs = new AtomicInteger();

        process(new FakeSandbox(request -> {
            if (runs.incrementAndGet() == 2) {
                throw new SandboxException("daemon gone");
            }
            return FakeSandbox.printed(request.stdin());
        }));

        assertThat(submission().verdict()).isEqualTo(Verdict.INTERNAL_ERROR);
        assertThat(submission().results()).hasSize(1);
    }

    @Test
    void nulBytesInTheOutputDoNotBreakStoring() {
        problem(new TestCase(0, "a\n", "a\n", false));

        process(new FakeSandbox(request -> FakeSandbox.printed("a\0b\n")));

        assertThat(submission().verdict()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(submission().results().getFirst().stdoutPreview()).isEqualTo("ab\n");
    }

    @Test
    void interruptionIsReportedAsAnInternalErrorAndPreserved() {
        problem(new TestCase(0, "a\n", "a\n", false));

        process(new FakeSandbox(request -> {
            Thread.currentThread().interrupt();
            throw new SandboxException("Interrupted");
        }));

        assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
        assertThat(submission().verdict()).isEqualTo(Verdict.INTERNAL_ERROR);
    }

    @Test
    void survivesADatabaseFailureWhileReportingAnInternalError() {
        problem(new TestCase(0, "a\n", "a\n", false));
        submissions.failOnFinish = true;

        process(new FakeSandbox(request -> {
            throw new SandboxException("daemon gone");
        }));

        assertThat(submission().status()).isEqualTo(Status.RUNNING);
    }

    @Test
    void aFailingListenerDoesNotAffectTheVerdict() {
        problem(new TestCase(0, "a\n", "a\n", false));
        submissions.insert(SUBMISSION, new NewSubmission(PROBLEM, Language.PYTHON, "print()"));

        processor(FakeSandbox.echoing(), event -> {
            throw new IllegalStateException("listener broke");
        }).process(SUBMISSION);

        assertThat(submission().verdict()).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    void cleansUpTheWorkspace() throws Exception {
        problem(new TestCase(0, "a\n", "a\n", false));

        process(FakeSandbox.echoing());

        try (var entries = Files.list(workspaces)) {
            assertThat(entries).isEmpty();
        }
    }

    private void problem(TestCase... tests) {
        problems.insert(new Problem(
                PROBLEM, "p", "P", "", Duration.ofSeconds(1), DataSize.ofMegabytes(64), ComparatorType.EXACT, null, List.of(tests)));
    }

    private void process(Sandbox sandbox) {
        submissions.insert(SUBMISSION, new NewSubmission(PROBLEM, Language.PYTHON, "print()"));
        processor(sandbox, events::add).process(SUBMISSION);
    }

    private SubmissionProcessor processor(Sandbox sandbox, SubmissionEventListener listener) {
        return new SubmissionProcessor(
                submissions,
                problems,
                sandbox,
                listener,
                new JudgeMetrics(registry, new SubmissionQueue(1)),
                new SubmissionProcessor.Settings(workspaces, DataSize.ofMegabytes(1), 16, 10));
    }

    private Submission submission() {
        return submissions.find(SUBMISSION).orElseThrow();
    }

    private record StubSandbox(CompileResult compiled, Function<RunRequest, RunResult> runs) implements Sandbox {

        @Override
        public CompileResult compile(CompileRequest request) {
            return compiled;
        }

        @Override
        public RunResult run(RunRequest request) {
            return runs.apply(request);
        }
    }
}
