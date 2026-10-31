package dev.judge.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.judge.api.JudgeApplication;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * Real programs against the real sandbox. Each one attacks the host in a specific way, and each
 * asserts the verdict it should get. Needs a running Docker daemon and the runtime images; with
 * either missing, these fail rather than skip, because a skipped sandbox test proves nothing.
 */
@SpringBootTest(
        classes = JudgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = IntegrationTest.ADMIN_KEY_PROPERTY)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdversarialIT extends IntegrationTest {

    private static final int TIME_LIMIT_MS = 3000;
    private static final int MEMORY_LIMIT_MB = 256;

    private UUID problem;

    @BeforeAll
    void createProblem() throws Exception {
        problem = createSumProblem(TIME_LIMIT_MS, MEMORY_LIMIT_MB);
    }

    @AfterAll
    void leavesNoContainersBehind() throws Exception {
        assertThat(docker("ps", "--all", "--quiet", "--filter", "label=judge.sandbox=true")).isBlank();
    }

    private static String java(String body) {
        return """
                import java.io.*;
                import java.net.*;
                import java.nio.file.*;
                import java.util.*;

                public class Main {
                    public static void main(String[] args) throws Exception {
                %s
                    }
                }
                """.formatted(body);
    }

    private JsonNode runJava(String body) throws Exception {
        return judge(problem, "JAVA", java(body));
    }

    private JsonNode runPython(String source) throws Exception {
        return judge(problem, "PYTHON", source);
    }

    private static void assertVerdict(JsonNode submission, String verdict) {
        assertThat(submission.get("verdict").asString()).as(submission.toString()).isEqualTo(verdict);
    }

    private void assertServiceAlive() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    private static String docker(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return output.toString(StandardCharsets.UTF_8);
    }

    // --- a correct program must still pass, or every test below proves nothing

    @Test
    void correctJava_isAccepted() throws Exception {
        JsonNode done = runJava("""
                Scanner in = new Scanner(System.in);
                System.out.println(in.nextInt() + in.nextInt());
                """);

        assertVerdict(done, "ACCEPTED");
        assertThat(done.get("results")).hasSize(2);
        JsonNode first = done.get("results").get(0);
        assertThat(first.get("stdoutPreview").asString()).isEqualTo("3\n");
        assertThat(first.get("runtimeMs").asLong()).isPositive();
        assertThat(first.get("peakMemoryKb").asLong()).isPositive();
    }

    @Test
    void correctPython_isAccepted() throws Exception {
        JsonNode done = runPython("a, b = map(int, input().split())\nprint(a + b)\n");

        assertVerdict(done, "ACCEPTED");
        assertThat(done.get("results").get(0).get("peakMemoryKb").asLong()).isPositive();
    }

    @Test
    void wrongAnswer_isReported() throws Exception {
        assertVerdict(runPython("print(4)\n"), "WRONG_ANSWER");
    }

    // --- infinite loop

    @Test
    void infiniteLoop_isKilled() throws Exception {
        assertVerdict(runJava("while (true) { }"), "TIME_LIMIT_EXCEEDED");
    }

    @Test
    void infiniteLoopInPython_isKilled() throws Exception {
        assertVerdict(runPython("while True:\n    pass\n"), "TIME_LIMIT_EXCEEDED");
    }

    @Test
    void sleepingForever_usesNoCpuAndIsStillKilled() throws Exception {
        assertVerdict(runPython("import time\ntime.sleep(3600)\n"), "TIME_LIMIT_EXCEEDED");
    }

    @Test
    void ignoringTheCpuSignal_doesNotHelp() throws Exception {
        assertVerdict(
                runPython("import signal\nsignal.signal(signal.SIGXCPU, signal.SIG_IGN)\nwhile True:\n    pass\n"),
                "TIME_LIMIT_EXCEEDED");
    }

    @Test
    void ignoringEverySignalWhileSleeping_doesNotHelp() throws Exception {
        assertVerdict(
                runPython("""
                        import signal, time
                        for s in range(1, 32):
                            try:
                                signal.signal(s, signal.SIG_IGN)
                            except (OSError, ValueError):
                                pass
                        time.sleep(3600)
                        """),
                "TIME_LIMIT_EXCEEDED");
    }

    // --- fork bomb

    @Test
    void forkBomb_isContained() throws Exception {
        JsonNode done = runJava("""
                List<Process> children = new ArrayList<>();
                while (true) {
                    children.add(new ProcessBuilder("sleep", "100").start());
                }
                """);

        assertVerdict(done, "RUNTIME_ERROR");
        assertServiceAlive();
    }

    @Test
    void forkBombInPython_isContained() throws Exception {
        JsonNode done = runPython("import os\nwhile True:\n    os.fork()\n");

        assertThat(done.get("verdict").asString()).isIn("RUNTIME_ERROR", "TIME_LIMIT_EXCEEDED");
        assertServiceAlive();
    }

    private static final String SLEEPING_THREAD = """
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(100_000);
                } catch (InterruptedException e) {
                }
            });
            """;

    @Test
    void threadBomb_runsOutTheClock() throws Exception {
        // Main dies at the pid limit, but its sleeping non-daemon threads keep the JVM alive.
        assertVerdict(
                runJava("while (true) {\n" + SLEEPING_THREAD + "thread.start();\n}"),
                "TIME_LIMIT_EXCEEDED");
    }

    @Test
    void hittingThePidLimit_isARuntimeErrorAndNotAMemoryVerdict() throws Exception {
        // The JVM reports this as an OutOfMemoryError, though the memory limit was never involved.
        assertVerdict(
                runJava("while (true) {\n" + SLEEPING_THREAD + "thread.setDaemon(true);\nthread.start();\n}"),
                "RUNTIME_ERROR");
    }

    @Test
    void jvmWarnings_doNotCorruptACorrectAnswer() throws Exception {
        // Hits the pid limit, which makes the JVM log warnings, then answers correctly anyway.
        JsonNode done = runJava("""
                try {
                    while (true) {
                %s
                        thread.setDaemon(true);
                        thread.start();
                    }
                } catch (OutOfMemoryError e) {
                }
                Scanner in = new Scanner(System.in);
                System.out.println(in.nextInt() + in.nextInt());
                System.exit(0);
                """.formatted(SLEEPING_THREAD));

        assertVerdict(done, "ACCEPTED");
    }

    // --- memory

    @Test
    void memoryHog_isCapped() throws Exception {
        assertVerdict(runJava("byte[] huge = new byte[1 << 30];\nSystem.out.println(huge.length);"), "MEMORY_LIMIT_EXCEEDED");
    }

    @Test
    void catchingOutOfMemory_doesNotHelp() throws Exception {
        assertVerdict(
                runJava("""
                        try {
                            byte[] huge = new byte[1 << 30];
                        } catch (OutOfMemoryError e) {
                            System.out.println(3);
                        }
                        """),
                "MEMORY_LIMIT_EXCEEDED");
    }

    @Test
    void memoryHogInPython_isKilledByTheCgroup() throws Exception {
        // Written rather than only allocated, because an untouched allocation never reaches the cgroup.
        assertVerdict(runPython("data = b'x' * (1 << 30)\nprint(len(data))\n"), "MEMORY_LIMIT_EXCEEDED");
    }

    @Test
    void offHeapMemoryHog_isKilledByTheCgroup() throws Exception {
        // Native memory sits outside the Java heap, so only the container's limit can stop this.
        JsonNode done = runJava("""
                java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
                while (true) {
                    long address = unsafe.allocateMemory(16 * 1024 * 1024);
                    unsafe.setMemory(address, 16 * 1024 * 1024, (byte) 1);
                }
                """);

        assertVerdict(done, "MEMORY_LIMIT_EXCEEDED");
    }

    // --- network

    @Test
    void networkCall_fails() throws Exception {
        assertVerdict(runJava("new Socket(\"1.1.1.1\", 80);"), "RUNTIME_ERROR");
    }

    @Test
    void networkCallInPython_fails() throws Exception {
        assertVerdict(
                runPython("import socket\nsocket.create_connection(('1.1.1.1', 80), timeout=1)\n"),
                "RUNTIME_ERROR");
    }

    @Test
    void containerHasNoNetworkInterfaceButLoopback() throws Exception {
        JsonNode done = runPython("import os\nprint(sorted(os.listdir('/sys/class/net')))\n");

        assertThat(done.get("results").get(0).get("stdoutPreview").asString()).isEqualTo("['lo']\n");
    }

    // --- filesystem

    @Test
    void fileWriteOutsideTmp_fails() throws Exception {
        assertVerdict(runJava("Files.writeString(Path.of(\"/etc/owned\"), \"x\");"), "RUNTIME_ERROR");
    }

    @Test
    void writingToTheWorkspace_fails() throws Exception {
        assertVerdict(runPython("open('/work/Main.class', 'w').write('x')\n"), "RUNTIME_ERROR");
    }

    @Test
    void fillingTheDisk_isStopped() throws Exception {
        assertVerdict(
                runPython("""
                        with open('/tmp/fill', 'wb') as f:
                            while True:
                                f.write(b'x' * (1 << 20))
                                f.flush()
                        """),
                "RUNTIME_ERROR");
        assertServiceAlive();
    }

    @Test
    void executingFromTmp_fails() throws Exception {
        assertVerdict(
                runPython("""
                        import os, shutil, subprocess
                        shutil.copy('/bin/sh', '/tmp/sh')
                        os.chmod('/tmp/sh', 0o755)
                        subprocess.run(['/tmp/sh', '-c', 'exit 0'], check=True)
                        """),
                "RUNTIME_ERROR");
    }

    // --- output

    @Test
    void hugeStdout_isTruncated() throws Exception {
        JsonNode done = runJava("""
                byte[] line = new byte[1 << 16];
                while (true) {
                    System.out.write(line);
                }
                """);

        assertVerdict(done, "OUTPUT_LIMIT_EXCEEDED");
        assertThat(done.toString().length()).isLessThan(16 * 1024);
        assertServiceAlive();
    }

    @Test
    void hugeStderr_isAlsoCapped() throws Exception {
        assertVerdict(
                runPython("""
                        import sys
                        while True:
                            sys.stderr.write('x' * 65536)
                        """),
                "OUTPUT_LIMIT_EXCEEDED");
        assertServiceAlive();
    }

    @Test
    void nulBytesAndBadUtf8InOutput_areAWrongAnswerAndNotAnInternalError() throws Exception {
        JsonNode done = runPython("""
                import sys
                sys.stdout.buffer.write(b'\\x00\\xff\\xfe\\xed\\xa0\\x80 3\\n')
                """);

        assertVerdict(done, "WRONG_ANSWER");
    }

    // --- privileges

    @Test
    void programRunsAsNobody() throws Exception {
        JsonNode done = runPython("import os\nprint(os.getuid(), os.getgid())\n");

        assertThat(done.get("results").get(0).get("stdoutPreview").asString()).isEqualTo("65534 65534\n");
    }

    @Test
    void programHasNoCapabilities() throws Exception {
        JsonNode done = runPython("""
                for line in open('/proc/self/status'):
                    if line.startswith(('CapEff', 'NoNewPrivs')):
                        print(line.split()[1])
                """);

        assertThat(done.get("results").get(0).get("stdoutPreview").asString()).isEqualTo("0000000000000000\n1\n");
    }

    // --- exits

    @Test
    void systemExitZero_isNotAccepted() throws Exception {
        assertVerdict(runJava("System.exit(0);"), "WRONG_ANSWER");
    }

    @Test
    void nonZeroExit_isARuntimeError() throws Exception {
        assertVerdict(runPython("print(3)\nraise SystemExit(7)\n"), "RUNTIME_ERROR");
    }

    @Test
    void killingItself_isARuntimeError() throws Exception {
        assertVerdict(runPython("import os, signal\nos.kill(os.getpid(), signal.SIGKILL)\n"), "RUNTIME_ERROR");
    }

    @Test
    void uncaughtException_isARuntimeError() throws Exception {
        assertVerdict(runJava("throw new RuntimeException(\"boom\");"), "RUNTIME_ERROR");
    }

    // --- compile

    @Test
    void compileError_isReportedCleanly() throws Exception {
        JsonNode done = judge(problem, "JAVA", "public class Main { this is not java }");

        assertVerdict(done, "COMPILE_ERROR");
        assertThat(done.get("compileOutput").asString()).contains("error");
        assertThat(done.get("results")).isEmpty();
    }

    // --- hidden tests

    @Test
    void hiddenInput_neverAppearsInAResponse() throws Exception {
        JsonNode done = runPython("import sys\nsys.stdout.write(sys.stdin.read())\n");

        assertThat(done.toString()).doesNotContain("40 2");
    }

    @Test
    void hiddenExpectedOutput_neverAppearsInAResponse() throws Exception {
        // Passes the visible test and fails the hidden one, which is where a leak would show.
        JsonNode done = runPython("print(3)\n");

        assertVerdict(done, "WRONG_ANSWER");
        JsonNode hidden = done.get("results").get(1);
        assertThat(hidden.get("stdoutPreview").isNull()).isTrue();
        assertThat(hidden.propertyNames()).containsExactlyInAnyOrder("index", "verdict", "runtimeMs", "peakMemoryKb", "stdoutPreview");
    }
}
