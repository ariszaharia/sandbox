package dev.judge.core.submission;

import dev.judge.core.problem.Problem;
import dev.judge.core.problem.ProblemRepository;
import dev.judge.core.problem.ProblemSummary;
import dev.judge.core.verdict.Verdict;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    static final class Problems implements ProblemRepository {

        private final Map<UUID, Problem> problems = new ConcurrentHashMap<>();
        int existsCalls;

        @Override
        public void insert(Problem problem) {
            problems.put(problem.id(), problem);
        }

        @Override
        public List<ProblemSummary> list() {
            return List.of();
        }

        @Override
        public Optional<Problem> find(UUID id) {
            return Optional.ofNullable(problems.get(id));
        }

        @Override
        public boolean exists(UUID id) {
            existsCalls++;
            return problems.containsKey(id);
        }
    }

    static class Submissions implements SubmissionRepository {

        final Map<UUID, Submission> rows = new ConcurrentHashMap<>();
        final List<Status> statusHistory = new ArrayList<>();
        boolean failOnFinish;

        @Override
        public void insert(UUID id, NewSubmission submission) {
            rows.put(id, new Submission(
                    id, submission.problemId(), submission.language(), submission.sourceCode(),
                    Status.QUEUED, null, null, List.of(), Instant.now(), null));
        }

        @Override
        public Optional<Submission> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public void updateStatus(UUID id, Status status) {
            statusHistory.add(status);
            rows.computeIfPresent(id, (key, s) -> new Submission(
                    s.id(), s.problemId(), s.language(), s.sourceCode(), status, s.verdict(), s.compileOutput(),
                    s.results(), s.createdAt(), s.finishedAt()));
        }

        @Override
        public void addResult(UUID id, TestResult result) {
            rows.computeIfPresent(id, (key, s) -> {
                List<TestResult> results = new ArrayList<>(s.results());
                results.add(result);
                return new Submission(
                        s.id(), s.problemId(), s.language(), s.sourceCode(), s.status(), s.verdict(), s.compileOutput(),
                        results, s.createdAt(), s.finishedAt());
            });
        }

        @Override
        public void finish(UUID id, Verdict verdict, String compileOutput) {
            if (failOnFinish) {
                throw new IllegalStateException("Database is down");
            }
            rows.computeIfPresent(id, (key, s) -> new Submission(
                    s.id(), s.problemId(), s.language(), s.sourceCode(), Status.DONE, verdict, compileOutput,
                    s.results(), s.createdAt(), Instant.now()));
        }

        @Override
        public int failUnfinished() {
            List<UUID> unfinished = rows.values().stream()
                    .filter(s -> s.status() != Status.DONE)
                    .map(Submission::id)
                    .toList();
            unfinished.forEach(id -> finish(id, Verdict.INTERNAL_ERROR, null));
            return unfinished.size();
        }
    }
}
