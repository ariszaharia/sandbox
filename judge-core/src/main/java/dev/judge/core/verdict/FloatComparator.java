package dev.judge.core.verdict;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Token by token, with numbers equal within an absolute tolerance and everything else exact.
 *
 * <p>Numbers are matched by pattern first because {@link Double#parseDouble} also accepts
 * {@code NaN}, {@code Infinity} and suffixes like {@code 1.5d}, none of which is a correct answer
 * to a problem whose expected output is a number.
 */
public final class FloatComparator implements OutputComparator {

    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    private final double tolerance;

    public FloatComparator(double tolerance) {
        if (!(tolerance >= 0)) {
            throw new IllegalArgumentException("Tolerance must not be negative: " + tolerance);
        }
        this.tolerance = tolerance;
    }

    @Override
    public boolean matches(String expected, String actual) {
        List<String> expectedTokens = TokenComparator.tokens(expected);
        List<String> actualTokens = TokenComparator.tokens(actual);
        if (expectedTokens.size() != actualTokens.size()) {
            return false;
        }
        for (int i = 0; i < expectedTokens.size(); i++) {
            if (!tokenMatches(expectedTokens.get(i), actualTokens.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean tokenMatches(String expected, String actual) {
        if (NUMBER.matcher(expected).matches() && NUMBER.matcher(actual).matches()) {
            // Written so that an overflow to infinity, whose difference is NaN, does not match.
            return Math.abs(Double.parseDouble(expected) - Double.parseDouble(actual)) <= tolerance;
        }
        return expected.equals(actual);
    }
}
