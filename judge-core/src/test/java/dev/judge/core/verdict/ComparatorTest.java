package dev.judge.core.verdict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ComparatorTest {

    private final OutputComparator exact = new ExactComparator();
    private final OutputComparator token = new TokenComparator();
    private final OutputComparator floats = new FloatComparator(0.001);

    @Test
    void exact_ignoresTrailingWhitespaceOnEachLine() {
        assertThat(exact.matches("a\nb\n", "a  \nb\t\n")).isTrue();
    }

    @Test
    void exact_ignoresCarriageReturns() {
        assertThat(exact.matches("a\nb\n", "a\r\nb\r\n")).isTrue();
    }

    @Test
    void exact_ignoresTrailingNewlines() {
        assertThat(exact.matches("a\n", "a")).isTrue();
        assertThat(exact.matches("a", "a\n\n\n")).isTrue();
    }

    @Test
    void exact_treatsInteriorBlankLinesAsSignificant() {
        assertThat(exact.matches("a\nb\n", "a\n\nb\n")).isFalse();
    }

    @Test
    void exact_treatsLeadingWhitespaceAsSignificant() {
        assertThat(exact.matches("a\n", " a\n")).isFalse();
    }

    @Test
    void exact_doesNotAcceptEmptyOutputForNonEmptyExpected() {
        assertThat(exact.matches("3\n", "")).isFalse();
    }

    @Test
    void exact_acceptsEmptyOutputWhenThatIsExpected() {
        assertThat(exact.matches("", "\n")).isTrue();
    }

    @Test
    void token_ignoresAllSpacing() {
        assertThat(token.matches("1 2 3\n", "1\n2   3")).isTrue();
    }

    @Test
    void token_comparesTokens() {
        assertThat(token.matches("1 2", "1 2 3")).isFalse();
        assertThat(token.matches("", "   \n")).isTrue();
    }

    @Test
    void float_acceptsWithinTolerance() {
        assertThat(floats.matches("3.1415", "3.1419")).isTrue();
        assertThat(floats.matches("1000", "1e3")).isTrue();
    }

    @Test
    void float_rejectsOutsideTolerance() {
        assertThat(floats.matches("3.14", "3.15")).isFalse();
    }

    @Test
    void float_comparesNonNumbersExactly() {
        assertThat(floats.matches("yes 1.0", "yes 1.0001")).isTrue();
        assertThat(floats.matches("yes 1.0", "no 1.0")).isFalse();
        assertThat(floats.matches("1.0", "one")).isFalse();
    }

    @Test
    void float_doesNotTreatNanOrInfinityOrSuffixesAsNumbers() {
        assertThat(floats.matches("1.0", "NaN")).isFalse();
        assertThat(floats.matches("1.0", "Infinity")).isFalse();
        assertThat(floats.matches("1.0", "1.0d")).isFalse();
    }

    @Test
    void float_doesNotMatchOverflowedNumbers() {
        assertThat(floats.matches("1e999", "1e999")).isFalse();
    }

    @Test
    void float_rejectsDifferentTokenCounts() {
        assertThat(floats.matches("1 2", "1")).isFalse();
    }

    @Test
    void float_rejectsANegativeOrNanTolerance() {
        assertThatThrownBy(() -> new FloatComparator(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FloatComparator(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparatorType_requiresToleranceForFloat() {
        assertThatThrownBy(() -> ComparatorType.FLOAT.create(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ComparatorType.FLOAT.create(0.5)).isInstanceOf(FloatComparator.class);
        assertThat(ComparatorType.EXACT.create(null)).isInstanceOf(ExactComparator.class);
        assertThat(ComparatorType.TOKEN.create(null)).isInstanceOf(TokenComparator.class);
    }
}
