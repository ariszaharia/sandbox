package dev.judge.core.submission;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Bounded by reservation rather than by {@code offer}. A slot is taken before anything else
 * happens, so under saturation a request is turned away without touching the database, and a
 * submission that was saved is guaranteed a place in the queue.
 */
public final class SubmissionQueue {

    private final Semaphore slots;
    private final LinkedBlockingQueue<UUID> queue = new LinkedBlockingQueue<>();

    public SubmissionQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Queue capacity must be positive: " + capacity);
        }
        this.slots = new Semaphore(capacity);
    }

    /** @return false if the queue is full. A true result must be followed by enqueue or release. */
    public boolean tryReserve() {
        return slots.tryAcquire();
    }

    public void release() {
        slots.release();
    }

    /** Fills a slot taken by {@link #tryReserve}. Never blocks. */
    public void enqueue(UUID id) {
        queue.add(id);
    }

    public UUID take() throws InterruptedException {
        UUID id = queue.take();
        slots.release();
        return id;
    }

    public int depth() {
        return queue.size();
    }
}
