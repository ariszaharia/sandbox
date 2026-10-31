# Judge Sandbox — Skeleton

A Spring Boot service that runs untrusted source code against test cases inside a disposable container and returns a verdict.

The interesting engineering is not the web layer. It is: **how do you run code written by someone who wants to hurt you, bound its resources, and still answer in under a second?** Everything below is organised around that.

---

## 1. Architecture

```
client ──POST /submissions──> API ──> submissions table (status=QUEUED)
                                │
                                └──> in-process queue (bounded)
                                         │
                                   N worker threads
                                         │
                              ┌──────────┴──────────┐
                              │   Runner (Docker)   │
                              │  compile → run ×T   │
                              └──────────┬──────────┘
                                         │
                            verdict + per-test results
                                         │
                                  submissions table
client ──GET /submissions/{id}──> API ──┘
```

Submission is **asynchronous**. `POST` returns `202 Accepted` with an id; the client polls `GET`, or subscribes over SSE. Synchronous execution would couple HTTP timeouts to compiler timeouts, and a slow `javac` would hold a Tomcat thread hostage.

The queue is bounded on purpose. When it is full, the API returns `429` rather than accepting work it cannot do. That single decision is the difference between a service that degrades and one that falls over.

---

## 2. Module layout

```
judge/
├── judge-api/                    # controllers, DTOs, validation, error handling
├── judge-core/                   # domain model, verdict logic, orchestration
│   ├── submission/
│   ├── problem/
│   └── verdict/
├── judge-runner/                 # the sandbox: Docker lifecycle, limits, I/O capture
│   ├── docker/
│   ├── limits/
│   └── language/                 # per-language compile & run commands
├── judge-persistence/            # entities, repositories, Flyway migrations
└── judge-it/                     # Testcontainers integration + adversarial tests
```

Keep `judge-runner` free of Spring annotations except at its edges. It should be testable by calling a plain Java method with a `RunRequest` and getting a `RunResult` back.

---

## 3. Domain model

```java
public record Problem(
        UUID id,
        String slug,
        String title,
        Duration timeLimit,          // per test case, wall clock
        DataSize memoryLimit,
        List<TestCase> testCases) {}

public record TestCase(
        int index,
        String input,
        String expectedOutput,
        boolean hidden) {}

public record Submission(
        UUID id,
        UUID problemId,
        Language language,
        String sourceCode,
        Status status,              // QUEUED, COMPILING, RUNNING, DONE
        Verdict verdict,            // null until DONE
        List<TestResult> results,
        Instant createdAt) {}

public record TestResult(
        int index,
        Verdict verdict,
        Duration runtime,
        DataSize peakMemory,
        String stdoutPreview) {}    // truncated, never the full dump

public enum Verdict {
    ACCEPTED,
    WRONG_ANSWER,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    OUTPUT_LIMIT_EXCEEDED,
    RUNTIME_ERROR,
    COMPILE_ERROR,
    INTERNAL_ERROR              // your bug, not theirs — never silently an RE
}
```

`INTERNAL_ERROR` as a distinct verdict matters. If the Docker daemon hiccups, a correct solution must not be reported as a runtime error. Conflating the two is how judges lose trust.

---

## 4. The runner interface

```java
public interface Sandbox {
    CompileResult compile(CompileRequest request);
    RunResult run(RunRequest request);
}

public record RunRequest(
        Path workdir,            // host dir, bind-mounted read-only
        Language language,
        String stdin,
        Duration wallClockLimit,
        Duration cpuLimit,
        DataSize memoryLimit,
        DataSize outputLimit,
        int processLimit) {}

public record RunResult(
        int exitCode,
        String stdout,
        String stderr,
        Duration wallTime,
        DataSize peakMemory,
        TerminationReason reason) {}   // NORMAL, TIMEOUT, OOM, OUTPUT_CAP, SIGNAL
```

Two implementations:

- `DockerSandbox` — the real one.
- `FakeSandbox` — returns canned results, so core logic is unit-testable without a daemon.

---

## 5. Isolation — the part that earns the interview

Every container is created, used for **one** execution, and destroyed. No reuse, no persistence between test cases.

```
docker run
  --rm
  --network=none                   # no exfiltration, no package downloads
  --read-only                      # root filesystem immutable
  --tmpfs /tmp:rw,noexec,nosuid,size=64m
  --memory=256m --memory-swap=256m # swap must equal memory or OOM never fires
  --cpus=1.0
  --pids-limit=64                  # fork bombs die quietly
  --ulimit nofile=64:64
  --ulimit fsize=8388608           # 8 MB file write cap
  --cap-drop=ALL
  --security-opt=no-new-privileges
  --user 65534:65534               # nobody; never root
  -v /run/sub-<id>:/work:ro
  judge-runtime-java:21
```

Defence layers, each covering a different failure:

| Threat | Control |
|---|---|
| Network callback / data exfiltration | `--network=none` |
| Fork bomb | `--pids-limit` |
| Memory exhaustion | `--memory` with `--memory-swap` equal |
| Infinite loop | wall-clock kill from the host, **not** a timer inside the container |
| Disk fill | `--read-only` + tmpfs size + `fsize` ulimit |
| Multi-gigabyte stdout | stream reader with a hard byte cap, kill on breach |
| Privilege escalation | `--cap-drop=ALL`, `no-new-privileges`, non-root uid |
| Container escape | run workers on a dedicated host/VM; treat Docker as a boundary, not a guarantee |

Two rules that are easy to get wrong:

1. **Enforce the timeout from outside.** A `@Scheduled` check or an in-container `timeout` command can be defeated by code that blocks signals. Kill the container from the host process.
2. **Cap output while reading, not after.** `process.getInputStream().readAllBytes()` on a program printing in a loop will OOM *your service*, not theirs.

---

## 6. Compile and run, per language

```java
public interface LanguageSpec {
    String image();                                  // judge-runtime-java:21
    List<String> compileCommand(Path source);        // empty for interpreted
    List<String> runCommand(Path artifact);
    Duration compileTimeout();
}
```

Start with Java and Python. Adding C++ later should mean one new class and one new Dockerfile, nothing else. If adding a language touches the orchestrator, the abstraction is wrong.

Compile once per submission; run once per test case. Reuse the compiled artifact by bind-mounting the same read-only workdir.

---

## 7. API

| Method | Path | Behaviour |
|---|---|---|
| `POST` | `/api/submissions` | `202` + id, or `429` when the queue is saturated |
| `GET` | `/api/submissions/{id}` | status, verdict, per-test results |
| `GET` | `/api/submissions/{id}/events` | SSE stream of status transitions |
| `GET` | `/api/problems` | list |
| `POST` | `/api/problems` | create (admin) |
| `GET` | `/actuator/health`, `/actuator/prometheus` | ops |

Validate before you queue: source size cap (say 64 KB), language in the supported set, problem exists. Rejecting cheaply at the edge keeps the expensive path clean.

Hidden test cases must never appear in a response. Return the index and verdict, not the input.

---

## 8. Persistence

```sql
-- V1__init.sql
CREATE TABLE problem (
    id            UUID PRIMARY KEY,
    slug          TEXT NOT NULL UNIQUE,
    title         TEXT NOT NULL,
    time_limit_ms INT  NOT NULL,
    memory_limit_mb INT NOT NULL
);

CREATE TABLE test_case (
    id          UUID PRIMARY KEY,
    problem_id  UUID NOT NULL REFERENCES problem(id) ON DELETE CASCADE,
    idx         INT  NOT NULL,
    input       TEXT NOT NULL,
    expected    TEXT NOT NULL,
    hidden      BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (problem_id, idx)
);

CREATE TABLE submission (
    id          UUID PRIMARY KEY,
    problem_id  UUID NOT NULL REFERENCES problem(id),
    language    TEXT NOT NULL,
    source_code TEXT NOT NULL,
    status      TEXT NOT NULL,
    verdict     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE TABLE test_result (
    submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
    idx           INT  NOT NULL,
    verdict       TEXT NOT NULL,
    runtime_ms    INT,
    peak_memory_kb INT,
    PRIMARY KEY (submission_id, idx)
);

CREATE INDEX ON submission (status, created_at);
```

Flyway from day one. A submission left in `RUNNING` because the service died needs a startup sweep that marks it `INTERNAL_ERROR` — write that reconciler, it is the kind of detail reviewers notice.

---

## 9. Output comparison

More subtle than it looks. Decide and document:

- Trailing whitespace on each line: ignored
- Trailing newline at end of file: ignored
- Interior blank lines: significant
- Floating point: compare with tolerance when the problem declares one

Put this behind a `Comparator` interface (`ExactComparator`, `TokenComparator`, `FloatComparator`) chosen per problem. Hardcoding `expected.equals(actual)` produces a judge nobody believes.

---

## 10. Tests that prove the sandbox works

This is the test class that makes the project. Each is a real program you submit, with an asserted verdict:

```java
@Test void infiniteLoop_isKilled()              // while(true){}            → TLE
@Test void forkBomb_isContained()               // ProcessBuilder loop      → RE, host unaffected
@Test void memoryHog_isCapped()                 // new byte[1<<30]          → MLE
@Test void networkCall_fails()                  // new Socket("1.1.1.1",80) → RE
@Test void fileWriteOutsideTmp_fails()          // write to /etc            → RE
@Test void hugeStdout_isTruncated()             // print in a loop          → OLE, service alive
@Test void systemExitZero_isNotAccepted()       // exit(0) with no output   → WA
@Test void compileError_isReportedCleanly()     // syntax error             → CE with message
@Test void containerDown_yieldsInternalError()  // daemon unreachable       → INTERNAL_ERROR
```

Run them with Testcontainers in CI. A green badge on *these* tests says more than a coverage percentage.

---

## 11. Observability

Micrometer counters and timers worth exporting:

- `judge.submissions.total{verdict}` — verdict distribution
- `judge.queue.depth` and `judge.queue.rejected.total`
- `judge.execution.duration` — histogram, by language
- `judge.container.startup.duration` — usually your dominant latency; measure before optimising

Then load-test and put the numbers in the README: sustained submissions/sec, p99 end-to-end latency, queue depth at saturation, the point where it starts rejecting. Concrete figures turn a project into evidence.

---

## 12. Build order

1. `FakeSandbox` + API + persistence. End-to-end flow with no Docker at all.
2. `DockerSandbox` for Java only, no limits. Make it work.
3. Add limits one at a time, writing the adversarial test *before* the control it proves.
4. Queue, backpressure, `429`.
5. Second language. If this is painful, fix the abstraction.
6. Metrics, load test, README with numbers.
7. SSE, a minimal frontend if you want a demo link.

Steps 1 to 4 are a complete, honest project. Everything after is polish.

---

## 13. README framing

Open with the threat model, not the feature list. Something like: *running untrusted code is the whole problem; here are the eight ways a submission can attack the host and what stops each one.* Then the limits table, then the measured numbers.

Also state what you did **not** solve: Docker is not a hard security boundary against a kernel exploit, so this runs on an isolated VM rather than alongside anything that matters. Naming your own limits reads as judgement, not weakness, and it is the sentence that separates you from a repo that claims to be secure.
