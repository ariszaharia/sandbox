package dev.judge.runner;

import java.time.Duration;
import java.util.function.Function;
import org.springframework.util.unit.DataSize;

/** Canned results, so everything above the sandbox can be exercised without a daemon. */
public final class FakeSandbox implements Sandbox {

    private final Function<RunRequest, RunResult> behaviour;

    public FakeSandbox(Function<RunRequest, RunResult> behaviour) {
        this.behaviour = behaviour;
    }

    /** Compiles anything and prints its input back. */
    public static FakeSandbox echoing() {
        return new FakeSandbox(request -> printed(request.stdin()));
    }

    public static RunResult printed(String stdout) {
        return result(0, stdout, TerminationReason.NORMAL);
    }

    public static RunResult result(int exitCode, String stdout, TerminationReason reason) {
        return new RunResult(exitCode, stdout, "", Duration.ofMillis(1), DataSize.ofMegabytes(1), Duration.ofMillis(1), reason);
    }

    @Override
    public CompileResult compile(CompileRequest request) {
        return CompileResult.notRequired();
    }

    @Override
    public RunResult run(RunRequest request) {
        return behaviour.apply(request);
    }
}
