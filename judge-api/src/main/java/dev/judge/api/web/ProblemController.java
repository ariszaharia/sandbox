package dev.judge.api.web;

import dev.judge.core.problem.Problem;
import dev.judge.core.problem.ProblemNotFoundException;
import dev.judge.core.problem.ProblemRepository;
import dev.judge.core.problem.ProblemSummary;
import dev.judge.core.problem.TestCase;
import dev.judge.core.verdict.ComparatorType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/problems")
class ProblemController {

    /** The limits bound what one submission can cost: a worker is held for up to time limit times tests. */
    record ProblemRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") @Size(max = 100) String slug,
            @NotBlank @Size(max = 200) String title,
            String statement,
            @Min(100) @Max(30_000) int timeLimitMs,
            @Min(32) @Max(2048) int memoryLimitMb,
            ComparatorType comparator,
            @PositiveOrZero Double tolerance,
            @NotEmpty @Size(max = 100) List<@Valid TestCaseRequest> testCases) {}

    /** @param hidden a test case is hidden unless it says otherwise */
    record TestCaseRequest(@NotNull String input, @NotNull String expected, Boolean hidden) {}

    record ProblemDetailResponse(
            UUID id,
            String slug,
            String title,
            String statement,
            long timeLimitMs,
            long memoryLimitMb,
            List<SampleResponse> samples) {}

    record SampleResponse(int index, String input, String expected) {}

    record ProblemSummaryResponse(UUID id, String slug, String title, long timeLimitMs, long memoryLimitMb) {

        static ProblemSummaryResponse from(ProblemSummary problem) {
            return new ProblemSummaryResponse(
                    problem.id(),
                    problem.slug(),
                    problem.title(),
                    problem.timeLimit().toMillis(),
                    problem.memoryLimit().toMegabytes());
        }
    }

    private final ProblemRepository problems;

    ProblemController(ProblemRepository problems) {
        this.problems = problems;
    }

    @GetMapping
    List<ProblemSummaryResponse> list() {
        return problems.list().stream().map(ProblemSummaryResponse::from).toList();
    }

    @GetMapping("/{id}")
    ProblemDetailResponse get(@PathVariable UUID id) {
        Problem problem = problems.find(id).orElseThrow(() -> new ProblemNotFoundException(id));
        return new ProblemDetailResponse(
                problem.id(),
                problem.slug(),
                problem.title(),
                problem.statement(),
                problem.timeLimit().toMillis(),
                problem.memoryLimit().toMegabytes(),
                problem.testCases().stream()
                        .filter(test -> !test.hidden())
                        .map(test -> new SampleResponse(test.index(), test.input(), test.expectedOutput()))
                        .toList());
    }

    /** Guarded by {@link AdminKeyInterceptor}. */
    @PostMapping
    ResponseEntity<ProblemSummaryResponse> create(@Valid @RequestBody ProblemRequest request) {
        ComparatorType comparator = request.comparator() == null ? ComparatorType.EXACT : request.comparator();
        if (comparator == ComparatorType.FLOAT && request.tolerance() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A FLOAT comparator needs a tolerance");
        }

        List<TestCase> tests = new ArrayList<>();
        for (TestCaseRequest test : request.testCases()) {
            tests.add(new TestCase(tests.size(), test.input(), test.expected(), test.hidden() == null || test.hidden()));
        }
        Problem problem = new Problem(
                UUID.randomUUID(),
                request.slug(),
                request.title(),
                request.statement() == null ? "" : request.statement(),
                Duration.ofMillis(request.timeLimitMs()),
                DataSize.ofMegabytes(request.memoryLimitMb()),
                comparator,
                request.tolerance(),
                tests);
        problems.insert(problem);

        return ResponseEntity.created(URI.create("/api/problems/" + problem.id()))
                .body(new ProblemSummaryResponse(
                        problem.id(), problem.slug(), problem.title(), request.timeLimitMs(), request.memoryLimitMb()));
    }
}
