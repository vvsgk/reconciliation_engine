# Reconciliation Engine

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

(Include your preferred license here)
