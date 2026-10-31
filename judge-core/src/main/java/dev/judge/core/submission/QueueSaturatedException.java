package dev.judge.core.submission;

public final class QueueSaturatedException extends RuntimeException {

    public QueueSaturatedException() {
        super("The judge queue is full");
    }
}
