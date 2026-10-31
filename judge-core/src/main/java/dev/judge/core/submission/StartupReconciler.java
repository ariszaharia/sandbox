package dev.judge.core.submission;

import dev.judge.core.verdict.Verdict;
import dev.judge.runner.Sandbox;
import dev.judge.runner.SandboxException;
import dev.judge.runner.SubmissionWorkspace;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clears what a previous process left behind. The queue lives in memory, so anything not DONE at
 * startup can never finish, whatever state it was in: without this it would poll forever.
 *
 * <p>Assumes one instance per database, which an in-process queue requires anyway.
 */
public final class StartupReconciler {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciler.class);

    private final SubmissionRepository submissions;
    private final Sandbox sandbox;
    private final JudgeMetrics metrics;
    private final Path workspaceRoot;

    public StartupReconciler(SubmissionRepository submissions, Sandbox sandbox, JudgeMetrics metrics, Path workspaceRoot) {
        this.submissions = submissions;
        this.sandbox = sandbox;
        this.metrics = metrics;
        this.workspaceRoot = workspaceRoot;
    }

    public void reconcile() {
        int failed = submissions.failUnfinished();
        if (failed > 0) {
            log.warn("Marked {} submissions left unfinished by a previous process as internal errors", failed);
            metrics.submissionsFinished(Verdict.INTERNAL_ERROR, failed);
        }
        SubmissionWorkspace.purgeStale(workspaceRoot);
        try {
            sandbox.reclaimOrphans();
        } catch (SandboxException e) {
            // The daemon being down must not stop the API; submissions report it as they run.
            log.warn("Could not reclaim orphaned containers: {}", e.getMessage());
        }
    }
}
