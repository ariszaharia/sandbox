package dev.judge.runner.language;

import java.time.Duration;
import java.util.List;

final class PythonSpec implements LanguageSpec {

    @Override
    public String image() {
        return "judge-runtime-python:3.12";
    }

    @Override
    public String sourceFileName() {
        return "main.py";
    }

    @Override
    public List<String> compileCommand(String workdir) {
        return List.of();
    }

    @Override
    public List<String> runCommand(String workdir) {
        return List.of(
                "python",
                "-X", "utf8",   // the container's POSIX locale would otherwise pick ASCII
                "-B",           // /work is read-only, so don't try to write bytecode there
                workdir + "/" + sourceFileName());
    }

    @Override
    public Duration compileTimeout() {
        return Duration.ZERO;
    }

    @Override
    public boolean indicatesOutOfMemory(int exitCode, String stderr) {
        return exitCode != 0 && stderr.stripTrailing().endsWith("MemoryError");
    }
}
