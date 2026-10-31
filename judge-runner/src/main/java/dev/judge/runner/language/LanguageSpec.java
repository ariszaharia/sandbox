package dev.judge.runner.language;

import java.time.Duration;
import java.util.List;

/**
 * Everything specific to one language. If adding a language touches the orchestrator, this
 * abstraction is wrong.
 *
 * <p>Paths are strings because they are paths inside a Linux container: a {@code java.nio.file.Path}
 * would render them with the host's separators, which on Windows gives {@code \work\Main.java}.
 */
public interface LanguageSpec {

    String image();

    String sourceFileName();

    /** Empty for interpreted languages. */
    List<String> compileCommand(String workdir);

    List<String> runCommand(String workdir);

    Duration compileTimeout();

    /**
     * Whether the program ran out of memory in a way the cgroup never saw. A managed runtime hits
     * its own ceiling first and exits normally, leaving no OOM kill for the daemon to report.
     */
    default boolean indicatesOutOfMemory(int exitCode, String stderr) {
        return false;
    }
}
