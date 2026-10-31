package dev.judge.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.judge.runner.TerminationReason;
import dev.judge.runner.language.Language;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class DockerSandboxClassifyTest {

    private static final Duration LIMIT = Duration.ofSeconds(1);

    @Test
    void cleanExitIsNormalEvenWithNonZeroCode() {
        assertThat(classify(null, state(1, false, 100), "")).isEqualTo(TerminationReason.NORMAL);
    }

    @Test
    void ourOwnKillWinsOverEverything() {
        assertThat(classify(TerminationReason.OUTPUT_CAP, state(137, true, 5000), ""))
                .isEqualTo(TerminationReason.OUTPUT_CAP);
    }

    @Test
    void daemonOomFlagIsAMemoryVerdict() {
        assertThat(classify(null, state(137, true, 100), "")).isEqualTo(TerminationReason.OOM);
    }

    @Test
    void selfInflictedSigkillIsNotAMemoryVerdict() {
        assertThat(classify(null, state(137, false, 100), "")).isEqualTo(TerminationReason.SIGNAL);
    }

    @Test
    void jvmHeapExhaustionIsAMemoryVerdictWithoutAnyOomKill() {
        String stderr = "Terminating due to java.lang.OutOfMemoryError: Java heap space\n\n@@judge-peak-memory=22130688\n";
        assertThat(classify(null, state(3, false, 100), stderr)).isEqualTo(TerminationReason.OOM);
    }

    @Test
    void jvmHittingThePidLimitIsNotAMemoryVerdict() {
        String stderr = "java.lang.OutOfMemoryError: unable to create native thread: possibly out of memory or process/resource limits reached";
        assertThat(classify(null, state(1, false, 100), stderr)).isEqualTo(TerminationReason.NORMAL);
    }

    @Test
    void finishingOverTheLimitInsideTheGraceWindowIsATimeout() {
        assertThat(classify(null, state(0, false, 1500), "")).isEqualTo(TerminationReason.TIMEOUT);
    }

    @Test
    void cpuRlimitSignalIsATimeout() {
        assertThat(classify(null, state(152, false, 100), "")).isEqualTo(TerminationReason.TIMEOUT);
    }

    @Test
    void peakMemoryIsReadFromTheTrailerAndRemoved() {
        String stderr = "boom\n\n@@judge-peak-memory=2048\n";

        assertThat(DockerSandbox.peakMemory(stderr)).isEqualTo(DataSize.ofBytes(2048));
        assertThat(DockerSandbox.stripPeakMemory(stderr)).isEqualTo("boom\n");
    }

    @Test
    void peakMemoryIsUnknownWhenTheTrailerIsMissing() {
        assertThat(DockerSandbox.peakMemory("killed before reporting")).isNull();
    }

    @Test
    void trailerOnlyCountsAtTheVeryEnd() {
        String forged = "@@judge-peak-memory=1\nstill printing";

        assertThat(DockerSandbox.peakMemory(forged)).isNull();
        assertThat(DockerSandbox.stripPeakMemory(forged)).isEqualTo(forged);
    }

    private static TerminationReason classify(TerminationReason killedFor, ContainerState state, String stderr) {
        return DockerSandbox.classify(killedFor, state, Language.JAVA.spec(), LIMIT, stderr);
    }

    private static ContainerState state(int exitCode, boolean oomKilled, long wallMillis) {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant started = created.plusMillis(200);
        return new ContainerState("exited", exitCode, oomKilled, created, started, started.plusMillis(wallMillis), "");
    }
}
