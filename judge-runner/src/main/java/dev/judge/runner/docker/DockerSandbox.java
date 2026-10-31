package dev.judge.runner.docker;

import dev.judge.runner.CompileRequest;
import dev.judge.runner.CompileResult;
import dev.judge.runner.RunRequest;
import dev.judge.runner.RunResult;
import dev.judge.runner.Sandbox;
import dev.judge.runner.SandboxException;
import dev.judge.runner.TerminationReason;
import dev.judge.runner.language.LanguageSpec;
import dev.judge.runner.limits.OutputCapture;
import dev.judge.runner.limits.ResourceLimits;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

/**
 * One container per execution, destroyed afterwards. The lifecycle is create, attach, inspect,
 * remove rather than {@code docker run --rm}, because {@code --rm} deletes the container before
 * its exit code and OOM flag can be read.
 */
public final class DockerSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    private static final int EXIT_SIGNAL_BASE = 128;
    private static final int EXIT_SIGXCPU = EXIT_SIGNAL_BASE + 24;
    private static final Pattern PEAK_MEMORY = Pattern.compile("\\n?@@judge-peak-memory=(\\d+)\\n?\\z");

    private final DockerCli cli;
    private final Config config;

    /**
     * @param startGrace     added to the wall-clock deadline for the kill, since a container takes
     *                       time to start and that time is not billed to the program
     * @param stderrRetained stderr kept in memory; the rest is drained and counted against the output limit
     * @param compileMemory  compilers need more room than the programs they build
     */
    public record Config(
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
            double cpus) {}

    public DockerSandbox(Config config) {
        this.config = config;
        this.cli = new DockerCli(config.dockerBinary(), config.dockerHost(), config.cliTimeout());
    }

    @Override
    public CompileResult compile(CompileRequest request) {
        LanguageSpec spec = request.language().spec();
        List<String> command = spec.compileCommand(ContainerArgs.CONTAINER_WORKDIR);
        if (command.isEmpty()) {
            return CompileResult.notRequired();
        }

        ResourceLimits limits = new ResourceLimits(
                config.compileMemory(),
                config.cpus(),
                config.compileProcesses(),
                config.openFiles(),
                config.fileSize(),
                config.tmpfs(),
                spec.compileTimeout(),
                spec.compileTimeout(),
                config.compileOutput());

        Execution execution = execute(spec, request.workdir(), true, limits, "", command);
        return new CompileResult(
                execution.reason() == TerminationReason.NORMAL && execution.state().exitCode() == 0,
                (execution.stdout() + execution.stderr()).strip(),
                execution.reason());
    }

    @Override
    public RunResult run(RunRequest request) {
        LanguageSpec spec = request.language().spec();
        ResourceLimits limits = new ResourceLimits(
                request.memoryLimit(),
                config.cpus(),
                request.processLimit(),
                config.openFiles(),
                config.fileSize(),
                config.tmpfs(),
                request.cpuLimit(),
                request.wallClockLimit(),
                request.outputLimit());

        Execution execution = execute(
                spec, request.workdir(), false, limits, request.stdin(), spec.runCommand(ContainerArgs.CONTAINER_WORKDIR));
        return new RunResult(
                execution.state().exitCode(),
                execution.stdout(),
                execution.stderr(),
                execution.state().wallTime(),
                execution.peakMemory(),
                execution.state().startupTime(),
                execution.reason());
    }

    @Override
    public void reclaimOrphans() {
        String ids = cli.run("ps", "--all", "--quiet", "--filter", "label=" + ContainerArgs.LABEL);
        if (ids.isBlank()) {
            return;
        }
        List<String> orphans = ids.lines().toList();
        log.warn("Removing {} sandbox containers left by a previous process", orphans.size());
        orphans.forEach(this::removeQuietly);
    }

    private Execution execute(
            LanguageSpec spec, Path workdir, boolean writable, ResourceLimits limits, String stdin, List<String> command) {
        String name = "judge-" + UUID.randomUUID();
        cli.run(ContainerArgs.create(name, spec.image(), workdir, writable, limits, command));
        try {
            return attach(name, spec, limits, stdin);
        } finally {
            removeQuietly(name);
        }
    }

    private Execution attach(String name, LanguageSpec spec, ResourceLimits limits, String stdin) {
        // Whichever limit trips first owns the verdict; a later kill must not overwrite it.
        AtomicReference<TerminationReason> killedFor = new AtomicReference<>();
        Runnable outputBreached = () -> {
            if (killedFor.compareAndSet(null, TerminationReason.OUTPUT_CAP)) {
                Thread.ofPlatform().daemon().name("docker-kill").start(() -> killQuietly(name));
            }
        };

        Process client;
        try {
            client = cli.builder(List.of("start", "--attach", "--interactive", name)).start();
        } catch (IOException e) {
            throw new SandboxException("Could not launch docker start", e);
        }

        try {
            long outputBytes = limits.output().toBytes();
            OutputCapture stdout = OutputCapture.start(
                    client.getInputStream(), Math.toIntExact(outputBytes), outputBytes, outputBreached);
            OutputCapture stderr = OutputCapture.start(
                    client.getErrorStream(), Math.toIntExact(config.stderrRetained().toBytes()), outputBytes, outputBreached);
            writeStdin(client, stdin);

            Duration deadline = limits.wallClock().plus(config.startGrace());
            if (!client.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                // Destroying the client would leave the container running. Only the daemon can stop it.
                if (killedFor.compareAndSet(null, TerminationReason.TIMEOUT)) {
                    killQuietly(name);
                }
                if (!client.waitFor(cli.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    client.destroyForcibly();
                }
            }
            stdout.await(cli.timeout());
            stderr.await(cli.timeout());

            ContainerState state = settledState(name, killedFor.get() != null);
            String rawStderr = stderr.text();
            return new Execution(
                    state,
                    stdout.text(),
                    stripPeakMemory(rawStderr),
                    peakMemory(rawStderr),
                    classify(killedFor.get(), state, spec, limits.wallClock(), rawStderr));
        } catch (InterruptedException e) {
            killQuietly(name);
            Thread.currentThread().interrupt();
            throw new SandboxException("Interrupted while running " + name, e);
        } finally {
            client.destroyForcibly();
        }
    }

    private void writeStdin(Process client, String stdin) {
        // On its own thread: a program that never reads must not be able to stall the wall-clock check.
        Thread.ofPlatform().daemon().name("stdin-writer").start(() -> {
            try (OutputStream out = client.getOutputStream()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // The program exited without reading all of it, which is its right.
            }
        });
    }

    private ContainerState settledState(String name, boolean weKilledIt) {
        ContainerState state = inspect(name);
        if (!state.exited() && weKilledIt) {
            cli.run("wait", name);
            state = inspect(name);
        }
        if (!state.everStarted() || !state.error().isBlank()) {
            throw new SandboxException("Container " + name + " failed to start: " + state.error());
        }
        if (!state.exited()) {
            throw new SandboxException("Lost the client for " + name + " while it was " + state.status());
        }
        return state;
    }

    private ContainerState inspect(String name) {
        return ContainerState.parse(cli.run("inspect", "--format", ContainerState.FORMAT, name));
    }

    static TerminationReason classify(
            TerminationReason killedFor, ContainerState state, LanguageSpec spec, Duration wallClock, String stderr) {
        if (killedFor != null) {
            return killedFor;
        }
        if (state.oomKilled() || spec.indicatesOutOfMemory(state.exitCode(), stripPeakMemory(stderr))) {
            return TerminationReason.OOM;
        }
        // The kill deadline includes startGrace, so a program can finish over its limit without being killed.
        if (state.exitCode() == EXIT_SIGXCPU || state.wallTime().compareTo(wallClock) > 0) {
            return TerminationReason.TIMEOUT;
        }
        return state.exitCode() > EXIT_SIGNAL_BASE ? TerminationReason.SIGNAL : TerminationReason.NORMAL;
    }

    static String stripPeakMemory(String stderr) {
        return PEAK_MEMORY.matcher(stderr).replaceFirst("");
    }

    static DataSize peakMemory(String stderr) {
        Matcher matcher = PEAK_MEMORY.matcher(stderr);
        if (!matcher.find()) {
            return null;
        }
        try {
            return DataSize.ofBytes(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void killQuietly(String name) {
        try {
            cli.run("kill", name);
        } catch (SandboxException e) {
            // Usually it already exited. If the daemon is gone, the inspect that follows reports it.
            log.debug("Kill of {} failed: {}", name, e.getMessage());
        }
    }

    private void removeQuietly(String name) {
        try {
            cli.run("rm", "--force", name);
        } catch (SandboxException e) {
            // Must not replace the result of the execution. The next startup sweep gets it.
            log.warn("Could not remove container {}: {}", name, e.getMessage());
        }
    }

    private record Execution(
            ContainerState state, String stdout, String stderr, DataSize peakMemory, TerminationReason reason) {}
}
