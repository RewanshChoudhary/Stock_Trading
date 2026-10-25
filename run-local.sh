#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$ROOT_DIR/threadpool-web/target/threadpool-web-1.0.0.jar"

cd "$ROOT_DIR"
mvn -pl threadpool-web -am package

java_opts=()
if [[ -n "${JAVA_OPTS:-}" ]]; then
    read -r -a java_opts <<< "$JAVA_OPTS"
fi

exec java "${java_opts[@]}" -jar "$JAR_PATH" "$@"
