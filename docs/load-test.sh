#!/usr/bin/env bash
#
# WalletEngine load driver: parallel wallet transactions over gRPC.
#
# Unlike a loop of grpcurl calls (one process and one connection per call), the driver
# reuses a single channel and runs one virtual thread per wallet, so the numbers describe
# the service, not the shell.
#
# Requires: JDK 21 and the app running (mvn spring-boot:run, or docker compose --profile app up).
#
# Examples:
#   docs/load-test.sh                                            # 50 wallets x 200 mixed ops
#   docs/load-test.sh --wallets 100 --ops 500 --mode credit
#   docs/load-test.sh --mode read --wallets 50 --ops 2000
#
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -q -DskipTests test-compile \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    -Dmdep.outputFile=target/load-classpath.txt

exec java -cp "target/test-classes:target/classes:$(cat target/load-classpath.txt)" \
    com.github.walletengine.perf.LoadTester "$@"
