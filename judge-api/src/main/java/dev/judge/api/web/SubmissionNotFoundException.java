package dev.judge.api.web;

import java.util.UUID;

final class SubmissionNotFoundException extends RuntimeException {

    SubmissionNotFoundException(UUID id) {
        super("No submission with id " + id);
    }
}
