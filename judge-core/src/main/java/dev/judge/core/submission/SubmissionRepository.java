package dev.judge.core.submission;

import dev.judge.core.verdict.Verdict;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository {

    void insert(UUID id, NewSubmission submission);

    Optional<Submission> find(UUID id);

    void updateStatus(UUID id, Status status);

    void addResult(UUID id, TestResult result);

    /** Moves to DONE. Has no effect on a submission that is already DONE. */
    void finish(UUID id, Verdict verdict, String compileOutput);

    /** Marks everything not DONE as an internal error. @return how many submissions that was */
    int failUnfinished();
}
