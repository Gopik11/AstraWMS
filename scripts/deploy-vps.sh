#!/usr/bin/env bash
# Deploys AstraWMS to a shared VPS as an isolated stack in /opt/astrawms (deploy/vps/docker-compose.yml).
# Images are built here and streamed to the server, so the server's CPUs are not used for compiling.
#
#   scripts/deploy-vps.sh                      # defaults below
#   VPS=root@host PUBLIC_PORT=8088 scripts/deploy-vps.sh
set -euo pipefail

VPS="${VPS:-root@145.223.90.247}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/astrawms_vps}"
PUBLIC_PORT="${PUBLIC_PORT:-8088}"
PUBLIC_URL="${PUBLIC_URL:-http://${VPS#*@}:${PUBLIC_PORT}}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="$(git -C "$ROOT" rev-parse --short HEAD)"
SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes "$VPS")
MODULES=(master-data-service:services inventory-service:services inbound-service:services task-service:services
         outbound-service:services sap-adapter:adapters)

[[ -z "$(git -C "$ROOT" status --porcelain)" ]] || { echo "Commit your changes first: images are tagged with the commit" >&2; exit 1; }

echo "== Building images astrawms/*:$TAG"
images=()
for m in "${MODULES[@]}"; do
  name="${m%%:*}"; dir="${m##*:}"
  docker build -q -t "astrawms/$name:$TAG" --build-arg MODULE="$name" --build-arg MODULE_DIR="$dir" \
    -f "$ROOT/deploy/Dockerfile" "$ROOT" >/dev/null
  images+=("astrawms/$name:$TAG")
done
docker build -q -t "astrawms/gateway:$TAG" -f "$ROOT/deploy/gateway/Dockerfile" "$ROOT" >/dev/null
images+=("astrawms/gateway:$TAG")

echo "== Copying images to $VPS (layers shared between services are sent once)"
docker save "${images[@]}" | gzip -1 | "${SSH[@]}" 'gunzip | docker load -q'

echo "== Copying deployment files to /opt/astrawms"
tar -C "$ROOT/deploy" -cf - vps/docker-compose.yml vps/postgres-init vps/remote-deploy.sh \
    keycloak/astrawms-realm.json \
  | "${SSH[@]}" 'set -e; mkdir -p /opt/astrawms && chmod 711 /opt/astrawms && cd /opt/astrawms
      t=$(mktemp -d); tar -xf - -C "$t"
      cp "$t/vps/docker-compose.yml" "$t/vps/remote-deploy.sh" .
      rm -rf postgres-init && cp -r "$t/vps/postgres-init" postgres-init && chmod 755 postgres-init postgres-init/*.sh
      mkdir -p gateway keycloak-template && chmod 700 keycloak-template
      cp "$t/keycloak/astrawms-realm.json" keycloak-template/
      chmod +x remote-deploy.sh; rm -rf "$t"'

echo "== Starting on the server"
"${SSH[@]}" "/opt/astrawms/remote-deploy.sh '$TAG' '$PUBLIC_URL' '$PUBLIC_PORT'"

echo
echo "AstraWMS $TAG is running at $PUBLIC_URL"
echo "Keycloak admin console: ssh -i $SSH_KEY -L 8181:127.0.0.1:8181 $VPS  then open http://localhost:8181"
