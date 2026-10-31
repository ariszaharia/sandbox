# Judge Sandbox

[![ci](https://github.com/ariszaharia/sandbox/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ariszaharia/sandbox/actions/workflows/ci.yml)

A Spring Boot service that runs untrusted source code against test cases in a disposable container and returns a verdict.

Running code written by someone who wants to hurt you is the whole problem. The web layer is the easy part. Below is each way a submission can attack the host, what stops it, and the test that proves it does. Every one of these tests submits a real program to the real sandbox and asserts the verdict, on every push, in CI.

## Threat model

| Attack | Control | Result | Test in `AdversarialIT` |
|---|---|---|---|
| Infinite loop | Wall-clock kill **from the host**, plus `RLIMIT_CPU` | `TLE` | `infiniteLoop_isKilled` |
| Blocking or ignoring signals to outlive the limit | `docker kill` sends SIGKILL, which cannot be caught | `TLE` | `ignoringEverySignalWhileSleeping_doesNotHelp` |
| Sleeping, which uses no CPU | The wall clock, not the CPU limit | `TLE` | `sleepingForever_usesNoCpuAndIsStillKilled` |
| Fork bomb | `--pids-limit=64` | `RE`, service healthy | `forkBomb_isContained` |
| Memory exhaustion | `--memory` with `--memory-swap` equal, or OOM never fires | `MLE` | `memoryHog_isCapped` |
| Native memory, outside the JVM heap | The same cgroup limit | `MLE` | `offHeapMemoryHog_isKilledByTheCgroup` |
| Catching `OutOfMemoryError` to carry on | `-XX:+ExitOnOutOfMemoryError` | `MLE` | `catchingOutOfMemory_doesNotHelp` |
| Network callback, data exfiltration | `--network=none`; only loopback exists | `RE` | `networkCall_fails` |
| Filling the disk | `--read-only`, 64 MB tmpfs, 8 MB `fsize` ulimit | `RE` | `fillingTheDisk_isStopped` |
| Writing outside `/tmp` | `--read-only`; workspace mounted read-only at run time | `RE` | `fileWriteOutsideTmp_fails` |
| Running a dropped binary | `/tmp` is `noexec` | `RE` | `executingFromTmp_fails` |
| Multi-gigabyte stdout or stderr | Stream reader with a hard byte cap, kill on breach | `OLE`, service healthy | `hugeStdout_isTruncated` |
| Output bytes that break storage (NUL, bad UTF-8) | Sanitised before storing | `WA`, not an internal error | `nulBytesAndBadUtf8InOutput_...` |
| Privilege escalation | `--cap-drop=ALL`, `no-new-privileges`, uid 65534 | Capabilities are zero | `programHasNoCapabilities` |
| Reading hidden test data by printing stdin | Output of hidden tests is never stored | Index and verdict only | `hiddenInput_neverAppearsInAResponse` |

Two rules are easy to get wrong:

- **The timeout is enforced from outside.** A timer inside the container can be defeated by the code it is timing.
- **Output is capped while it is read, not after.** `readAllBytes()` on a program printing in a loop runs *this service* out of memory, not the program.

The reverse also holds. `INTERNAL_ERROR` is a separate verdict, so when the daemon is unreachable a correct solution is not accused of a runtime error (`DaemonDownIT`).

## What this does not solve

- **Docker is not a hard security boundary.** A kernel exploit reaches the host, because containers share its kernel. There is no gVisor or Kata layer here. Run this on an isolated VM, not next to anything that matters.
- **The service itself is a high-value target.** It can talk to the Docker daemon, which is equivalent to root on that host.
- **Hidden tests can be probed.** A program can branch on its input and choose its verdict or its runtime, leaking hidden data slowly through those signals. Stopping at the first failed test limits this but does not end it.
- **There are no users, so no per-user limits.** One client can fill the queue for everyone, and submission ids are the only protection for results.
- **One instance only.** The queue lives in memory. A restart fails queued and running submissions rather than retrying them, and a second instance would wrongly sweep the first one's work.
- **No TLS, and `/actuator/prometheus` is open.** Both belong to a reverse proxy, which is not included. The admin key is a single shared secret.
- **SSE connections are not capped** beyond Tomcat's own limit.
- **Time limits include runtime startup.** Even a trivial program uses about 150 ms of its limit, in Java or Python. A program that loads more of its runtime uses more, so very tight limits are not fair across languages.

## Measured performance

`loadtest/load.mjs`, open-loop: submissions arrive at a fixed rate whether or not earlier ones finished. Each is a one-test Python program, for 30 seconds per stage, with 2 workers and a queue of 64.

| Offered/s | Accepted | Rejected (429) | Failed | Completed/s | e2e p50 | e2e p99 | `POST` p99 | Max queue |
|---|---|---|---|---|---|---|---|---|
| 1 | 30 | 0 | 0 | 1.01 | 789 ms | 1217 ms | 25 ms | 0 |
| 2 | 60 | 0 | 0 | 2.00 | 555 ms | 912 ms | 22 ms | 0 |
| 4 | 120 | 0 | 0 | 3.24 | 3750 ms | 7385 ms | 19 ms | 24 |
| 8 | 156 | 84 | 0 | 2.87 | 15941 ms | 25138 ms | 18 ms | 64 |
| 16 | 154 | 326 | 0 | 2.97 | 20774 ms | 22458 ms | 10 ms | 64 |

- **Capacity is about 3 submissions/s** with 2 workers. Under that, a submission takes under a second at the median, and about a second at p99.
- **Saturation degrades instead of collapsing.** At five times capacity there were no failed requests or service errors, throughput held at about 3/s, and `POST` got no slower. The excess was turned away with `429`.
- **The queue sets the worst-case wait.** 64 queued at 3/s is the roughly 21 seconds seen at saturation. A smaller queue rejects sooner and waits less.
- **The container lifecycle dominates, not the program.** Container start averaged 171 ms and the program 149 ms, out of about 670 ms per submission per worker. The rest, about 350 ms, is everything else around them; it is not measured separately, but it includes the `docker` CLI calls to create, inspect and remove.

These are from one run on a developer machine: Docker Desktop on WSL2 under Windows 10, 4 vCPUs and 4 GB, with the load generator on the same machine. Treat them as the shape of the behaviour, not as what a dedicated Linux host would do. Java is slower because it also compiles: a single Java submission with four tests took 3.9 seconds end to end.

## Design

```
client ──POST /api/submissions──> API ──> reserve queue slot ── none free ──> 429
                                   │
                                   └──> submission row (QUEUED) ──> in-memory queue
                                                                        │
                                                                  N worker threads
                                                                        │
                                                          compile once, run once per test,
                                                          one new container each, stop at first failure
                                                                        │
client ──GET /api/submissions/{id}, or SSE on /events──> API <── verdict + per-test results
```

| Module | Holds |
|---|---|
| `judge-runner` | The sandbox: Docker lifecycle, limits, output capture, per-language commands. Plain Java. |
| `judge-core` | Domain model, comparators, verdicts, queue, workers. Plain Java. |
| `judge-persistence` | JDBC repositories and Flyway migrations. |
| `judge-api` | Controllers, validation, SSE, metrics, wiring, the demo page. |
| `judge-it` | Integration and adversarial tests. |

Decisions worth knowing:

- **Asynchronous.** Synchronous execution would tie HTTP timeouts to compiler timeouts and hold a Tomcat thread for the duration.
- **A queue slot is reserved first.** A rejected request never touches the database, and a saved submission always has a place in the queue.
- **Judging stops at the first failure.** Otherwise a program that always times out holds a worker for the time limit multiplied by every test.
- **`create`, `start`, `inspect`, `rm` instead of `docker run --rm`.** `--rm` deletes the container before its exit code and OOM flag can be read.
- **Crash recovery.** At startup, anything not `DONE` becomes `INTERNAL_ERROR`, and orphaned containers and workspaces are removed. This also ran for real: killing the service with a program mid-run and restarting it settled the row and removed the still-running container.

### Output comparison

Set per problem:

| Comparator | Behaviour |
|---|---|
| `EXACT` (default) | Trailing whitespace on each line and trailing blank lines are ignored. Leading whitespace and interior blank lines are significant. |
| `TOKEN` | Compares whitespace-separated tokens. |
| `FLOAT` | Tokens, with numbers equal within an absolute `tolerance`. `NaN` and `Infinity` are not numbers. |

## API

| Method | Path | Behaviour |
|---|---|---|
| `POST` | `/api/submissions` | `202` and an id; `429` when saturated; `400`, `404`, `411` or `413` for bad requests |
| `GET` | `/api/submissions/{id}` | Status, verdict, per-test results |
| `GET` | `/api/submissions/{id}/events` | SSE stream of status transitions, closing at `DONE` |
| `GET` | `/api/problems` | List |
| `GET` | `/api/problems/{id}` | Statement and visible samples |
| `POST` | `/api/problems` | Create. Needs `X-Admin-Key`; refuses everyone if no key is configured |
| `GET` | `/actuator/health`, `/actuator/prometheus` | Ops |

Metrics: `judge.submissions.total{verdict}`, `judge.queue.depth`, `judge.queue.rejected.total`, `judge.execution.duration{language}`, `judge.container.startup.duration`.

## Running it

Needs Java 21 and Docker.

```sh
sh runtimes/build.sh        # containers use --pull=never, so the images must exist first
docker compose up -d        # Postgres on port 5433

./mvnw -DskipTests package
JUDGE_ADMIN_KEY=change-me java -jar judge-api/target/judge-api-0.1.0-SNAPSHOT-exec.jar --judge.demo-data=true
```

Then open <http://localhost:8080> for the demo page, or:

```sh
PROBLEM=$(curl -s localhost:8080/api/problems | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)
curl -i localhost:8080/api/submissions -H 'Content-Type: application/json' \
  -d "{\"problemId\":\"$PROBLEM\",\"language\":\"PYTHON\",\"sourceCode\":\"a,b=map(int,input().split())\\nprint(a+b)\\n\"}"
```

Settings are in `judge-api/src/main/resources/application.yml`. Set `judge.sandbox.mode=fake` to run without a Docker daemon.

Tests:

```sh
./mvnw test     # unit tests, no Docker needed
./mvnw verify   # everything; needs Docker and the runtime images, and fails rather than skips without them
```

Load test, with the service running:

```sh
JUDGE_ADMIN_KEY=change-me node loadtest/load.mjs http://localhost:8080 30 1,2,4,8,16
```

## Adding a language

One class implementing `LanguageSpec`, one line in the `Language` enum, one Dockerfile, and a line in `runtimes/build.sh`. The orchestrator does not change.
