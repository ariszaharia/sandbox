package dev.judge.core.submission;

import dev.judge.core.verdict.Verdict;
import dev.judge.runner.language.Language;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Submission(
        UUID id,
        UUID problemId,
        Language language,
        String sourceCode,
        Status status,
        Verdict verdict,            // null until DONE
        String compileOutput,       // null until compiled
        List<TestResult> results,
        Instant createdAt,
        Instant finishedAt) {}
