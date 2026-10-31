package dev.judge.core.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.judge.core.problem.Problem;
import dev.judge.core.problem.ProblemNotFoundException;
import dev.judge.core.verdict.ComparatorType;
import dev.judge.runner.language.Language;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class SubmissionServiceTest {

    private static final UUID PROBLEM = UUID.randomUUID();

    private final InMemoryRepositories.Submissions submissions = new InMemoryRepositories.Submissions();
    private final InMemoryRepositories.Problems problems = new InMemoryRepositories.Problems();
    private final SubmissionQueue queue = new SubmissionQueue(2);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SubmissionService service = new SubmissionService(
            submissions, problems, queue, new JudgeMetrics(registry, queue), DataSize.ofBytes(100));

    @BeforeEach
    void createProblem() {
        problems.insert(new Problem(
                PROBLEM, "p", "P", "", Duration.ofSeconds(1), DataSize.ofMegabytes(64), ComparatorType.EXACT, null, List.of()));
    }

    @Test
    void savesAndEnqueues() throws Exception {
        UUID id = service.submit(submission("print(1)"));

        assertThat(submissions.find(id)).get().extracting(Submission::status).isEqualTo(Status.QUEUED);
        assertThat(queue.take()).isEqualTo(id);
    }

    @Test
    void rejectsWhenSaturatedWithoutTouchingTheDatabase() {
        service.submit(submission("1"));
        service.submit(submission("2"));
        int existsCallsBefore = problems.existsCalls;

        assertThatThrownBy(() -> service.submit(submission("3"))).isInstanceOf(QueueSaturatedException.class);

        assertThat(submissions.rows).hasSize(2);
        assertThat(problems.existsCalls).isEqualTo(existsCallsBefore);
        assertThat(registry.get("judge.queue.rejected.total").counter().count()).isEqualTo(1);
    }

    @Test
    void takingFromTheQueueFreesASlot() throws Exception {
        service.submit(submission("1"));
        service.submit(submission("2"));
        queue.take();

        assertThat(service.submit(submission("3"))).isNotNull();
    }

    @Test
    void anUnknownProblemGivesBackItsSlot() {
        NewSubmission unknown = new NewSubmission(UUID.randomUUID(), Language.PYTHON, "print(1)");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.submit(unknown)).isInstanceOf(ProblemNotFoundException.class);
        }

        assertThat(service.submit(submission("1"))).isNotNull();
        assertThat(service.submit(submission("2"))).isNotNull();
    }

    @Test
    void aDatabaseFailureGivesBackItsSlot() {
        SubmissionQueue oneSlot = new SubmissionQueue(1);
        SubmissionService failing = new SubmissionService(
                new InMemoryRepositories.Submissions() {
                    @Override
                    public void insert(UUID id, NewSubmission submission) {
                        throw new IllegalStateException("Database is down");
                    }
                },
                problems,
                oneSlot,
                new JudgeMetrics(new SimpleMeterRegistry(), oneSlot),
                DataSize.ofBytes(100));

        assertThatThrownBy(() -> failing.submit(submission("1"))).isInstanceOf(IllegalStateException.class);

        assertThat(oneSlot.tryReserve()).isTrue();
    }

    @Test
    void rejectsSourceOverTheCapMeasuredInBytes() {
        assertThat(service.submit(submission("x".repeat(100)))).isNotNull();
        assertThatThrownBy(() -> service.submit(submission("é".repeat(51))))
                .isInstanceOf(InvalidSubmissionException.class)
                .hasMessageContaining("larger");
    }

    @Test
    void rejectsSourceThatCannotBeStored() {
        assertThatThrownBy(() -> service.submit(submission("a\0b"))).isInstanceOf(InvalidSubmissionException.class);
        assertThatThrownBy(() -> service.submit(submission("a\uD800b"))).isInstanceOf(InvalidSubmissionException.class);
        assertThatThrownBy(() -> service.submit(submission("  \n"))).isInstanceOf(InvalidSubmissionException.class);
        assertThat(submissions.rows).isEmpty();
    }

    private static NewSubmission submission(String source) {
        return new NewSubmission(PROBLEM, Language.PYTHON, source);
    }
}
