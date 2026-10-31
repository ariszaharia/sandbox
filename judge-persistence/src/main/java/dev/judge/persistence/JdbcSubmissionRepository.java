package dev.judge.persistence;

import dev.judge.core.submission.NewSubmission;
import dev.judge.core.submission.Status;
import dev.judge.core.submission.Submission;
import dev.judge.core.submission.SubmissionRepository;
import dev.judge.core.submission.TestResult;
import dev.judge.core.verdict.Verdict;
import dev.judge.runner.language.Language;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.unit.DataSize;

public final class JdbcSubmissionRepository implements SubmissionRepository {

    private final JdbcClient jdbc;

    public JdbcSubmissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID id, NewSubmission submission) {
        jdbc.sql("INSERT INTO submission (id, problem_id, language, source_code, status) VALUES (?, ?, ?, ?, ?)")
                .params(id, submission.problemId(), submission.language().name(), submission.sourceCode(), Status.QUEUED.name())
                .update();
    }

    @Override
    public Optional<Submission> find(UUID id) {
        List<TestResult> results = jdbc.sql("""
                        SELECT idx, verdict, runtime_ms, peak_memory_kb, stdout_preview
                        FROM test_result WHERE submission_id = ? ORDER BY idx
                        """)
                .param(id)
                .query((rs, row) -> testResult(rs))
                .list();
        return jdbc.sql("SELECT * FROM submission WHERE id = ?")
                .param(id)
                .query((rs, row) -> new Submission(
                        id,
                        rs.getObject("problem_id", UUID.class),
                        Language.valueOf(rs.getString("language")),
                        rs.getString("source_code"),
                        Status.valueOf(rs.getString("status")),
                        verdict(rs.getString("verdict")),
                        rs.getString("compile_output"),
                        results,
                        instant(rs, "created_at"),
                        instant(rs, "finished_at")))
                .optional();
    }

    @Override
    public void updateStatus(UUID id, Status status) {
        jdbc.sql("UPDATE submission SET status = ? WHERE id = ? AND status <> 'DONE'")
                .params(status.name(), id)
                .update();
    }

    @Override
    public void addResult(UUID id, TestResult result) {
        jdbc.sql("""
                        INSERT INTO test_result (submission_id, idx, verdict, runtime_ms, peak_memory_kb, stdout_preview)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        result.index(),
                        result.verdict().name(),
                        Math.toIntExact(result.runtime().toMillis()),
                        result.peakMemory() == null ? null : Math.toIntExact(result.peakMemory().toKilobytes()),
                        result.stdoutPreview())
                .update();
    }

    @Override
    public void finish(UUID id, Verdict verdict, String compileOutput) {
        jdbc.sql("""
                        UPDATE submission SET status = 'DONE', verdict = ?, compile_output = ?, finished_at = now()
                        WHERE id = ? AND status <> 'DONE'
                        """)
                .params(verdict.name(), compileOutput, id)
                .update();
    }

    @Override
    public int failUnfinished() {
        return jdbc.sql("""
                        UPDATE submission SET status = 'DONE', verdict = 'INTERNAL_ERROR', finished_at = now()
                        WHERE status <> 'DONE'
                        """)
                .update();
    }

    private static TestResult testResult(ResultSet rs) throws SQLException {
        Integer peakKb = rs.getObject("peak_memory_kb", Integer.class);
        return new TestResult(
                rs.getInt("idx"),
                Verdict.valueOf(rs.getString("verdict")),
                Duration.ofMillis(rs.getInt("runtime_ms")),
                peakKb == null ? null : DataSize.ofKilobytes(peakKb),
                rs.getString("stdout_preview"));
    }

    private static Verdict verdict(String name) {
        return name == null ? null : Verdict.valueOf(name);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
