package dev.judge.core.submission;

import dev.judge.runner.language.Language;
import java.util.UUID;

public record NewSubmission(
        UUID problemId,
        Language language,
        String sourceCode) {}
