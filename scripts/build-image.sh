#!/bin/sh
# Builds the app jar once (Maven) and packages it into the local image referenced by
# compose. The jar is never rebuilt inside Docker: one artifact for local, compose, ECR.
set -eu

cd "$(dirname "$0")/.."

./mvnw -q -DskipTests package
docker build -f src/main/docker/Dockerfile -t walletengine:local .
