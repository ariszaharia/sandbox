package dev.judge.runner;

public enum TerminationReason {
    NORMAL,
    TIMEOUT,
    OOM,
    OUTPUT_CAP,
    SIGNAL
}
