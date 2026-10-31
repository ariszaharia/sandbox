package dev.judge.core.problem;

import java.util.UUID;

public final class ProblemNotFoundException extends RuntimeException {

    public ProblemNotFoundException(UUID id) {
        super("No problem with id " + id);
    }
}
