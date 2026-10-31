package dev.judge.runner.language;

import java.time.Duration;
import java.util.List;

final class JavaSpec implements LanguageSpec {

    private static final String OUT_OF_MEMORY = "java.lang.OutOfMemoryError";
    // Hitting the pid limit surfaces as an OutOfMemoryError, but it is not a memory verdict.
    private static final String THREAD_LIMIT = "unable to create native thread";

    @Override
    public String image() {
        return "judge-runtime-java:21";
    }

    @Override
    public String sourceFileName() {
        return "Main.java";
    }

    @Override
    public List<String> compileCommand(String workdir) {
        return List.of(
                "javac",
                "-proc:none",                 // annotation processors would run submitted code at compile time
                "-encoding", "UTF-8",
                "-J-XX:+UseSerialGC",
                "-J-XX:MaxRAMPercentage=75",
                "-d", workdir,
                workdir + "/" + sourceFileName());
    }

    @Override
    public List<String> runCommand(String workdir) {
        return List.of(
                "java",
                "-XX:+UseSerialGC",           // one CPU, and GC threads count against the pid limit
                "-XX:-UsePerfData",           // no mmap'd file in /tmp
                "-XX:MaxRAMPercentage=75",    // the default of 25% is a quarter of what the problem allows
                "-XX:+ExitOnOutOfMemoryError",// a catch block must not be able to survive MLE
                // The JVM's own messages default to stdout, where they would be compared as the
                // program's answer and where indicatesOutOfMemory would never see them.
                "-XX:+DisplayVMOutputToStderr",
                "-Xlog:disable",
                "-Xlog:all=warning:stderr",
                "-Dstdout.encoding=UTF-8",    // the container's POSIX locale would print '?' for non-ASCII
                "-Dstderr.encoding=UTF-8",
                "-cp", workdir,
                "Main");
    }

    @Override
    public Duration compileTimeout() {
        return Duration.ofSeconds(15);
    }

    @Override
    public boolean indicatesOutOfMemory(int exitCode, String stderr) {
        return exitCode != 0
                && stderr.contains(OUT_OF_MEMORY)
                && !stderr.contains(THREAD_LIMIT);
    }
}
