# Reconciliation Engine

A deterministic transaction-reconciliation service for competing account-state reports. It accepts events in any arrival order, resolves the account’s current state, stores an audit decision, and can replay historical events without changing stored data.

## Architecture

```text
POST /events -> validate -> idempotency -> persist -> ordered resolver -> account + audit -> response
POST /replay -> load/filter -> ordered resolver -> response (read-only)
```

Java 17, Spring Boot 4, Spring Web MVC, Spring Data JPA, PostgreSQL, JUnit, H2, Maven, and Docker are used. No Kafka, Redis, microservices, Kubernetes, external APIs, or ML decisioning are used.

## Data model and rules

- `events` stores every accepted event. `event_id` is its primary key and idempotency key. Indexed by `(account_id, timestamp)` for efficient queries.
- `accounts` stores one account currency and the current resolved balance. Optimistic locking (`@Version`) prevents lost updates under concurrency.
- `audit_records` stores each accepted event’s reconciliation decision; considered event IDs are JSON text (LOB). Audit includes `policyVersion`, `previousResolvedEvent`, `decisionReason`, and `replayRunId` for traceability.
- Conflict grouping: events are partitioned into groups where adjacent events are no more than one hour apart. Each group is resolved deterministically; final state is the winner of the most-recent group. See code for the explicit policy.
- An account’s first event establishes its currency; a different later currency is rejected with 400. There is no conversion.
- Balance is the resolved event amount, not the sum of historical events. Replay is read-only and returns a deterministic state hash.

## API

`POST /events` accepts:

```json
{"eventId":"E001","timestamp":"2026-08-15T09:00:00Z","accountId":"ACC001","amount":150.00,"currency":"USD","source":"bank"}
```

It returns HTTP 200 and the accepted event plus resolved event, method, considered IDs, and final balance. Invalid input and currency mismatch return 400; duplicate IDs return 409.

`POST /replay` accepts `{"accountId":"ACC001","until":"2026-08-15T12:00:00Z"}`. `until` is optional and inclusive.

## Running and testing

Set `db_username` and `db_password` for the existing local PostgreSQL configuration, then run:

```bash
./mvnw spring-boot:run
./mvnw test
docker compose up --build
```

Tests use H2 and do not require PostgreSQL. Fixture scenarios used by the tests are under `fixtures/`.
