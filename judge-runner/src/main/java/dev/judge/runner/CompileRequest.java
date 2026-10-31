package dev.judge.runner;

import dev.judge.runner.language.Language;
import java.nio.file.Path;

public record CompileRequest(
        Path workdir,            // host dir, bind-mounted read-write for this step only
        Language language) {}
