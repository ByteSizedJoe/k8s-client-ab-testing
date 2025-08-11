#!/usr/bin/env bash
set -euo pipefail

JVM_OPTS=(
  -Xms512m -Xmx512m \
  -XX:ActiveProcessorCount=4 \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:NativeMemoryTracking=summary
)

ARGS=(
  --namespace ab-harness \
  --out out \
  --repeats 3 \
  --warmup 15 \
  --duration 120 \
  --threads 6 \
  --trustCerts true \
  --reqTimeout 30 \
  --connTimeout 10 \
  --wsTimeout 600 \
  --maxRequests 128 \
  --maxRequestsPerHost 64 \
  --tls TLSv1.2,TLSv1.3 \
  --jfr 20
)

build_and_run() {
  local profile=$1
  echo "Building with profile $profile..."
  mvn -q -P "$profile" -DskipTests package
  local JAR
  JAR=$(ls -1 target/k8s-client-ab-harness-*-SNAPSHOT.jar 2>/dev/null || ls -1 target/k8s-client-ab-harness-*.jar | head -n1)
  echo "Running $profile..."
  JAVA_HOME=${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which javac))))}
  java "${JVM_OPTS[@]}" -Dtransport.id=$profile -jar "$JAR" "${ARGS[@]}" --label "$profile"
}

build_and_run vertx-4
build_and_run vertx-5
