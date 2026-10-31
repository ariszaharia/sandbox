package dev.judge.core.problem;

public record TestCase(
        int index,
        String input,
        String expectedOutput,
        boolean hidden) {}
