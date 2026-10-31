package dev.judge.core.submission;

import dev.judge.core.problem.ProblemNotFoundException;
import dev.judge.core.problem.ProblemRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.util.unit.DataSize;

/** The edge of the expensive path: anything that will not be judged is rejected here, cheaply. */
public final class SubmissionService {

    private final SubmissionRepository submissions;
    private final ProblemRepository problems;
    private final SubmissionQueue queue;
    private final JudgeMetrics metrics;
    private final DataSize maxSourceSize;

    public SubmissionService(
            SubmissionRepository submissions,
            ProblemRepository problems,
            SubmissionQueue queue,
            JudgeMetrics metrics,
            DataSize maxSourceSize) {
        this.submissions = submissions;
        this.problems = problems;
        this.queue = queue;
        this.metrics = metrics;
        this.maxSourceSize = maxSourceSize;
    }

    /**
     * @throws InvalidSubmissionException if the source cannot be judged
     * @throws QueueSaturatedException    if there is no room for more work
     * @throws ProblemNotFoundException   if the problem does not exist
     */
    public UUID submit(NewSubmission submission) {
        validate(submission.sourceCode());

        if (!queue.tryReserve()) {
            metrics.queueRejected();
            throw new QueueSaturatedException();
        }
        boolean enqueued = false;
        try {
            if (!problems.exists(submission.problemId())) {
                throw new ProblemNotFoundException(submission.problemId());
            }
            UUID id = UUID.randomUUID();
            submissions.insert(id, submission);
            queue.enqueue(id);
            enqueued = true;
            return id;
        } finally {
            if (!enqueued) {
                queue.release();
            }
        }
    }

    private void validate(String source) {
        if (source.isBlank()) {
            throw new InvalidSubmissionException("Source code is empty");
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > maxSourceSize.toBytes()) {
            throw new InvalidSubmissionException("Source code is larger than " + maxSourceSize.toKilobytes() + " KB");
        }
        // Postgres cannot store a NUL or an unpaired surrogate, and neither belongs in source code.
        if (source.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(source)) {
            throw new InvalidSubmissionException("Source code is not valid text");
        }
    }
}
