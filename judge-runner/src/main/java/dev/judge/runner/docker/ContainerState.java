package dev.judge.runner.docker;

import dev.judge.runner.SandboxException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The daemon's record of a container. The exit code comes from here and not from the client,
 * whose own exit status cannot be told apart from a failure of the client itself.
 */
record ContainerState(
        String status,
        int exitCode,
        boolean oomKilled,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String error) {

    static final String FORMAT = String.join("|",
            "{{.State.Status}}",
            "{{.State.ExitCode}}",
            "{{.State.OOMKilled}}",
            "{{.Created}}",
            "{{.State.StartedAt}}",
            "{{.State.FinishedAt}}",
            "{{.State.Error}}");

    static ContainerState parse(String line) {
        String[] parts = line.split("\\|", 7);
        if (parts.length != 7) {
            throw new SandboxException("Unexpected docker inspect output: " + line);
        }
        try {
            return new ContainerState(
                    parts[0],
                    Integer.parseInt(parts[1]),
                    Boolean.parseBoolean(parts[2]),
                    Instant.parse(parts[3]),
                    Instant.parse(parts[4]),
                    Instant.parse(parts[5]),
                    parts[6]);
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new SandboxException("Unexpected docker inspect output: " + line, e);
        }
    }

    boolean exited() {
        return "exited".equals(status);
    }

    /** Docker reports year 1 for a timestamp that was never set. */
    boolean everStarted() {
        return startedAt.isAfter(createdAt);
    }

    Duration wallTime() {
        return Duration.between(startedAt, finishedAt);
    }

    Duration startupTime() {
        return Duration.between(createdAt, startedAt);
    }
}
