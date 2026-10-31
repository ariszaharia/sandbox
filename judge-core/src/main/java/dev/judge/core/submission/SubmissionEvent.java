package dev.judge.core.submission;

import dev.judge.core.verdict.Verdict;
import java.util.UUID;

/** A status transition. */
public record SubmissionEvent(
        UUID id,
        Status status,
        Verdict verdict) {}
