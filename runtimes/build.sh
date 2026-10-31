#!/bin/sh
# Builds the runtime images. Containers are created with --pull=never, so these must exist first.
set -e
cd "$(dirname "$0")"
docker build -t judge-runtime-java:21 -f java/Dockerfile .
docker build -t judge-runtime-python:3.12 -f python/Dockerfile .
