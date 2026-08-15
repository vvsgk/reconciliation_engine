# Reconciliation Engine

[![Java](https://img.shields.io/badge/Java-17-%23007f00)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-blue)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-lightgrey)](./LICENSE)

A deterministic transaction-reconciliation service that accepts out-of-order account-state reports (events), decides the canonical account state per the product rule, stores a human-readable audit for each ingestion, and can replay history to reproduce state and a cryptographic fingerprint.

> **Important:** The current development configuration references PostgreSQL credentials stored in a local/internal environment. **Before running or deploying this project, replace the database URL, username, and password with your own PostgreSQL credentials. Do not use the development credentials.** For deployment, use environment variables or a secrets manager and never commit real credentials to Git.

Table of contents
- Quickstart
- Architecture & Policy (authoritative)
- Data model
- API examples
- Tests & Postgres (Testcontainers)
- Stress harness
- Audit & Replay
- Security: credentials checklist
- Fixtures & troubleshooting
- Contributing

Quickstart (docker + env)
1. Ensure Docker is running locally.
2. Export database credentials (do NOT commit credentials into git):

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reconciliation_test
export SPRING_DATASOURCE_USERNAME=reconciliation_user
export SPRING_DATASOURCE_PASSWORD=REPLACE_WITH_STRONG_PASSWORD
```

3. Run the app:

```bash
./mvnw -DskipTests spring-boot:run
```

4. Run tests (recommended with Testcontainers Postgres):

```bash
./mvnw -DskipTests=false test
```

Architecture & policy (authoritative)
The service implements a deterministic pairwise folding policy (the PRD):
- For a candidate event and the next chronological event:
    - If timestamp difference ≤ 1 hour → the event with the higher amount wins. Tie-breakers: earlier timestamp, then lexicographically smaller eventId.
    - If timestamp difference > 1 hour → the later timestamp wins. Tie-breakers: higher amount, then lexicographically smaller eventId.

This policy is applied in chronological order to produce a single resolved event for an account at any point in time. Replay uses the same algorithm and produces a canonical fingerprint (SHA-256) over policy version + canonical ordered events + result.

Data model (summary)
- events (event_id PK): account_id, timestamp, amount, currency, source, created_at.
- accounts (account_id PK): balance (resolved event amount), currency, updated_at, version (optimistic lock).
- audit_records (reconciliation_id PK): timestamp, account_id, conflicting_events (LOB JSON), resolved_event, resolution_method, final_balance, policy_version, previous_resolved_event, decision_reason, replay_run_id.

Important semantics
- Balance is the resolved event's amount (not a sum of transactions).
- The account's first event sets currency. Any later event with a different currency is rejected with 400 and the entire ingestion (event insert + account change + audit) must be rolled back.
- Event idempotency: event_id is unique—duplicate insertion returns 409.

API examples
1) Ingest an event

```bash
curl -s -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"E001","timestamp":"2026-08-15T09:00:00Z","accountId":"ACC001","amount":150.00,"currency":"USD","source":"bank"}'
```

Success returns 200 and the reconciliation payload:
- resolvedEvent, resolutionMethod, consideredEventIds, finalBalance

2) Replay

```bash
curl -s -X POST http://localhost:8080/replay \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC001","until":"2026-08-15T12:00:00Z"}'
```

Tests & Postgres (Testcontainers recommended)
- Tests exercise concurrency, idempotency, rollback, and deterministic replay. H2 is present only for lightweight experiments but is not a valid substitute for Postgres when testing concurrency/locking/SQL state codes.
- Recommended: run tests with Docker available so Testcontainers can spin up Postgres.
- To switch to a local Postgres for tests, set SPRING_DATASOURCE_URL, USERNAME, PASSWORD before running `./mvnw test`.

Stress harness
- A stress harness scaffold is included under `src/test/java/.../stress`.
- The harness supports workloads: normal, duplicate storm, out-of-order, hot-spot hot-account.
- Large runs (100k–1M events) require a dedicated machine/CI runner and a real Postgres instance. Run small smoke stress locally (10k) before attempting larger runs.

Audit & Replay
- Each successful ingestion creates an audit explaining why a particular event won. `decision_reason` includes the policy name, the compared amounts/timestamps, and tie-break rationale.
- Audits store `previousResolvedEvent` for transitions; this provides a state-change trail.
- Replay generates a canonical representation and stores SHA-256(policyVersion + canonicalEvents + decision) so live processing == replay fingerprint.

Security: credentials checklist (must do before testing)
- Remove any hard-coded credentials from files. If you find an internal/system password in the repo, treat it as compromised and rotate it before use.
- Use environment variables or a secrets manager in CI. Example env vars shown in Quickstart.
- For Postgres in Docker Compose, prefer a generated strong password; do NOT reuse internal passwords.

Docker compose (example)

```yaml
version: '3.8'
services:
  db:
    image: postgres:15
    environment:
      POSTGRES_DB: reconciliation_test
      POSTGRES_USER: reconciliation_user
      POSTGRES_PASSWORD: change_me_strong_password
    ports:
      - '5432:5432'
  app:
    build: .
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/reconciliation_test
      SPRING_DATASOURCE_USERNAME: reconciliation_user
      SPRING_DATASOURCE_PASSWORD: change_me_strong_password
    depends_on:
      - db
```

Fixtures & examples
- Fixtures live in `fixtures/` and include duplicate, out-of-order, within-hour-conflict, >1-hour conflict, timestamp-tie scenarios. Use them as a source of truth for expected behavior.

Troubleshooting
- If concurrency tests fail: ensure Postgres is used, not H2. Increase JVM threads or run stress on a machine with more CPU.
- If you see DataIntegrityViolationException: check whether it is a duplicate-key (translate to 409) vs an unexpected constraint; handle accordingly and inspect DB logs.

Contributing
- Create issues for failing tests, determinism questions, or performance investigations.
- For major changes (policy or data model), include a compatibility test proving old vs new behavior and update the replay fingerprint policy version.

License
- (Add license file) — example: MIT

--
Notes: do NOT commit real passwords. Replace any internal passwords found in the repo and rotate credentials before testing or CI runs.

A deterministic transaction-reconciliation service that accepts events in any arrival order, resolves account state according to a strict policy, stores an auditable decision for every accepted ingestion, and can replay history to reconstruct account state deterministically.

## High-level architecture

POST /events -> validate (schema + currency) -> idempotency check -> persist event -> ordered resolver (PRD policy) -> account upsert + audit -> response

POST /replay -> load events (optional `until`) -> ordered resolver (read-only) -> response + replay fingerprint

Core technologies: Java 17, Spring Boot 4, Spring Web MVC, Spring Data JPA, PostgreSQL (recommended for tests), JUnit 5, Maven, Docker.

## Conflict policy (authoritative PRD rule)

Events are processed in chronological order and folded pairwise. For each comparison between the current candidate and the next event:

- If timestamp difference ≤ 1 hour → higher amount wins. Tie-breakers: earlier timestamp, then lexicographically smaller eventId.
- If timestamp difference > 1 hour → latest timestamp wins. Tie-breakers: higher amount, then lexicographically smaller eventId.

This rule is implemented deterministically so different arrival orders produce the same final state and replay fingerprint.

## Data model

- events: every accepted event (event_id PK, account_id, timestamp, amount, currency, source, created_at). event_id is the idempotency key.
- accounts: stores current resolved balance, currency, updated_at; optimistic locking (version) used. Balance semantics: the resolved event's amount is the account balance (not a running sum).
- audit_records: one audit per accepted ingestion capturing considered event IDs, resolved event, resolution method, final balance, policy version, previousResolvedEvent, decisionReason, and replayRunId.

## Determinism & Replay

Replay constructs a canonical ordered event list (by timestamp then eventId), applies the same pairwise policy, and computes a canonical fingerprint:

canonical = policyVersion + canonicalOrderedEvents + resolvedEventId + resolutionMethod + finalBalance
SHA-256(canonical) is stored as the replay fingerprint. Replay is read-only and should produce the same fingerprint as live processing.

## API

POST /events
- Body: {eventId, timestamp (ISO8601 UTC), accountId, amount (decimal), currency (ISO code), source}
- Responses: 200 OK (event accepted), 400 Bad Request (validation or currency mismatch), 409 Conflict (duplicate event id)

POST /replay
- Body: {accountId, until?}
- Returns: reconciliation result at `until` (inclusive) and replay fingerprint.

## Tests, local dev & CI (recommended)

This project is designed to run its test suite against PostgreSQL. For reproducible local/CI runs use Testcontainers. Steps:

1. Install Docker and ensure it runs locally.
2. Run tests with Testcontainers-managed Postgres (recommended):
   ./mvnw -DskipTests=false test

There is a lightweight H2 config present for quick experiments, but H2 is not equivalent to PostgreSQL for concurrency, locks, or SQL error codes; do not rely on H2 for final verification.

## Configuration & secrets (IMPORTANT)

- Never commit real credentials. This repository may contain example/test credentials or previously used internal passwords. Do NOT use them in any non-isolated environment.
- Before running tests or starting the service, set database credentials securely via environment variables or an external config file. Examples:

  export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reconciliation_test
  export SPRING_DATASOURCE_USERNAME=reconciliation_user
  export SPRING_DATASOURCE_PASSWORD=REPLACE_WITH_STRONG_PASSWORD

- If you see a credential in source (e.g., application-test.properties), replace it immediately with secure values or remove it and rely on Testcontainers or env vars. Treat any "internal" password as compromised and rotate it before testing.

## How to run locally (recommended quickstart)

1. Ensure Docker is running.
2. Run the test Postgres via Testcontainers automatically by running:
   ./mvnw test

3. Run the application (dev):
   ./mvnw spring-boot:run
   or with environment overrides:
   SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reconciliation \
   SPRING_DATASOURCE_USERNAME=... \
   SPRING_DATASOURCE_PASSWORD=... \
   ./mvnw spring-boot:run

4. API examples (curl):
   curl -X POST -H "Content-Type: application/json" -d @event.json http://localhost:8080/events
   curl -X POST -H "Content-Type: application/json" -d '{"accountId":"ACC-REPLAY","until":"2026-08-15T09:30:00Z"}' http://localhost:8080/replay

## Stress testing harness

A stress harness (disabled by default) is included under src/test/java/.../stress. It can generate workloads (10k/100k/500k/1M events) and measure throughput and latency percentiles. Run large stress tests on a machine with sufficient CPU/RAM and a real Postgres instance (not H2 nor ephemeral testcontainers unless you provision resources accordingly).

## Concurrency & idempotency

- Event idempotency: event_id is unique. Duplicate event IDs return 409.
- Account upsert: implemented with upsert/update pattern to reduce contention; account currency is validated inside the same transaction as event persistence and audit to ensure atomic rollback on failure.

## Audit explanations

Audits contain a human-readable `decisionReason` explaining why a winner was chosen (policy name, amounts/timestamps compared, and tie-break rationale). `previousResolvedEvent` captures the prior winner when a new event changes the resolved state.

## Fixtures

Fixtures are in `/fixtures/` and include combined complex scenarios (duplicates, out-of-order, within-hour conflicts, >1-hour conflicts, timestamp ties). Use them in tests or local runs.

## Security checklist before testing or submission

- Remove any hard-coded credentials from config files.
- Rotate any internal/system passwords that were used during development.
- Use env vars or a secrets manager for DB credentials in CI.

## Contributing & contact

- Open issues for failing tests, performance bottlenecks, or determinism questions.
- For large stress runs, prefer running on CI runners or dedicated benchmark machines and share measurements (p50/p95/p99, throughput, 409 rate).

## License