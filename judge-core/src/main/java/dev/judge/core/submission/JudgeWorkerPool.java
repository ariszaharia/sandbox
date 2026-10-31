package dev.judge.core.submission;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** N threads taking from the queue. Each one holds a submission for as long as judging it takes. */
public final class JudgeWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(JudgeWorkerPool.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

    private final SubmissionQueue queue;
    private final SubmissionProcessor processor;
    private final int size;
    private final List<Thread> workers = new ArrayList<>();

    public JudgeWorkerPool(SubmissionQueue queue, SubmissionProcessor processor, int size) {
        this.queue = queue;
        this.processor = processor;
        this.size = size;
    }

    public void start() {
        for (int i = 0; i < size; i++) {
            workers.add(Thread.ofPlatform().name("judge-worker-" + i).start(this::work));
        }
    }

    /** Interrupting a worker kills its container, and the submission ends as an internal error. */
    public void stop() {
        workers.forEach(Thread::interrupt);
        try {
            for (Thread worker : workers) {
                if (!worker.join(SHUTDOWN_WAIT)) {
                    log.warn("{} did not stop in time", worker.getName());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                UUID id = queue.take();
                processor.process(id);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                // A dead worker would silently shrink the pool until the queue stopped moving.
                log.error("Worker survived an unexpected failure", t);
            }
        }
    }
}
