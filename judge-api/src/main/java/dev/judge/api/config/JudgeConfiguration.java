package dev.judge.api.config;

import dev.judge.api.web.SubmissionEventStreams;
import dev.judge.core.problem.ProblemRepository;
import dev.judge.core.submission.JudgeMetrics;
import dev.judge.core.submission.JudgeWorkerPool;
import dev.judge.core.submission.StartupReconciler;
import dev.judge.core.submission.SubmissionProcessor;
import dev.judge.core.submission.SubmissionQueue;
import dev.judge.core.submission.SubmissionRepository;
import dev.judge.core.submission.SubmissionService;
import dev.judge.persistence.JdbcProblemRepository;
import dev.judge.persistence.JdbcSubmissionRepository;
import dev.judge.runner.FakeSandbox;
import dev.judge.runner.Sandbox;
import dev.judge.runner.docker.DockerSandbox;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The only place the framework meets the core and the runner, which stay plain Java. */
@Configuration(proxyBeanMethods = false)
public class JudgeConfiguration {

    @Bean
    ProblemRepository problemRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        return new JdbcProblemRepository(jdbc, transaction);
    }

    @Bean
    SubmissionRepository submissionRepository(JdbcClient jdbc) {
        return new JdbcSubmissionRepository(jdbc);
    }

    @Bean
    Sandbox sandbox(JudgeProperties properties) {
        JudgeProperties.Sandbox sandbox = properties.sandbox();
        if ("fake".equals(sandbox.mode())) {
            return FakeSandbox.echoing();
        }
        return new DockerSandbox(new DockerSandbox.Config(
                sandbox.dockerBinary(),
                sandbox.dockerHost(),
                sandbox.cliTimeout(),
                sandbox.startGrace(),
                sandbox.stderrRetained(),
                sandbox.compileMemory(),
                sandbox.compileOutput(),
                sandbox.compileProcesses(),
                sandbox.openFiles(),
                sandbox.fileSize(),
                sandbox.tmpfs(),
                sandbox.cpus()));
    }

    @Bean
    SubmissionQueue submissionQueue(JudgeProperties properties) {
        return new SubmissionQueue(properties.queueCapacity());
    }

    @Bean
    JudgeMetrics judgeMetrics(MeterRegistry registry, SubmissionQueue queue) {
        return new JudgeMetrics(registry, queue);
    }

    @Bean
    SubmissionService submissionService(
            SubmissionRepository submissions,
            ProblemRepository problems,
            SubmissionQueue queue,
            JudgeMetrics metrics,
            JudgeProperties properties) {
        return new SubmissionService(submissions, problems, queue, metrics, properties.maxSourceSize());
    }

    @Bean
    SubmissionEventStreams submissionEventStreams(SubmissionRepository submissions) {
        return new SubmissionEventStreams(submissions);
    }

    @Bean
    SubmissionProcessor submissionProcessor(
            SubmissionRepository submissions,
            ProblemRepository problems,
            Sandbox sandbox,
            SubmissionEventStreams events,
            JudgeMetrics metrics,
            JudgeProperties properties) {
        return new SubmissionProcessor(
                submissions,
                problems,
                sandbox,
                events,
                metrics,
                new SubmissionProcessor.Settings(
                        properties.workspaceRoot(),
                        properties.sandbox().outputLimit(),
                        properties.sandbox().processLimit(),
                        properties.previewChars()));
    }

    /**
     * Reconciles while the context is still being built. Doing it any later, once the web server
     * is up, would sweep up submissions accepted in the meantime as if a dead process had left them.
     */
    @Bean(destroyMethod = "stop")
    JudgeWorkerPool judgeWorkerPool(
            SubmissionRepository submissions,
            SubmissionQueue queue,
            SubmissionProcessor processor,
            Sandbox sandbox,
            JudgeMetrics metrics,
            JudgeProperties properties) {
        new StartupReconciler(submissions, sandbox, metrics, properties.workspaceRoot()).reconcile();
        JudgeWorkerPool pool = new JudgeWorkerPool(queue, processor, properties.workers());
        pool.start();
        return pool;
    }
}
