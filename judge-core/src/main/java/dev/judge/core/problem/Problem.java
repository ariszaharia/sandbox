package dev.judge.core.problem;

import dev.judge.core.verdict.ComparatorType;
import dev.judge.core.verdict.OutputComparator;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.util.unit.DataSize;

public record Problem(
        UUID id,
        String slug,
        String title,
        String statement,
        Duration timeLimit,          // per test case, wall clock
        DataSize memoryLimit,
        ComparatorType comparatorType,
        Double tolerance,            // only for FLOAT
        List<TestCase> testCases) {

    public OutputComparator comparator() {
        return comparatorType.create(tolerance);
    }
}
