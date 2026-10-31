package dev.judge.core.verdict;

import static org.assertj.core.api.Assertions.assertThat;

import dev.judge.runner.FakeSandbox;
import dev.judge.runner.TerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VerdictResolverTest {

    private final OutputComparator exact = new ExactComparator();

    @ParameterizedTest
    @CsvSource({
        "TIMEOUT, TIME_LIMIT_EXCEEDED",
        "OOM, MEMORY_LIMIT_EXCEEDED",
        "OUTPUT_CAP, OUTPUT_LIMIT_EXCEEDED",
        "SIGNAL, RUNTIME_ERROR",
    })
    void aLimitDecidesTheVerdictEvenWhenTheOutputIsRight(TerminationReason reason, Verdict verdict) {
        assertThat(VerdictResolver.resolve(FakeSandbox.result(0, "3\n", reason), "3\n", exact)).isEqualTo(verdict);
    }

    @Test
    void nonZeroExitIsARuntimeErrorEvenWhenTheOutputIsRight() {
        assertThat(VerdictResolver.resolve(FakeSandbox.result(1, "3\n", TerminationReason.NORMAL), "3\n", exact))
                .isEqualTo(Verdict.RUNTIME_ERROR);
    }

    @Test
    void cleanExitComparesOutput() {
        assertThat(VerdictResolver.resolve(FakeSandbox.printed("3\n"), "3\n", exact)).isEqualTo(Verdict.ACCEPTED);
        assertThat(VerdictResolver.resolve(FakeSandbox.printed("4\n"), "3\n", exact)).isEqualTo(Verdict.WRONG_ANSWER);
    }

    @Test
    void exitingZeroWithNoOutputIsAWrongAnswer() {
        assertThat(VerdictResolver.resolve(FakeSandbox.printed(""), "3\n", exact)).isEqualTo(Verdict.WRONG_ANSWER);
    }
}
