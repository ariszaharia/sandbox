package dev.judge.runner;

import dev.judge.runner.language.Language;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.util.unit.DataSize;

public record RunRequest(
        Path workdir,            // host dir, bind-mounted read-only
        Language language,
        String stdin,
        Duration wallClockLimit,
        Duration cpuLimit,
        DataSize memoryLimit,
        DataSize outputLimit,
        int processLimit) {}
