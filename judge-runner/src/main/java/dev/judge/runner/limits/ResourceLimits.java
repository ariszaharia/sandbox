package dev.judge.runner.limits;

import java.time.Duration;
import org.springframework.util.unit.DataSize;

/** What one container may consume. Wall clock and output are enforced from outside the container. */
public record ResourceLimits(
        DataSize memory,
        double cpus,
        int processes,
        int openFiles,
        DataSize fileSize,
        DataSize tmpfs,
        Duration cpuTime,
        Duration wallClock,
        DataSize output) {
}
