package dev.judge.api.web;

import dev.judge.core.submission.Status;
import dev.judge.core.submission.Submission;
import dev.judge.core.submission.TestResult;
import dev.judge.core.verdict.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The source code is left out: it was sent by the caller, and an id should not be a way to read it. */
record SubmissionResponse(
        UUID id,
        UUID problemId,
        String language,
        Status status,
        Verdict verdict,
        String compileOutput,
        List<TestResultResponse> results,
        Instant createdAt,
        Instant finishedAt) {

    record TestResultResponse(
            int index,
            Verdict verdict,
            long runtimeMs,
            Long peakMemoryKb,
            String stdoutPreview) {

        static TestResultResponse from(TestResult result) {
            return new TestResultResponse(
                    result.index(),
                    result.verdict(),
                    result.runtime().toMillis(),
                    result.peakMemory() == null ? null : result.peakMemory().toKilobytes(),
                    result.stdoutPreview());
        }
    }

    static SubmissionResponse from(Submission submission) {
        return new SubmissionResponse(
                submission.id(),
                submission.problemId(),
                submission.language().name(),
                submission.status(),
                submission.verdict(),
                submission.compileOutput(),
                submission.results().stream().map(TestResultResponse::from).toList(),
                submission.createdAt(),
                submission.finishedAt());
    }
}
