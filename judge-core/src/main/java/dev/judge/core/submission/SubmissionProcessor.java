package dev.judge.core.submission;

import dev.judge.core.problem.Problem;
import dev.judge.core.problem.ProblemRepository;
import dev.judge.core.problem.TestCase;
import dev.judge.core.verdict.OutputComparator;
import dev.judge.core.verdict.Verdict;
import dev.judge.core.verdict.VerdictResolver;
import dev.judge.runner.CompileRequest;
import dev.judge.runner.CompileResult;
import dev.judge.runner.RunRequest;
import dev.judge.runner.RunResult;
import dev.judge.runner.Sandbox;
import dev.judge.runner.SubmissionWorkspace;
import dev.judge.runner.TerminationReason;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

/**
 * Compiles once and runs once per test case, stopping at the first failure. Stopping keeps a
 * program that always times out from holding a worker for the time limit times every test.
 *
 * <p>Any failure that is not the program's own becomes {@link Verdict#INTERNAL_ERROR}.
 */
public final class SubmissionProcessor {

    private static final Logger log = LoggerFactory.getLogger(SubmissionProcessor.class);
    private static final int MAX_COMPILE_OUTPUT_CHARS = 64 * 1024;

    /** @param processLimit per container, so it also has to cover the runtime's own threads */
    public record Settings(Path workspaceRoot, DataSize outputLimit, int processLimit, int previewChars) {}

    private final SubmissionRepository submissions;
    private final ProblemRepository problems;
    private final Sandbox sandbox;
    private final SubmissionEventListener events;
    private final JudgeMetrics metrics;
    private final Settings settings;

    public SubmissionProcessor(
            SubmissionRepository submissions,
            ProblemRepository problems,
            Sandbox sandbox,
            SubmissionEventListener events,
            JudgeMetrics metrics,
            Settings settings) {
        this.submissions = submissions;
        this.problems = problems;
        this.sandbox = sandbox;
        this.events = events;
        this.metrics = metrics;
        this.settings = settings;
    }

    public void process(UUID id) {
        try {
            judge(id);
        } catch (Exception e) {
            // Saving the verdict needs a database connection, which an interrupted thread cannot get.
            boolean interrupted = Thread.interrupted();
            log.error("Judging {} failed; reporting an internal error", id, e);
            finishQuietly(id);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void judge(UUID id) {
        Submission submission = submissions.find(id)
                .orElseThrow(() -> new IllegalStateException("Queued submission " + id + " does not exist"));
        Problem problem = problems.find(submission.problemId())
                .orElseThrow(() -> new IllegalStateException("Problem of submission " + id + " does not exist"));

        try (SubmissionWorkspace workspace = SubmissionWorkspace.create(
                settings.workspaceRoot(), id, submission.language(), submission.sourceCode())) {
            transition(id, Status.COMPILING);
            CompileResult compiled = sandbox.compile(new CompileRequest(workspace.directory(), submission.language()));
            String compileOutput = StoredText.of(compileMessage(compiled), MAX_COMPILE_OUTPUT_CHARS);
            if (!compiled.success()) {
                finish(id, Verdict.COMPILE_ERROR, compileOutput);
                return;
            }

            transition(id, Status.RUNNING);
            finish(id, runTests(id, submission, problem, workspace.directory()), compileOutput);
        }
    }

    private Verdict runTests(UUID id, Submission submission, Problem problem, Path workdir) {
        OutputComparator comparator = problem.comparator();
        for (TestCase test : problem.testCases()) {
            RunResult result = sandbox.run(new RunRequest(
                    workdir,
                    submission.language(),
                    test.input(),
                    problem.timeLimit(),
                    problem.timeLimit(),
                    problem.memoryLimit(),
                    settings.outputLimit(),
                    settings.processLimit()));
            metrics.testExecuted(submission.language(), result.wallTime(), result.startupTime());

            Verdict verdict = VerdictResolver.resolve(result, test.expectedOutput(), comparator);
            submissions.addResult(id, new TestResult(
                    test.index(),
                    verdict,
                    result.wallTime(),
                    result.peakMemory(),
                    test.hidden() ? null : StoredText.of(result.stdout(), settings.previewChars())));
            if (verdict != Verdict.ACCEPTED) {
                return verdict;
            }
        }
        return Verdict.ACCEPTED;
    }

    /** A compiler killed for exceeding a limit may have printed nothing that explains why. */
    private static String compileMessage(CompileResult compiled) {
        TerminationReason reason = compiled.reason();
        return switch (reason) {
            case NORMAL, SIGNAL -> compiled.output();
            case TIMEOUT -> "Compilation exceeded its time limit\n" + compiled.output();
            case OOM -> "Compilation exceeded its memory limit\n" + compiled.output();
            case OUTPUT_CAP -> "Compilation exceeded its output limit\n" + compiled.output();
        };
    }

    private void transition(UUID id, Status status) {
        submissions.updateStatus(id, status);
        publish(new SubmissionEvent(id, status, null));
    }

    private void finish(UUID id, Verdict verdict, String compileOutput) {
        submissions.finish(id, verdict, compileOutput);
        metrics.submissionsFinished(verdict, 1);
        publish(new SubmissionEvent(id, Status.DONE, verdict));
    }

    private void finishQuietly(UUID id) {
        try {
            finish(id, Verdict.INTERNAL_ERROR, null);
        } catch (RuntimeException e) {
            // The database is unreachable too. The startup sweep settles this submission.
            log.error("Could not save the internal error of {}", id, e);
        }
    }

    private void publish(SubmissionEvent event) {
        try {
            events.onEvent(event);
        } catch (RuntimeException e) {
            log.warn("Listener failed on {}", event, e);
        }
    }
}
