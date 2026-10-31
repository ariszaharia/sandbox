# Build from the runtimes/ directory:
#   docker build -t judge-runtime-java:21 -f java/Dockerfile .
FROM eclipse-temurin:21-jdk-jammy

COPY judge-exec.sh /judge/exec.sh
RUN chmod 0555 /judge/exec.sh

WORKDIR /work
USER 65534:65534
ENTRYPOINT ["/judge/exec.sh"]
