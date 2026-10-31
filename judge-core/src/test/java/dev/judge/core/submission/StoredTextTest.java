package dev.judge.core.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StoredTextTest {

    @Test
    void leavesShortTextAlone() {
        assertThat(StoredText.of("hello", 10)).isEqualTo("hello");
    }

    @Test
    void truncates() {
        assertThat(StoredText.of("hello", 3)).isEqualTo("hel");
    }

    @Test
    void removesNulBeforeMeasuring() {
        assertThat(StoredText.of("a\0\0\0b", 2)).isEqualTo("ab");
    }

    @Test
    void doesNotCutASurrogatePairInHalf() {
        String stored = StoredText.of("a😀b", 2);

        assertThat(stored).isEqualTo("a");
        assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(stored)).isTrue();
    }
}
