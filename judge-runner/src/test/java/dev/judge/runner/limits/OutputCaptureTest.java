package dev.judge.runner.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutputCaptureTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void keepsOutputUnderTheCap() throws Exception {
        AtomicInteger breaches = new AtomicInteger();

        OutputCapture capture = OutputCapture.start(stream(100), 1000, 1000, breaches::incrementAndGet);

        assertThat(capture.await(WAIT)).isTrue();
        assertThat(capture.text()).hasSize(100);
        assertThat(breaches).hasValue(0);
    }

    @Test
    void outputExactlyAtTheCapIsNotABreach() throws Exception {
        AtomicInteger breaches = new AtomicInteger();

        OutputCapture capture = OutputCapture.start(stream(1000), 1000, 1000, breaches::incrementAndGet);

        assertThat(capture.await(WAIT)).isTrue();
        assertThat(breaches).hasValue(0);
    }

    @Test
    void firesOnceAndKeepsDrainingPastTheCapWithoutStoring() throws Exception {
        AtomicInteger breaches = new AtomicInteger();
        InputStream huge = stream(2_000_000);

        OutputCapture capture = OutputCapture.start(huge, 1000, 1000, breaches::incrementAndGet);

        assertThat(capture.await(WAIT)).isTrue();
        assertThat(capture.text()).hasSize(1000);
        assertThat(breaches).hasValue(1);
        assertThat(huge.available()).as("client must not be left blocked on a full pipe").isZero();
    }

    @Test
    void retainingLessThanTheCapStillCountsEverythingTowardsIt() throws Exception {
        AtomicInteger breaches = new AtomicInteger();

        OutputCapture capture = OutputCapture.start(stream(500), 10, 100, breaches::incrementAndGet);

        assertThat(capture.await(WAIT)).isTrue();
        assertThat(capture.text()).hasSize(10);
        assertThat(breaches).hasValue(1);
    }

    private static InputStream stream(int bytes) {
        return new ByteArrayInputStream("x".repeat(bytes).getBytes());
    }
}
