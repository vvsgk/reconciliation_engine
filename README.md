# Reconciliation Engine

> A deterministic transaction-reconciliation service for processing out-of-order account-state events, resolving canonical account state, recording auditable decisions, and reproducing state through deterministic replay.

<p align="center">

**Java 17** · **Spring Boot 4** · **PostgreSQL 17** · **Spring Data JPA** · **JUnit 5** · **Testcontainers**

</p>

---

## Table of Contents

- [Overview](#overview)
- [Important: Database Credentials](#important-database-credentials)
- [Core Features](#core-features)
- [Technology Stack](#technology-stack)
- [Architecture](#architecture)
- [Reconciliation Policy](#reconciliation-policy)
- [Data Model](#data-model)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
  - [1. Start Docker](#1-start-docker)
  - [2. Verify Java and Docker](#2-verify-java-and-docker)
  - [3. Make Maven Wrapper Executable](#3-make-maven-wrapper-executable)
  - [4. Run the Test Suite](#4-run-the-test-suite)
  - [5. Start the Application](#5-start-the-application)
- [Configuration](#configuration)
- [API](#api)
  - [POST /events](#post-events)
  - [POST /replay](#post-replay)
- [Idempotency and Currency Rules](#idempotency-and-currency-rules)
- [Auditability](#auditability)
- [Replay and Deterministic Fingerprinting](#replay-and-deterministic-fingerprinting)
- [Testing](#testing)
- [Testcontainers and PostgreSQL](#testcontainers-and-postgresql)
- [Troubleshooting](#troubleshooting)
- [Fixtures](#fixtures)
- [Stress Testing](#stress-testing)
- [Useful Maven Commands](#useful-maven-commands)
- [Security Checklist](#security-checklist)
- [Project Structure](#project-structure)
- [Development Workflow](#development-workflow)
- [License](#license)

---

## Overview

The Reconciliation Engine accepts account-state events that may arrive out of order and determines the canonical state of each account according to a deterministic reconciliation policy.

The system is designed around four guarantees:

1. **Deterministic reconciliation**  
   The same event history produces the same final state regardless of arrival order.

2. **Transactional integrity**  
   Event persistence, account updates, and audit records are handled atomically.

3. **Idempotency**  
   Repeated delivery of the same event does not create duplicate state.

4. **Replayability**  
   Historical events can be replayed without modifying live state, producing a reproducible cryptographic fingerprint.

> **Balance semantics:** The account balance represents the amount of the currently resolved event. It is **not** the sum of all transaction amounts.

---

# Important: Database Credentials

> **Important:** The current development configuration references PostgreSQL credentials stored in a local/internal environment. **Before running or deploying this project, replace the database URL, username, and password with your own PostgreSQL credentials. Do not use the development credentials.** For deployment, use environment variables or a secrets manager and never commit real credentials to Git.

### Never commit credentials

Do not commit:

- PostgreSQL passwords
- Production credentials
- API keys
- Internal connection strings
- Secrets from local development environments

For local development, use environment variables:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reconciliation_test
export SPRING_DATASOURCE_USERNAME=reconciliation_user
export SPRING_DATASOURCE_PASSWORD='YOUR_STRONG_PASSWORD'
```

For CI/CD and production, use your platform's secret-management system.

If an internal credential has already been exposed, treat it as compromised and rotate it.

---

# Core Features

### Deterministic reconciliation

Events are processed chronologically using a strict pairwise conflict-resolution policy.

### Out-of-order event handling

Events can arrive in any order while still producing the same canonical account state.

### Idempotent ingestion

`eventId` acts as the unique idempotency key.

### Currency validation

The first event establishes an account's currency. A later event using a different currency is rejected and the transaction is rolled back.

### Transactional rollback

A failed ingestion does not leave partial changes across:

- event storage
- account state
- audit records

### Audit trail

Every successful ingestion produces an auditable explanation of how the winning event was selected.

### Deterministic replay

Stored history can be replayed to reconstruct account state without changing live data.

### Cryptographic fingerprint

Replay generates a SHA-256 fingerprint from the canonical reconciliation result.

### Concurrency handling

The test suite exercises duplicate storms and concurrent ingestion for the same account.

---

# Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 4 |
| API | Spring Web MVC |
| Persistence | Spring Data JPA |
| ORM | Hibernate |
| Database | PostgreSQL 17 |
| Testing | JUnit 5 |
| Integration Testing | Testcontainers |
| Build Tool | Maven Wrapper |
| Container Runtime | Docker / Docker Desktop |
| Fingerprinting | SHA-256 |

---

# Architecture

At a high level, event ingestion follows this flow:

```text
                    POST /events
                         │
                         ▼
                Request Validation
                         │
                         ▼
                 Idempotency Check
                         │
                         ▼
                  Currency Check
                         │
                         ▼
                  Persist Event
                         │
                         ▼
             Deterministic Resolver
                         │
                         ▼
                 Account Update
                         │
                         ▼
                  Audit Record
                         │
                         ▼
                     Response
```

Replay follows a separate read-only path:

```text
                    POST /replay
                         │
                         ▼
                  Load Event History
                         │
                         ▼
                Apply Optional "until"
                         │
                         ▼
                 Canonical Ordering
                         │
                         ▼
             Deterministic Resolution
                         │
                         ▼
              Generate SHA-256 Hash
                         │
                         ▼
                     Response
```

---

# Reconciliation Policy

This is the authoritative conflict-resolution policy.

Events are processed in chronological order.

For each candidate event and the next chronological event:

## Timestamp difference ≤ 1 hour

The event with the **higher amount** wins.

Tie-breakers:

1. Earlier timestamp wins.
2. If timestamps are equal, lexicographically smaller `eventId` wins.

## Timestamp difference > 1 hour

The event with the **later timestamp** wins.

Tie-breakers:

1. Higher amount wins.
2. If amounts are equal, lexicographically smaller `eventId` wins.

### Policy summary

```text
Difference <= 1 hour
    ├── Higher amount wins
    ├── Earlier timestamp wins on amount tie
    └── Smaller eventId wins on timestamp tie

Difference > 1 hour
    ├── Later timestamp wins
    ├── Higher amount wins on timestamp tie
    └── Smaller eventId wins on amount tie
```

The policy is deterministic by design.

Therefore:

```text
A → B → C
```

and:

```text
C → A → B
```

must produce the same canonical result when they contain the same event history.

---

# Data Model

## `events`

Stores every accepted event.

```text
event_id
account_id
timestamp
amount
currency
source
created_at
```

`event_id` is the primary key and idempotency key.

---

## `accounts`

Stores the current canonical account state.

```text
account_id
balance
currency
updated_at
version
```

The `balance` is the amount from the resolved event, not a running sum.

---

## `audit_records`

Stores the reconciliation decision for each successful ingestion.

Important information includes:

```text
reconciliation_id
timestamp
account_id
conflicting_events
resolved_event
resolution_method
final_balance
policy_version
previous_resolved_event
decision_reason
replay_run_id
```

---

# Prerequisites

Install the following before running the project:

- **Java 17**
- **Docker Desktop**
- **Git**

Verify Java:

```bash
java -version
```

Verify Docker:

```bash
docker info
```

Verify the Docker CLI can communicate with the server:

```bash
docker ps
```

An empty `docker ps` result is perfectly valid.

---

# Quick Start

This is the recommended local development path.

```text
Start Docker
     ↓
Verify Java + Docker
     ↓
chmod +x mvnw
     ↓
./mvnw clean test
     ↓
BUILD SUCCESS
     ↓
Configure PostgreSQL credentials
     ↓
./mvnw spring-boot:run
     ↓
Use /events and /replay
```

---

## 1. Start Docker

### macOS

```bash
open -a Docker
```

Wait until Docker Desktop finishes starting.

Then:

```bash
docker info
```

and:

```bash
docker ps
```

### Important

Do **not** execute the Docker socket directly.

For example, this is not a valid command:

```text
/Users/<username>/.docker/run/docker.sock
```

The Docker CLI communicates with the socket automatically.

---

## 2. Verify Java and Docker

Check Java:

```bash
java -version
```

Check Docker:

```bash
docker info
```

Check running containers:

```bash
docker ps
```

On Docker Desktop for macOS, if the Docker Desktop context is available:

```bash
docker context ls
```

If necessary:

```bash
docker context use desktop-linux
```

Then:

```bash
docker info
```

You should see a `Server` section.

---

## 3. Make Maven Wrapper Executable

From the project root:

```bash
chmod +x mvnw
```

Verify Maven:

```bash
./mvnw -version
```

You do not need a separate Maven installation when using the Maven Wrapper.

---

## 4. Run the Test Suite

The recommended command is:

```bash
./mvnw clean test
```

This performs:

```text
Clean target/
     ↓
Compile application
     ↓
Compile tests
     ↓
Start PostgreSQL through Testcontainers
     ↓
Start Spring Boot test context
     ↓
Run JUnit tests
     ↓
Stop test containers
```

### First run

The first run can take longer because Testcontainers may download its required Docker images, including PostgreSQL.

For example:

```text
testcontainers/ryuk
postgres:17-alpine
```

This is expected.

Once the images are cached, later test runs are generally faster.

### Successful build

Look for:

```text
BUILD SUCCESS
```

and:

```text
Failures: 0
Errors: 0
```

---

## 5. Start the Application

The application itself needs a PostgreSQL database.

Testcontainers is used for automated tests. For manually running the application, use a local PostgreSQL instance or PostgreSQL through Docker.

Configure:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reconciliation_test
export SPRING_DATASOURCE_USERNAME=reconciliation_user
export SPRING_DATASOURCE_PASSWORD='YOUR_STRONG_PASSWORD'
```

Then:

```bash
./mvnw spring-boot:run
```

The application should be available at:

```text
http://localhost:8080
```

---

# Configuration

The application can receive database configuration through environment variables:

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
```

Example:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reconciliation_test
export SPRING_DATASOURCE_USERNAME=reconciliation_user
export SPRING_DATASOURCE_PASSWORD='YOUR_STRONG_PASSWORD'
```

### PostgreSQL with Docker

If you do not have a local PostgreSQL installation, you can run PostgreSQL with Docker:

```bash
docker run --name reconciliation-postgres \
  -e POSTGRES_DB=reconciliation_test \
  -e POSTGRES_USER=reconciliation_user \
  -e POSTGRES_PASSWORD='YOUR_STRONG_PASSWORD' \
  -p 5432:5432 \
  -d postgres:17-alpine
```

Check:

```bash
docker ps
```

Then start the application:

```bash
./mvnw spring-boot:run
```

Stop the database:

```bash
docker stop reconciliation-postgres
```

Remove it:

```bash
docker rm reconciliation-postgres
```

---

# API

The service exposes two primary endpoints.

---

## POST `/events`

Ingest an account-state event.

### Request

```bash
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{
    "eventId": "E001",
    "timestamp": "2026-08-15T09:00:00Z",
    "accountId": "ACC001",
    "amount": 150.00,
    "currency": "USD",
    "source": "bank"
  }'
```

### Successful response

The response contains the reconciliation result, including:

- resolved event
- resolution method
- considered event IDs
- final balance

---

## POST `/replay`

Replay the stored event history for an account.

```bash
curl -X POST http://localhost:8080/replay \
  -H 'Content-Type: application/json' \
  -d '{
    "accountId": "ACC001",
    "until": "2026-08-15T12:00:00Z"
  }'
```

Replay is read-only.

It reconstructs the account state using the same deterministic reconciliation policy.

---

# Idempotency and Currency Rules

## Event Idempotency

`eventId` is unique.

Example:

```text
POST E001
    ↓
Accepted

POST E001
    ↓
Duplicate
    ↓
409 Conflict
```

Repeated delivery of the same event must not create duplicate records.

---

## Currency

The first event establishes the account currency.

Example:

```text
ACC001
First event → USD
```

A later event with another currency is rejected:

```text
ACC001
First event  → USD
Later event  → EUR
                 ↓
             Rejected
```

The request returns:

```text
400 Bad Request
```

The complete ingestion must roll back.

That means these operations must not partially commit:

```text
Event insert
Account update
Audit insert
```

---

# Auditability

Every successful ingestion creates an audit record.

The audit explains:

- which events were considered
- which event won
- which policy was applied
- what the previous resolved event was
- what the final balance became
- why the winner was selected

This allows the reconciliation decision to be inspected after the fact rather than treating the resolver as an opaque black box.

---

# Replay and Deterministic Fingerprinting

Replay reconstructs state from historical events.

The process is:

```text
Load events
    ↓
Filter account
    ↓
Apply optional "until" boundary
    ↓
Sort canonically
    ↓
Apply reconciliation policy
    ↓
Resolve final state
    ↓
Build canonical representation
    ↓
SHA-256
    ↓
Replay fingerprint
```

The canonical representation contains:

```text
policyVersion
canonical ordered events
resolved event
resolution method
final balance
```

Conceptually:

```text
canonical representation
        ↓
      SHA-256
        ↓
replay fingerprint
```

The same event history, policy version, and ordering rules should produce the same fingerprint.

Replay does not modify live account state.

---

# Testing

The project is designed to test against PostgreSQL rather than treating H2 as an equivalent replacement.

The suite covers:

- application context startup
- reconciliation behavior
- conflict resolution
- duplicate event handling
- currency mismatch
- transactional rollback
- out-of-order events
- timestamp ties
- within-hour conflicts
- deterministic replay
- replay boundaries
- concurrent duplicate ingestion
- concurrent same-account ingestion

Run everything:

```bash
./mvnw clean test
```

---

## Run a Focused Startup Test

Before running the entire suite, you can verify Docker, Testcontainers, PostgreSQL, Spring Boot, and JPA with:

```bash
./mvnw -Dtest=ReconciliationEngineApplicationTests#contextLoads test
```

A successful result should end with:

```text
Tests run: 1, Failures: 0, Errors: 0
BUILD SUCCESS
```

Then run:

```bash
./mvnw clean test
```

---

# Testcontainers and PostgreSQL

Integration tests use Testcontainers to start PostgreSQL automatically.

The architecture is:

```text
JUnit 5
   │
   ▼
Testcontainers
   │
   ▼
PostgreSQL 17
   │
   ▼
Spring Boot
   │
   ▼
JPA / Hibernate
   │
   ▼
Integration Tests
```

This is intentional.

PostgreSQL is required for meaningful verification of database behavior involving:

- transactions
- unique constraints
- locking
- concurrency
- SQL state handling
- database-specific behavior

Therefore:

> **A test passing against H2 is not sufficient evidence that PostgreSQL behavior is correct.**

---

# Troubleshooting

## `./mvnw: permission denied`

Run:

```bash
chmod +x mvnw
```

Then:

```bash
./mvnw clean test
```

---

## Docker is not running

Run:

```bash
open -a Docker
```

Wait for Docker Desktop to start.

Then:

```bash
docker info
docker ps
```

---

## `Could not find a valid Docker environment`

Check:

```bash
docker info
```

Then:

```bash
docker context ls
```

If using Docker Desktop:

```bash
docker context use desktop-linux
```

Then:

```bash
docker ps
```

---

## `NoClassDefFoundError: Could not initialize class`

Do not immediately modify every failing test.

When many tests fail with the same `NoClassDefFoundError`, inspect the first underlying exception.

Run:

```bash
./mvnw -e -Dtest=ReconciliationEngineApplicationTests#contextLoads test
```

Look for the first:

```text
Caused by:
```

A shared test-infrastructure failure can cause many tests to fail at once.

---

## The first test run is slow

This is usually normal.

Testcontainers may need to download:

```text
testcontainers/ryuk
postgres:17-alpine
```

The PostgreSQL image is downloaded only when it is not already available locally.

Subsequent runs should normally be faster.

---

## Hikari reports closed connections

You may see warnings similar to:

```text
Failed to validate connection
This connection has been closed
```

If the suite still finishes with:

```text
Failures: 0
Errors: 0
BUILD SUCCESS
```

the warning did not cause a test failure.

If the suite hangs or fails around concurrency tests, investigate PostgreSQL container lifecycle and Spring/Hikari context reuse before changing reconciliation logic.

---

# Fixtures

Test fixtures are stored under:

```text
fixtures/
```

They cover scenarios including:

- duplicate events
- out-of-order events
- within-hour conflicts
- conflicts greater than one hour
- timestamp ties
- combined scenarios

Fixtures should be treated as behavioral examples of the reconciliation policy.

---

# Stress Testing

A stress-testing harness is included under the test sources.

Supported workload categories include:

```text
normal
duplicate storm
out-of-order events
hot-account / hot-spot contention
```

Large workloads such as:

```text
100k
500k
1M events
```

should be executed on a machine or CI runner with sufficient CPU, memory, and a properly provisioned PostgreSQL instance.

For performance analysis, record at least:

```text
Throughput
p50 latency
p95 latency
p99 latency
Error rate
409 duplicate rate
```

Do not treat a small laptop smoke test as a production benchmark.

---

# Useful Maven Commands

### Full clean test

```bash
./mvnw clean test
```

### Run tests without cleaning

```bash
./mvnw test
```

### Run one test class

```bash
./mvnw -Dtest=ReconciliationEngineApplicationTests test
```

### Run one test method

```bash
./mvnw -Dtest=ReconciliationEngineApplicationTests#contextLoads test
```

### Compile/package without running tests

```bash
./mvnw clean package -DskipTests
```

### Start the application

```bash
./mvnw spring-boot:run
```

### Check Maven version

```bash
./mvnw -version
```

---

# Security Checklist

Before committing or deploying:

- [ ] No real database passwords are committed.
- [ ] No production credentials are committed.
- [ ] No API keys or secrets are committed.
- [ ] Development/internal credentials have been replaced.
- [ ] Any exposed credentials have been rotated.
- [ ] Local configuration uses environment variables where appropriate.
- [ ] CI/CD uses a secrets manager.
- [ ] Production credentials are never copied into README examples.

---

# Project Structure

```text
reconciliation-engine/
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   └── resources/
│   │
│   └── test/
│       ├── java/
│       └── resources/
│
├── fixtures/
│
├── pom.xml
├── mvnw
├── mvnw.cmd
├── README.md
└── LICENSE
```

---

# Development Workflow

Use this workflow when making changes.

### 1. Start Docker

```bash
docker info
```

### 2. Run the focused startup test

```bash
./mvnw -Dtest=ReconciliationEngineApplicationTests#contextLoads test
```

### 3. Run the complete suite

```bash
./mvnw clean test
```

### 4. Inspect failures

Classify the failure before changing code:

```text
Compilation
    ↓
Test infrastructure
    ↓
Database
    ↓
Application startup
    ↓
Application behavior
    ↓
Assertion
    ↓
Concurrency
```

Do not modify reconciliation logic to solve a Docker or Testcontainers problem.

### 5. Start the application

After the tests pass:

```bash
./mvnw spring-boot:run
```

### 6. Exercise the API

Use:

```text
POST /events
POST /replay
```

---

# Determinism Guarantee

The central design goal is:

```text
Same events
      +
Same policy version
      +
Same canonical ordering
      =
Same resolved state
      +
Same replay fingerprint
```

For example:

```text
Arrival order:
A → B → C
```

and:

```text
Arrival order:
C → A → B
```

must produce the same final canonical state when the underlying event history is identical.

This behavior is covered by the replay and concurrency tests.

---

# Development Principles

Changes to the reconciliation policy are behavioral changes.

When changing the policy:

1. Update the policy implementation.
2. Update the relevant tests.
3. Update fixtures.
4. Update the policy version.
5. Verify replay behavior.
6. Verify fingerprint behavior.
7. Document compatibility implications.

Do not silently change reconciliation rules.

---

# License

See [`LICENSE`](./LICENSE).

---

## Quick Reference

### Test everything

```bash
./mvnw clean test
```

### Start Docker on macOS

```bash
open -a Docker
```

### Verify Docker

```bash
docker info
docker ps
```

### Fix Maven permissions

```bash
chmod +x mvnw
```

### Verify Maven

```bash
./mvnw -version
```

### Run only the Spring/PostgreSQL startup test

```bash
./mvnw -Dtest=ReconciliationEngineApplicationTests#contextLoads test
```

### Start the application

```bash
./mvnw spring-boot:run
```

---

## Expected Development Loop

```text
┌─────────────────────┐
│    Docker Running   │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ ./mvnw clean test   │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│    BUILD SUCCESS    │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Configure PostgreSQL│
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ ./mvnw spring-boot: │
│       run           │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│     POST /events    │
│     POST /replay    │
└─────────────────────┘
```

The README is intended to be the single source of truth for local setup, testing, application startup, API usage, reconciliation behavior, and troubleshooting.
