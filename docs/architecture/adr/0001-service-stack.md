# ADR-0001: Service Stack — Java 21, Spring Boot 4.1, PostgreSQL, Kafka

- **Status:** Accepted
- **Date:** 2026-09-30

## Context

AstraWMS services must integrate with SAP (RFC/BAPI, IDoc) and Oracle (REST, open interfaces), sustain the NFR throughput and latency targets (§E.2), and be maintainable by enterprise integration teams. The architecture (§F) prescribes coarse-grained services per bounded context, a transactional outbox, and Kafka.

## Decision

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 (LTS) |
| Framework | Spring Boot 4.1 (Spring Framework 7, Jackson 3, JUnit 6) |
| Persistence | PostgreSQL 16 via Spring `JdbcClient`. **No JPA**: inventory needs explicit locking (`FOR UPDATE`, atomic conditional updates) and exact SQL. |
| Migrations | Flyway, run as the schema-owner role; the service connects as a separate non-owner role (ADR-0003) |
| Messaging | Apache Kafka; String/JSON payloads in the canonical envelope (ISD-00 §2) |
| Build | Maven multi-module monorepo; `scripts/mvn-docker.sh` builds without a local JDK |
| Tests | Testcontainers (real Postgres and Kafka). No in-memory database substitutes. |

## Consequences

- SAP JCo is available for the future SAP adapter (IF-* over RFC).
- Hand-written SQL requires reviews for injection safety. All queries use named parameters; dynamic SQL only appends fixed column names.
- Boot 4 / Jackson 3 are recent. Two platform conventions compensate for changed defaults, and both are implemented in `astra-common`:
  - the platform auto-configuration runs after Kafka auto-configuration;
  - `FAIL_ON_NULL_FOR_PRIMITIVES` is disabled.
