#!/usr/bin/env bash
# Runs ON the VPS, from /opt/astrawms, called by scripts/deploy-vps.sh after the images and files are copied.
#   remote-deploy.sh <image-tag> <public-url> <public-port>
# Generates the secrets once (.env, root-only), renders the Keycloak realm with them, and starts the stack in
# stages so that the shared host never sees all JVMs starting at once. Touches nothing outside /opt/astrawms.
set -euo pipefail
cd /opt/astrawms
TAG="$1"; PUBLIC_URL="$2"; PUBLIC_PORT="$3"

say() { printf '\n== %s\n' "$*"; }

if [[ ! -f .env ]]; then
  say "Generating secrets (.env, first deployment only)"
  umask 077
  {
    for v in PG_SUPERUSER_PASSWORD DB_OWNER_PASSWORD DB_APP_PASSWORD KC_DB_PASSWORD KC_ADMIN_PASSWORD \
             INBOUND_CLIENT_SECRET TASK_CLIENT_SECRET OUTBOUND_CLIENT_SECRET PROVISIONER_CLIENT_SECRET; do
      echo "$v=$(openssl rand -hex 24)"
    done
  } > .env
fi
chmod 600 .env
# Deployment parameters (not secrets) are refreshed on every run.
sed -i '/^IMAGE_TAG=/d;/^PUBLIC_URL=/d;/^PUBLIC_PORT=/d' .env
printf 'IMAGE_TAG=%s\nPUBLIC_URL=%s\nPUBLIC_PORT=%s\n' "$TAG" "$PUBLIC_URL" "$PUBLIC_PORT" >> .env
set -a; source .env; set +a

say "Rendering the Keycloak realm with this environment's client secrets"
mkdir -p keycloak && chmod 755 keycloak
python3 - <<'PY'
import json, os
realm = json.load(open("keycloak-template/astrawms-realm.json", encoding="utf-8"))
secrets = {"astra-inbound": "INBOUND_CLIENT_SECRET", "astra-task": "TASK_CLIENT_SECRET",
           "astra-outbound": "OUTBOUND_CLIENT_SECRET", "astra-provisioner": "PROVISIONER_CLIENT_SECRET"}
for c in realm["clients"]:
    if c["clientId"] in secrets:
        c["secret"] = os.environ[secrets[c["clientId"]]]
    if c["clientId"] == "astra-web":
        c["redirectUris"] = [os.environ["PUBLIC_URL"] + "/*"]
        c["webOrigins"] = [os.environ["PUBLIC_URL"]]
realm["displayName"] = "AstraWMS (test environment)"
json.dump(realm, open("keycloak/astrawms-realm.json", "w", encoding="utf-8"), indent=1)
PY
chown 1000:1000 keycloak/astrawms-realm.json && chmod 600 keycloak/astrawms-realm.json

dc() { docker compose --env-file .env "$@"; }
wait_healthy() { # wait_healthy <service> <seconds>
  local id; id="$(dc ps -q "$1")"
  for _ in $(seq 1 "$2"); do
    [[ "$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null)" == "healthy" ]] && return 0
    sleep 1
  done
  echo "FAILED: $1 not healthy after $2 s" >&2; dc logs --tail 40 "$1" >&2; exit 1
}
wait_up() { # wait_up <service> <seconds>: readiness through the gateway
  for _ in $(seq 1 "$2"); do
    curl -sf "http://127.0.0.1:${PUBLIC_PORT}/health/$1" | grep -q '"UP"' && return 0
    sleep 2
  done
  echo "FAILED: $1 not ready" >&2; dc logs --tail 60 "$1" >&2; exit 1
}

say "Pulling infrastructure images"
dc pull -q postgres kafka keycloak gateway

say "Stage 1: Postgres and Kafka"
dc up -d postgres kafka
wait_healthy postgres 120

say "Stage 2: Keycloak (first start builds its runtime; can take a few minutes on this host)"
dc up -d keycloak
wait_healthy keycloak 600

# The admin console signs in through the master realm, which the gateway does not expose: give that realm the
# tunnel address (ssh -L 8181:127.0.0.1:8181) as its frontend URL. The astrawms realm keeps the public URL.
docker exec -e KCPW="$KC_ADMIN_PASSWORD" "$(dc ps -q keycloak)" sh -c '
  /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin     --password "$KCPW" --config /tmp/kcadm.cfg >/dev/null 2>&1 &&
  /opt/keycloak/bin/kcadm.sh update realms/master -s attributes.frontendUrl=http://localhost:8181 --config /tmp/kcadm.cfg
  rc=$?; rm -f /tmp/kcadm.cfg; exit $rc' || { echo "FAILED: could not set the master realm frontend URL" >&2; exit 1; }

say "Stage 3: gateway, then the services one at a time"
dc up -d gateway
for svc in master-data-service inventory-service inbound-service task-service outbound-service sap-adapter; do
  dc up -d --no-deps "$svc"
  wait_up "$svc" 120
  echo "$svc ready"
done

say "Removing AstraWMS images of earlier deployments"
docker images --format '{{.Repository}}:{{.Tag}}' | grep '^astrawms/' | grep -v ":${TAG}$" | xargs -r docker rmi -f >/dev/null 2>&1 || true

say "AstraWMS containers"
dc ps --format 'table {{.Service}}\t{{.Status}}'
docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' | grep '^astrawms-'
