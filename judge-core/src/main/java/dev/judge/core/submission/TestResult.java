package dev.judge.core.submission;

import dev.judge.core.verdict.Verdict;
import java.time.Duration;
import org.springframework.util.unit.DataSize;

public record TestResult(
        int index,
        Verdict verdict,
        Duration runtime,
        DataSize peakMemory,        // null when the program was killed before it could be read
        String stdoutPreview) {}    // truncated, and null for a hidden test: a program can print its stdin
