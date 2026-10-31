package dev.judge.runner.docker;

import dev.judge.runner.SandboxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs short docker commands. Every call has a deadline, because a hung daemon must not hang a worker. */
final class DockerCli {

    private final String binary;
    private final String dockerHost;
    private final Duration timeout;

    DockerCli(String binary, String dockerHost, Duration timeout) {
        this.binary = binary;
        this.dockerHost = dockerHost;
        this.timeout = timeout;
    }

    Duration timeout() {
        return timeout;
    }

    ProcessBuilder builder(List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(binary);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (dockerHost != null && !dockerHost.isBlank()) {
            builder.environment().put("DOCKER_HOST", dockerHost);
        }
        return builder;
    }

    String run(String... args) {
        return run(List.of(args));
    }

    String run(List<String> args) {
        Process process;
        try {
            process = builder(args).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new SandboxException("Could not launch " + binary, e);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofPlatform().daemon().name("docker-cli").start(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException e) {
                // Process destroyed after a timeout; the exit path below reports it.
            }
        });

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new SandboxException("docker " + args.getFirst() + " did not finish within " + timeout);
            }
            reader.join(timeout);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SandboxException("Interrupted during docker " + args.getFirst(), e);
        }

        String text = output.toString(StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            throw new SandboxException("docker " + args.getFirst() + " exited " + process.exitValue() + ": " + text);
        }
        return text;
    }
}
