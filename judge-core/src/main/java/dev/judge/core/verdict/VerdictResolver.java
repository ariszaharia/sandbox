package dev.judge.core.verdict;

import dev.judge.runner.RunResult;

/** Turns what happened to one test run into a verdict. Output is only compared after a clean exit. */
public final class VerdictResolver {

    private VerdictResolver() {
    }

    public static Verdict resolve(RunResult result, String expected, OutputComparator comparator) {
        return switch (result.reason()) {
            case TIMEOUT -> Verdict.TIME_LIMIT_EXCEEDED;
            case OOM -> Verdict.MEMORY_LIMIT_EXCEEDED;
            case OUTPUT_CAP -> Verdict.OUTPUT_LIMIT_EXCEEDED;
            case SIGNAL -> Verdict.RUNTIME_ERROR;
            case NORMAL -> {
                if (result.exitCode() != 0) {
                    yield Verdict.RUNTIME_ERROR;
                }
                yield comparator.matches(expected, result.stdout()) ? Verdict.ACCEPTED : Verdict.WRONG_ANSWER;
            }
        };
    }
}
