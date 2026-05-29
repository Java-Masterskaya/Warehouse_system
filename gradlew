#!/usr/bin/env bash

cd "$(dirname "$0")" || exit

GRADLE_USER_HOME="$(pwd)/gradle" JAVA_OPTS="-Xmx2048m" "$(dirname "$0")/gradlew" "$@"