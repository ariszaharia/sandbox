# Build from the runtimes/ directory:
#   docker build -t judge-runtime-python:3.12 -f python/Dockerfile .
FROM python:3.12-slim-bookworm

COPY judge-exec.sh /judge/exec.sh
RUN chmod 0555 /judge/exec.sh

WORKDIR /work
USER 65534:65534
ENTRYPOINT ["/judge/exec.sh"]
