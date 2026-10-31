package dev.judge.core.problem;

import java.time.Duration;
import java.util.UUID;
import org.springframework.util.unit.DataSize;

/** A problem without its test cases, for listing. */
public record ProblemSummary(
        UUID id,
        String slug,
        String title,
        Duration timeLimit,
        DataSize memoryLimit) {}
