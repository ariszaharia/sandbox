package dev.judge.api.web;

import dev.judge.core.submission.InvalidSubmissionException;
import dev.judge.core.submission.NewSubmission;
import dev.judge.core.submission.SubmissionRepository;
import dev.judge.core.submission.SubmissionService;
import dev.judge.runner.language.Language;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/submissions")
class SubmissionController {

    record SubmissionRequest(
            @NotNull UUID problemId,
            @NotNull String language,
            @NotNull String sourceCode) {}

    record SubmissionAccepted(UUID id) {}

    private final SubmissionService service;
    private final SubmissionRepository submissions;
    private final SubmissionEventStreams streams;

    SubmissionController(SubmissionService service, SubmissionRepository submissions, SubmissionEventStreams streams) {
        this.service = service;
        this.submissions = submissions;
        this.streams = streams;
    }

    @PostMapping
    ResponseEntity<SubmissionAccepted> submit(@Valid @RequestBody SubmissionRequest request) {
        UUID id = service.submit(new NewSubmission(request.problemId(), language(request.language()), request.sourceCode()));
        return ResponseEntity.accepted()
                .location(URI.create("/api/submissions/" + id))
                .body(new SubmissionAccepted(id));
    }

    @GetMapping("/{id}")
    SubmissionResponse get(@PathVariable UUID id) {
        return submissions.find(id)
                .map(SubmissionResponse::from)
                .orElseThrow(() -> new SubmissionNotFoundException(id));
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable UUID id) {
        return streams.subscribe(id);
    }

    private static Language language(String name) {
        try {
            return Language.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new InvalidSubmissionException(
                    "Unsupported language '" + name + "'; supported: " + Arrays.toString(Language.values()));
        }
    }
}
