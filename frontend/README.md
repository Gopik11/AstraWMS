# AstraWMS web UI

React + TypeScript (Vite). Signs in with Keycloak (authorization code + PKCE, client `astra-web`) and calls the
AstraWMS API on the same origin. See [ADR-0013](../docs/architecture/adr/0013-web-ui.md).

```bash
npm ci
npm run dev      # http://localhost:5173, API proxied to the local gateway (deploy/docker-compose.yml)
npm test         # unit tests (vitest)
npm run build    # production build; the gateway image (deploy/gateway/Dockerfile) bakes it in
```

Runtime settings come from `/config.json` (`public/config.json` in development; mounted by the gateway elsewhere).
