package dev.judge.runner;

/** Runs code it does not trust. Throws {@link SandboxException} when the sandbox itself fails. */
public interface Sandbox {

    CompileResult compile(CompileRequest request);

    RunResult run(RunRequest request);

    /** Removes containers left behind by a previous process. Safe to call when there are none. */
    default void reclaimOrphans() {
    }
}
