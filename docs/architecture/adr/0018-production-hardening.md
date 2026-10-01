# ADR-0018: Production Hardening: HTTPS, Kafka Authentication, Dead-Letter Replay and Monitoring

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

The VPS test environment served the UI over plain HTTP on port 8088. Browsers refuse PKCE sign-in there, since it needs a secure context. Four other gaps blocked operating the system without a developer on hand:
- Any container on the Docker network could read or write every Kafka topic.
- Dead-lettered messages could only be inspected with Kafka tools.
- Nothing measured outbox backlog, consumer lag or dead letters.

## Decision

1. **HTTPS at the host nginx.** A new vhost (`deploy/vps/host-nginx-astrawms.conf`) serves `https://145-223-90-247.sslip.io` with a Let's Encrypt certificate.
   - sslip.io resolves the name to the VPS address, so no domain purchase is needed.
   - The host's certbot timer renews the certificate.
   - The gateway port is bound to 127.0.0.1 (`PUBLIC_BIND`).
   - Keycloak's public hostname and the web client's redirect URIs follow `PUBLIC_URL`.
   - Only this vhost was added; the other applications' vhosts are untouched.
2. **Kafka SASL/PLAIN with one account per service.**
   - The broker's JAAS file lists the accounts. On the VPS it is rendered from generated secrets in `.env`; any secret missing from `.env` is generated on the next deployment.
   - Services get `SPRING_KAFKA_SECURITY_PROTOCOL` and `SPRING_KAFKA_PROPERTIES_SASL_*` from the environment, so no code changed.
   - Testcontainers stays PLAINTEXT. The local compose uses SASL with dev passwords so the smoke tests exercise it; its `localhost:29092` listener stays open for debugging tools.
   - TLS on the broker is not needed while it is reachable only on the private network.
   - Per-account ACLs are the next step: the accounts already exist for them.
3. **Dead-letter operations in every service** (astra-common `DeadLetters`, `/api/v1/ops/dlq`, SOLUTION_ADMIN; the gateway exposes it as `/api/v1/ops/<service>/...`).
   - **Listing** shows only the records this service's consumer groups dead-lettered (`kafka_dlt-original-consumer-group`), and only for the caller's tenant. A DLQ topic is shared by every consumer of the source topic and holds every tenant's data. Unreadable records have no tenant and are only counted.
   - **Replay** sends the record back to its original topic with `astra-replay-for-group`. A `RecordInterceptor` (`ReplayRouting`) makes every other consumer group skip it, and the inbox makes a duplicate harmless anyway.
   - Each record can be replayed once. The replay is recorded in `dlq_replay` (who and when), a migration in each service. It is not a shared Flyway location, so services keep their own version sequences.
4. **Metrics and alerts.**
   - Every service exposes `/actuator/prometheus`. It is unauthenticated but reachable only on the private network, because the gateway never routes `/actuator`.
   - New platform metrics:
     - `astra_outbox_pending`
     - `astra_outbox_oldest_pending_seconds`
     - `astra_kafka_dead_lettered_total`
   - Prometheus (`deploy/monitoring`) scrapes the services and evaluates alert rules, each naming the action to take:

     | Alert | Fires when |
     |---|---|
     | ServiceDown | a service stops answering |
     | OutboxBacklog | the oldest unpublished outbox message keeps ageing |
     | MessagesDeadLettered | a message is dead-lettered |
     | ConsumerLagGrowing | a consumer falls behind |
     | HeapPressure | heap use stays above 90% |
     | HttpServerErrors | a service keeps answering with 5xx |

   - Locally Prometheus runs under the `monitoring` profile. On the VPS it is capped at 160 MB, keeps 7 days or 500 MB, and is reachable on 127.0.0.1:9090 (SSH tunnel).
   - The web UI's **Operations** page shows per-service health, outbox backlog and dead letters with a Replay button.
5. **Resilient image upload.** `deploy-vps.sh` sends the image archive in checksummed 50 MB chunks, each retried on its own, because a single long SSH stream was reset on a slow link.

## Consequences

- Browsers can sign in on the VPS. Port 8088 is no longer reachable from the internet.
- An administrator can recover from a dead letter without Kafka tooling.
- `MessagingIT` covers it: a broken message is listed for its tenant only, the listing is refused for non-admins, a replay is accepted once, and the replay reaches only the failing group.
- All nine smoke tests pass on the local stack with Kafka authentication on.
- Not built yet:
  - Kafka ACLs per account.
  - Alert delivery (Alertmanager to e-mail or chat).
  - Dashboards (Grafana).
  - Log shipping.
  - Database backups for the VPS. This is the most important gap before real data.
