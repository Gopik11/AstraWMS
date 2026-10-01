#!/usr/bin/env bash
# Deploys AstraWMS to a shared VPS as an isolated stack in /opt/astrawms (deploy/vps/docker-compose.yml).
# Images are built here and streamed to the server, so the server's CPUs are not used for compiling.
#
#   scripts/deploy-vps.sh                      # defaults below
#   VPS=root@host PUBLIC_URL=https://name PUBLIC_PORT=8088 scripts/deploy-vps.sh
#
# The gateway listens on 127.0.0.1:PUBLIC_PORT and the host nginx serves PUBLIC_URL over TLS in front of it
# (deploy/vps/host-nginx-astrawms.conf). PUBLIC_BIND=0.0.0.0 exposes the port directly instead: plain HTTP, where
# browsers cannot sign in (PKCE needs a secure context).
set -euo pipefail

VPS="${VPS:-root@145.223.90.247}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/astrawms_vps}"
PUBLIC_PORT="${PUBLIC_PORT:-8088}"
PUBLIC_URL="${PUBLIC_URL:-https://astrawms.cloud}"
PUBLIC_BIND="${PUBLIC_BIND:-127.0.0.1}"
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
# A single long stream over SSH can be reset on slow links, so the archive goes in checksummed 50 MB chunks, each
# retried on its own, and is loaded only once it arrived complete. Chunks already on the server are not resent.
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
docker save "${images[@]}" | gzip -1 > "$WORK/images.tar.gz"
( cd "$WORK" && split -b 50m -d -a 3 images.tar.gz part. && sha256sum images.tar.gz > images.sha256 )
echo "   $(du -h "$WORK/images.tar.gz" | cut -f1) in $(ls "$WORK"/part.* | wc -l) chunks"
INBOX="/opt/astrawms/upload-$TAG"
"${SSH[@]}" "find /opt/astrawms -maxdepth 1 -name 'upload-*' ! -name 'upload-$TAG' -exec rm -rf {} + ; mkdir -p '$INBOX' && chmod 700 '$INBOX'"
for part in "$WORK"/part.*; do
  name="$(basename "$part")"; size="$(stat -c %s "$part")"
  for attempt in 1 2 3 4 5; do
    [[ "$("${SSH[@]}" "stat -c %s '$INBOX/$name' 2>/dev/null || echo 0")" == "$size" ]] && break
    "${SSH[@]}" "cat > '$INBOX/$name.tmp' && mv '$INBOX/$name.tmp' '$INBOX/$name'" < "$part" && break
    echo "   $name: attempt $attempt failed, retrying" >&2; sleep 5
  done
done
"${SSH[@]}" "cat '$INBOX'/part.* > '$INBOX/images.tar.gz'"
"${SSH[@]}" "cd '$INBOX' && sha256sum -c --quiet" < "$WORK/images.sha256" \
  || { echo "Image archive arrived corrupted; run the deployment again" >&2; exit 1; }
"${SSH[@]}" "gunzip -c '$INBOX/images.tar.gz' | docker load -q && rm -rf '$INBOX'"

echo "== Copying deployment files to /opt/astrawms"
tar -C "$ROOT/deploy" -cf - vps/docker-compose.yml vps/postgres-init vps/remote-deploy.sh \
    keycloak/astrawms-realm.json monitoring \
  | "${SSH[@]}" 'set -e; mkdir -p /opt/astrawms && chmod 711 /opt/astrawms && cd /opt/astrawms
      t=$(mktemp -d); tar -xf - -C "$t"
      cp "$t/vps/docker-compose.yml" "$t/vps/remote-deploy.sh" .
      rm -rf postgres-init && cp -r "$t/vps/postgres-init" postgres-init && chmod 755 postgres-init postgres-init/*.sh
      mkdir -p gateway keycloak-template && chmod 700 keycloak-template
      cp "$t/keycloak/astrawms-realm.json" keycloak-template/
      rm -rf monitoring && cp -r "$t/monitoring" monitoring && chmod 755 monitoring && chmod 644 monitoring/*
      chmod +x remote-deploy.sh; rm -rf "$t"'

echo "== Starting on the server"
"${SSH[@]}" "/opt/astrawms/remote-deploy.sh '$TAG' '$PUBLIC_URL' '$PUBLIC_PORT' '$PUBLIC_BIND'"

echo
echo "AstraWMS $TAG is running at $PUBLIC_URL"
echo "Keycloak admin console: ssh -i $SSH_KEY -L 8181:127.0.0.1:8181 $VPS  then open http://localhost:8181"
echo "Prometheus (metrics, alerts): ssh -i $SSH_KEY -L 9090:127.0.0.1:9090 $VPS  then open http://localhost:9090"
