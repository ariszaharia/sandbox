package dev.judge.runner;

public record CompileResult(
        boolean success,
        String output,
        TerminationReason reason) {

    public static CompileResult notRequired() {
        return new CompileResult(true, "", TerminationReason.NORMAL);
    }
}
