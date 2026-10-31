package dev.judge.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.judge.runner.limits.ResourceLimits;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ContainerArgsTest {

    private static final ResourceLimits LIMITS = new ResourceLimits(
            DataSize.ofMegabytes(256), 1.0, 64, 64, DataSize.ofMegabytes(8), DataSize.ofMegabytes(64),
            Duration.ofMillis(1500), Duration.ofSeconds(1), DataSize.ofMegabytes(8));

    @Test
    void carriesEveryIsolationControl() {
        List<String> args = args(false);

        assertThat(args).contains(
                "--network=none",
                "--read-only",
                "--memory=268435456",
                "--memory-swap=268435456",
                "--cpus=1.0",
                "--pids-limit=64",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--log-driver=none",
                "--pull=never",
                "nofile=64:64",
                "fsize=8388608",
                "65534:65534",
                "/tmp:rw,noexec,nosuid,size=64m");
        assertThat(args).doesNotContain("--rm");
    }

    @Test
    void workdirIsReadOnlyForRunsAndWritableOnlyForCompiles() {
        assertThat(mount(false)).endsWith(",dst=/work,readonly");
        assertThat(mount(true)).endsWith(",dst=/work");
    }

    @Test
    void cpuSecondsRoundUpAndHardLimitIsOneLater() {
        assertThat(args(false)).contains("cpu=2:3");
    }

    @Test
    void imageAndCommandComeLast() {
        assertThat(args(false)).endsWith("image:1", "java", "Main");
    }

    private static String mount(boolean writable) {
        List<String> args = args(writable);
        return args.get(args.indexOf("--mount") + 1);
    }

    private static List<String> args(boolean writable) {
        return ContainerArgs.create("name", "image:1", Path.of("sub-1"), writable, LIMITS, List.of("java", "Main"));
    }
}
