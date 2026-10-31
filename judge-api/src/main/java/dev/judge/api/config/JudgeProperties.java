package dev.judge.api.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** Defaults live in application.yml. */
@ConfigurationProperties("judge")
public record JudgeProperties(
        String adminKey,
        boolean demoData,
        int queueCapacity,
        int workers,
        DataSize maxSourceSize,
        DataSize maxSubmissionBody,
        DataSize maxProblemBody,
        Path workspaceRoot,
        int previewChars,
        Sandbox sandbox) {

    /** @param mode {@code docker}, or {@code fake} to run without a daemon */
    public record Sandbox(
            String mode,
            String dockerBinary,
            String dockerHost,
            Duration cliTimeout,
            Duration startGrace,
            DataSize stderrRetained,
            DataSize compileMemory,
            DataSize compileOutput,
            int compileProcesses,
            int openFiles,
            DataSize fileSize,
            DataSize tmpfs,
            double cpus,
            DataSize outputLimit,
            int processLimit) {}
}
