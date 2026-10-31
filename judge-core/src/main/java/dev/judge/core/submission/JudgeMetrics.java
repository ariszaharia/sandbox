package dev.judge.core.submission;

import dev.judge.core.verdict.Verdict;
import dev.judge.runner.language.Language;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

public final class JudgeMetrics {

    private final MeterRegistry registry;
    private final Counter rejected;
    private final Timer containerStartup;

    public JudgeMetrics(MeterRegistry registry, SubmissionQueue queue) {
        this.registry = registry;
        this.rejected = Counter.builder("judge.queue.rejected.total").register(registry);
        this.containerStartup = Timer.builder("judge.container.startup.duration")
                .publishPercentileHistogram()
                .register(registry);
        Gauge.builder("judge.queue.depth", queue, SubmissionQueue::depth).register(registry);
    }

    void queueRejected() {
        rejected.increment();
    }

    void submissionsFinished(Verdict verdict, int count) {
        Counter.builder("judge.submissions.total").tag("verdict", verdict.name()).register(registry).increment(count);
    }

    void testExecuted(Language language, Duration wallTime, Duration startupTime) {
        Timer.builder("judge.execution.duration")
                .tag("language", language.name())
                .publishPercentileHistogram()
                .register(registry)
                .record(wallTime);
        containerStartup.record(startupTime);
    }
}
