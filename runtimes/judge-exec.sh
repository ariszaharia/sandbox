#!/bin/sh
# PID 1 of every sandbox container. Runs the given command as a child, then reports the
# cgroup's peak memory on stderr, because that figure is gone once the container exits.
#
# The submission can forge this line, which only misreports its own memory use. Verdicts
# never read it: MLE comes from the daemon's OOM flag and the language runtime's exit.

# Files javac writes are owned by the sandbox uid; the host still has to delete them.
umask 000

"$@"
code=$?

peak=$(cat /sys/fs/cgroup/memory.peak 2>/dev/null \
    || cat /sys/fs/cgroup/memory/memory.max_usage_in_bytes 2>/dev/null)
printf '\n@@judge-peak-memory=%s\n' "${peak:-0}" >&2

exit "$code"
