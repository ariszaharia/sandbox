package dev.judge.runner.limits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reads a stream on its own thread with a hard cap. {@code readAllBytes()} on a program printing
 * in a loop would run out of memory in this service, not in the sandbox.
 *
 * <p>Past the cap it keeps draining without storing anything: stopping would leave the docker
 * client blocked on a full pipe, and it would never exit after the container was killed.
 */
public final class OutputCapture {

    private static final int BUFFER_BYTES = 8192;

    private final InputStream stream;
    private final int retainBytes;
    private final long breachAfterBytes;
    private final Runnable onBreach;
    private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
    private final Thread reader;

    private OutputCapture(InputStream stream, int retainBytes, long breachAfterBytes, Runnable onBreach) {
        this.stream = stream;
        this.retainBytes = retainBytes;
        this.breachAfterBytes = breachAfterBytes;
        this.onBreach = onBreach;
        this.reader = Thread.ofPlatform().daemon().name("output-capture").unstarted(this::drain);
    }

    /** @param onBreach called at most once, from the reader thread, so it must not block */
    public static OutputCapture start(InputStream stream, int retainBytes, long breachAfterBytes, Runnable onBreach) {
        OutputCapture capture = new OutputCapture(stream, retainBytes, breachAfterBytes, onBreach);
        capture.reader.start();
        return capture;
    }

    private void drain() {
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        boolean breached = false;
        try {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                retain(buffer, read);
                if (!breached && total > breachAfterBytes) {
                    breached = true;
                    onBreach.run();
                }
            }
        } catch (IOException e) {
            // The client went away. Whatever arrived so far is all there will be.
        }
    }

    private synchronized void retain(byte[] buffer, int length) {
        int room = retainBytes - retained.size();
        if (room > 0) {
            retained.write(buffer, 0, Math.min(room, length));
        }
    }

    /** @return false if the stream is still open after the timeout */
    public boolean await(Duration timeout) throws InterruptedException {
        return reader.join(timeout);
    }

    public synchronized String text() {
        return retained.toString(StandardCharsets.UTF_8);
    }
}
