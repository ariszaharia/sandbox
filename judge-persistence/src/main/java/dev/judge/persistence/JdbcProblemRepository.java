package dev.judge.persistence;

import dev.judge.core.problem.Problem;
import dev.judge.core.problem.ProblemAlreadyExistsException;
import dev.judge.core.problem.ProblemRepository;
import dev.judge.core.problem.ProblemSummary;
import dev.judge.core.problem.TestCase;
import dev.judge.core.verdict.ComparatorType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

public final class JdbcProblemRepository implements ProblemRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcProblemRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public void insert(Problem problem) {
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.sql("""
                                INSERT INTO problem (id, slug, title, statement, time_limit_ms, memory_limit_mb, comparator, tolerance)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """)
                        .params(
                                problem.id(),
                                problem.slug(),
                                problem.title(),
                                problem.statement(),
                                Math.toIntExact(problem.timeLimit().toMillis()),
                                Math.toIntExact(problem.memoryLimit().toMegabytes()),
                                problem.comparatorType().name(),
                                problem.tolerance())
                        .update();
                for (TestCase test : problem.testCases()) {
                    jdbc.sql("INSERT INTO test_case (id, problem_id, idx, input, expected, hidden) VALUES (?, ?, ?, ?, ?, ?)")
                            .params(UUID.randomUUID(), problem.id(), test.index(), test.input(), test.expectedOutput(), test.hidden())
                            .update();
                }
            });
        } catch (DuplicateKeyException e) {
            throw new ProblemAlreadyExistsException(problem.slug(), e);
        }
    }

    @Override
    public List<ProblemSummary> list() {
        return jdbc.sql("SELECT id, slug, title, time_limit_ms, memory_limit_mb FROM problem ORDER BY slug")
                .query((rs, row) -> new ProblemSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("title"),
                        Duration.ofMillis(rs.getInt("time_limit_ms")),
                        DataSize.ofMegabytes(rs.getInt("memory_limit_mb"))))
                .list();
    }

    @Override
    public Optional<Problem> find(UUID id) {
        List<TestCase> tests = jdbc.sql("SELECT idx, input, expected, hidden FROM test_case WHERE problem_id = ? ORDER BY idx")
                .param(id)
                .query((rs, row) -> new TestCase(
                        rs.getInt("idx"), rs.getString("input"), rs.getString("expected"), rs.getBoolean("hidden")))
                .list();
        return jdbc.sql("SELECT * FROM problem WHERE id = ?")
                .param(id)
                .query((rs, row) -> new Problem(
                        id,
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("statement"),
                        Duration.ofMillis(rs.getInt("time_limit_ms")),
                        DataSize.ofMegabytes(rs.getInt("memory_limit_mb")),
                        ComparatorType.valueOf(rs.getString("comparator")),
                        rs.getObject("tolerance", Double.class),
                        tests))
                .optional();
    }

    @Override
    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM problem WHERE id = ?)").param(id).query(Boolean.class).single();
    }
}
