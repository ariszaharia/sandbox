package dev.judge.core.submission;

/** Called on a worker thread, so it must never block: a slow client must not stall the judge. */
@FunctionalInterface
public interface SubmissionEventListener {

    void onEvent(SubmissionEvent event);
}
