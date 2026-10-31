package dev.judge.core.verdict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Line by line. Whitespace at the end of a line and blank lines at the end of the output are
 * ignored; leading whitespace and interior blank lines are significant.
 */
public final class ExactComparator implements OutputComparator {

    @Override
    public boolean matches(String expected, String actual) {
        return normalise(expected).equals(normalise(actual));
    }

    private static List<String> normalise(String text) {
        List<String> lines = new ArrayList<>(Arrays.stream(text.split("\n", -1)).map(String::stripTrailing).toList());
        while (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }
}
