#!/usr/bin/env bash
# Runs Maven inside the official Temurin 21 image, so no local JDK/Maven install is needed.
# Testcontainers talks to the host Docker engine through the mounted socket.
#
#   scripts/mvn-docker.sh -B verify
#   scripts/mvn-docker.sh -B -pl services/inventory-service -am test
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${ASTRA_MAVEN_IMAGE:-maven:3.9-eclipse-temurin-21}"

# Git Bash on Windows: stop MSYS from rewriting container paths, and pass a Windows-style host path.
if command -v cygpath >/dev/null 2>&1; then
  export MSYS_NO_PATHCONV=1
  HOST_ROOT="$(cygpath -m "$ROOT")"
else
  HOST_ROOT="$ROOT"
fi

exec docker run --rm \
  -v "$HOST_ROOT":/workspace -w /workspace \
  -v astrawms-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  --add-host=host.docker.internal:host-gateway \
  "$IMAGE" mvn "$@"
