package dev.judge.core.problem;

public final class ProblemAlreadyExistsException extends RuntimeException {

    public ProblemAlreadyExistsException(String slug, Throwable cause) {
        super("A problem with slug '" + slug + "' already exists", cause);
    }
}
