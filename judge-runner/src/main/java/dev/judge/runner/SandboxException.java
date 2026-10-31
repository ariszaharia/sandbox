package dev.judge.runner;

/**
 * The sandbox failed, not the submission. This is what separates INTERNAL_ERROR from a
 * RUNTIME_ERROR, so nothing thrown here may be something a program can provoke.
 */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
