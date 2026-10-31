package dev.judge.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.judge.runner.SandboxException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ContainerStateTest {

    @Test
    void parsesDaemonOutput() {
        ContainerState state = ContainerState.parse(
                "exited|3|false|2026-10-31T10:13:19.615650586Z|2026-10-31T10:13:19.825235541Z|2026-10-31T10:13:19.974309167Z|");

        assertThat(state.exited()).isTrue();
        assertThat(state.exitCode()).isEqualTo(3);
        assertThat(state.everStarted()).isTrue();
        assertThat(state.wallTime()).isEqualTo(Duration.ofNanos(149_073_626));
        assertThat(state.startupTime()).isEqualTo(Duration.ofNanos(209_584_955));
    }

    @Test
    void aContainerThatNeverStartedIsDetected() {
        ContainerState state = ContainerState.parse(
                "created|127|false|2026-10-31T10:13:19Z|0001-01-01T00:00:00Z|0001-01-01T00:00:00Z|exec format | error");

        assertThat(state.everStarted()).isFalse();
        assertThat(state.error()).isEqualTo("exec format | error");
    }

    @Test
    void garbageIsASandboxFailure() {
        assertThatThrownBy(() -> ContainerState.parse("nonsense")).isInstanceOf(SandboxException.class);
    }
}
