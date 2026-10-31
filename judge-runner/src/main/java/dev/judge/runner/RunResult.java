package dev.judge.runner;

import java.time.Duration;
import org.springframework.util.unit.DataSize;

public record RunResult(
        int exitCode,
        String stdout,
        String stderr,
        Duration wallTime,       // from the daemon's clock, so container startup is not billed to the program
        DataSize peakMemory,     // null when the program was killed before it could be read
        Duration startupTime,    // container create to process start
        TerminationReason reason) {}
