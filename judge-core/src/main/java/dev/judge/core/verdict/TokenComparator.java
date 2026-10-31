package dev.judge.core.verdict;

import java.util.List;

/** Compares whitespace-separated tokens, so line breaks and spacing carry no meaning. */
public final class TokenComparator implements OutputComparator {

    @Override
    public boolean matches(String expected, String actual) {
        return tokens(expected).equals(tokens(actual));
    }

    static List<String> tokens(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() ? List.of() : List.of(stripped.split("\\s+"));
    }
}
