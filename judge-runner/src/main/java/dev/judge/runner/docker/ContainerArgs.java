package dev.judge.runner.docker;

import dev.judge.runner.limits.ResourceLimits;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The arguments to {@code docker create}. Every flag answers a specific threat; see the README. */
final class ContainerArgs {

    static final String LABEL = "judge.sandbox=true";
    static final String CONTAINER_WORKDIR = "/work";

    private ContainerArgs() {
    }

    static List<String> create(
            String name, String image, Path workdir, boolean writableWorkdir, ResourceLimits limits, List<String> command) {

        // SIGXCPU at the soft limit can be told apart from an OOM kill or a self-inflicted SIGKILL;
        // the hard limit one second later catches a program that ignores the signal.
        long softCpuSeconds = Math.max(1, limits.cpuTime().toSeconds() + (limits.cpuTime().toNanosPart() > 0 ? 1 : 0));
        String mount = "type=bind,src=" + workdir.toAbsolutePath() + ",dst=" + CONTAINER_WORKDIR
                + (writableWorkdir ? "" : ",readonly");

        List<String> args = new ArrayList<>(List.of(
                "create",
                "--name", name,
                "--label", LABEL,
                "--interactive",
                "--pull=never",                   // a missing image is our bug; never fetch at submission time
                "--log-driver=none",              // json-file would persist a flood of stdout on the host's disk
                "--network=none",
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=" + limits.tmpfs().toMegabytes() + "m",
                "--memory=" + limits.memory().toBytes(),
                "--memory-swap=" + limits.memory().toBytes(),   // must equal memory or OOM never fires
                "--cpus=" + limits.cpus(),
                "--pids-limit=" + limits.processes(),
                "--ulimit", "nofile=" + limits.openFiles() + ":" + limits.openFiles(),
                "--ulimit", "fsize=" + limits.fileSize().toBytes(),
                "--ulimit", "cpu=" + softCpuSeconds + ":" + (softCpuSeconds + 1),
                "--ulimit", "core=0",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--user", "65534:65534",
                "--mount", mount,
                image));
        args.addAll(command);
        return args;
    }
}
