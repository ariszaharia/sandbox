// Open-loop load test: submissions arrive at a fixed rate whether or not earlier ones have finished,
// which is what exposes saturation. A closed loop would slow down with the service and hide it.
//
//   node loadtest/load.mjs [baseUrl] [secondsPerStage] [rate,rate,...]
//
// Needs the service running with JUDGE_ADMIN_KEY set to the same value in this process's environment.

const base = process.argv[2] ?? 'http://localhost:8080';
const stageSeconds = Number(process.argv[3] ?? 30);
const rates = (process.argv[4] ?? '1,2,4,8,16').split(',').map(Number);
const adminKey = process.env.JUDGE_ADMIN_KEY;
if (!adminKey) throw new Error('Set JUDGE_ADMIN_KEY');

const SOURCE = 'a, b = map(int, input().split())\nprint(a + b)\n';
const POLL_MS = 100;
const DRAIN_TIMEOUT_MS = 300_000;

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const percentile = (sorted, p) => sorted.length === 0 ? NaN : sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];

async function createProblem() {
  const response = await fetch(`${base}/api/problems`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Admin-Key': adminKey },
    body: JSON.stringify({
      slug: `load-${Date.now()}`,
      title: 'Load test',
      timeLimitMs: 2000,
      memoryLimitMb: 256,
      testCases: [{ input: '1 2\n', expected: '3\n', hidden: false }],
    }),
  });
  if (response.status !== 201) throw new Error(`Could not create problem: ${response.status} ${await response.text()}`);
  return (await response.json()).id;
}

async function queueDepth() {
  const text = await (await fetch(`${base}/actuator/prometheus`)).text();
  const line = text.split('\n').find(l => l.startsWith('judge_queue_depth'));
  return line ? Number(line.split(' ').at(-1)) : NaN;
}

async function one(problemId, stage) {
  const started = performance.now();
  let response;
  try {
    response = await fetch(`${base}/api/submissions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ problemId, language: 'PYTHON', sourceCode: SOURCE }),
    });
  } catch {
    stage.failed++;
    return;
  }
  stage.acceptLatencies.push(performance.now() - started);
  if (response.status === 429) {
    await response.text();
    stage.rejected++;
    return;
  }
  if (response.status !== 202) {
    await response.text();
    stage.failed++;
    return;
  }
  stage.accepted++;
  const { id } = await response.json();

  const deadline = performance.now() + DRAIN_TIMEOUT_MS;
  while (performance.now() < deadline) {
    await sleep(POLL_MS);
    const submission = await (await fetch(`${base}/api/submissions/${id}`)).json();
    if (submission.status === 'DONE') {
      stage.latencies.push(performance.now() - started);
      stage.verdicts[submission.verdict] = (stage.verdicts[submission.verdict] ?? 0) + 1;
      stage.lastFinished = performance.now();
      return;
    }
  }
  stage.failed++;
}

async function runStage(problemId, rate) {
  const stage = { rate, accepted: 0, rejected: 0, failed: 0, latencies: [], acceptLatencies: [], verdicts: {}, maxDepth: 0, lastFinished: 0 };
  const intervalMs = 1000 / rate;
  const total = Math.round(rate * stageSeconds);
  const started = performance.now();
  const inFlight = [];

  const sampler = setInterval(async () => {
    const depth = await queueDepth().catch(() => 0);
    stage.maxDepth = Math.max(stage.maxDepth, depth);
  }, 500);

  for (let i = 0; i < total; i++) {
    const wait = started + i * intervalMs - performance.now();
    if (wait > 0) await sleep(wait);
    inFlight.push(one(problemId, stage));
  }
  await Promise.all(inFlight);
  clearInterval(sampler);

  const sorted = [...stage.latencies].sort((a, b) => a - b);
  const accepts = [...stage.acceptLatencies].sort((a, b) => a - b);
  return {
    offeredPerSec: rate,
    accepted: stage.accepted,
    rejected: stage.rejected,
    failed: stage.failed,
    completedPerSec: Number((stage.latencies.length / ((stage.lastFinished - started) / 1000)).toFixed(2)),
    e2eP50Ms: Math.round(percentile(sorted, 0.5)),
    e2eP99Ms: Math.round(percentile(sorted, 0.99)),
    postP99Ms: Math.round(percentile(accepts, 0.99)),
    maxQueueDepth: stage.maxDepth,
    verdicts: stage.verdicts,
  };
}

const problemId = await createProblem();
const results = [];
for (const rate of rates) {
  const result = await runStage(problemId, rate);
  results.push(result);
  console.log(JSON.stringify(result));
  while ((await queueDepth()) > 0) await sleep(500);
  await sleep(2000);
}

console.log('\n| Offered/s | Accepted | Rejected (429) | Failed | Completed/s | e2e p50 | e2e p99 | POST p99 | Max queue |');
console.log('|---|---|---|---|---|---|---|---|---|');
for (const r of results) {
  console.log(`| ${r.offeredPerSec} | ${r.accepted} | ${r.rejected} | ${r.failed} | ${r.completedPerSec} | ${r.e2eP50Ms} ms | ${r.e2eP99Ms} ms | ${r.postP99Ms} ms | ${r.maxQueueDepth} |`);
}
