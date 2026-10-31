package dev.judge.api.web;

import dev.judge.core.submission.Status;
import dev.judge.core.submission.Submission;
import dev.judge.core.submission.SubmissionEvent;
import dev.judge.core.submission.SubmissionEventListener;
import dev.judge.core.submission.SubmissionRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Each subscriber gets its own queue and its own virtual thread to write to it. Writing to a client
 * blocks when the client is slow, and that must happen on its thread and never on a judge worker's.
 */
public final class SubmissionEventStreams implements SubmissionEventListener {

    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    private final SubmissionRepository submissions;
    private final Map<UUID, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();

    public SubmissionEventStreams(SubmissionRepository submissions) {
        this.submissions = submissions;
    }

    SseEmitter subscribe(UUID id) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        Subscriber subscriber = new Subscriber(emitter);

        // Registered before reading the state, so that a transition in between cannot be missed.
        subscribers.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet()).add(subscriber);
        Submission current;
        try {
            current = submissions.find(id).orElseThrow(() -> new SubmissionNotFoundException(id));
        } catch (RuntimeException e) {
            remove(id, subscriber);
            throw e;
        }
        subscriber.events.add(new SubmissionEvent(id, current.status(), current.verdict()));

        Thread thread = Thread.ofVirtual().name("sse-" + id).start(subscriber::pump);
        Runnable stop = () -> {
            remove(id, subscriber);
            thread.interrupt();
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(error -> stop.run());
        return emitter;
    }

    @Override
    public void onEvent(SubmissionEvent event) {
        Set<Subscriber> interested = subscribers.get(event.id());
        if (interested != null) {
            interested.forEach(subscriber -> subscriber.events.add(event));
        }
    }

    private void remove(UUID id, Subscriber subscriber) {
        subscribers.computeIfPresent(id, (key, set) -> {
            set.remove(subscriber);
            return set.isEmpty() ? null : set;
        });
    }

    private static final class Subscriber {

        private final SseEmitter emitter;
        private final BlockingQueue<SubmissionEvent> events = new LinkedBlockingQueue<>();

        private Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private void pump() {
            int furthest = -1;
            try {
                while (true) {
                    SubmissionEvent event = events.take();
                    // The first state read can land behind a transition that was queued before it.
                    if (event.status().ordinal() <= furthest) {
                        continue;
                    }
                    furthest = event.status().ordinal();
                    emitter.send(SseEmitter.event().name("status").data(event, MediaType.APPLICATION_JSON));
                    if (event.status() == Status.DONE) {
                        emitter.complete();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                // The stream ended; the emitter is already finished.
            } catch (IOException | IllegalStateException e) {
                // The client went away, or the emitter timed out while this thread was writing.
                emitter.completeWithError(e);
            }
        }
    }
}
