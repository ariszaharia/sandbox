package dev.judge.api.config;

import dev.judge.core.problem.Problem;
import dev.judge.core.problem.ProblemAlreadyExistsException;
import dev.judge.core.problem.ProblemRepository;
import dev.judge.core.problem.TestCase;
import dev.judge.core.verdict.ComparatorType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/** One problem to try the service with. Off unless {@code judge.demo-data=true}. */
@Component
@ConditionalOnProperty("judge.demo-data")
class DemoData implements ApplicationRunner {

    private final ProblemRepository problems;

    DemoData(ProblemRepository problems) {
        this.problems = problems;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            problems.insert(new Problem(
                    UUID.randomUUID(),
                    "sum-two-numbers",
                    "Sum two numbers",
                    "Read two integers separated by a space and print their sum.",
                    Duration.ofSeconds(2),
                    DataSize.ofMegabytes(256),
                    ComparatorType.EXACT,
                    null,
                    List.of(
                            new TestCase(0, "1 2\n", "3\n", false),
                            new TestCase(1, "40 2\n", "42\n", false),
                            new TestCase(2, "-7 7\n", "0\n", true),
                            new TestCase(3, "1000000000 1000000000\n", "2000000000\n", true))));
        } catch (ProblemAlreadyExistsException e) {
            // Seeded by an earlier run.
        }
    }
}
