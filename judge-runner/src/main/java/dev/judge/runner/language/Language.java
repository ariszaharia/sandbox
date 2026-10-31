package dev.judge.runner.language;

public enum Language {
    JAVA(new JavaSpec()),
    PYTHON(new PythonSpec());

    private final LanguageSpec spec;

    Language(LanguageSpec spec) {
        this.spec = spec;
    }

    public LanguageSpec spec() {
        return spec;
    }
}
