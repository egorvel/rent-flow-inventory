# RentFlow Inventory Service

The Inventory service owns the RentFlow equipment catalogue. Each inventory item has:

- A unique, manually assigned, immutable serial number.
- An equipment type and human-readable name.
- One of these statuses: `AVAILABLE`, `RESERVED`, `RENTED`, `INSPECTION_REQUIRED`,
  `UNDER_MAINTENANCE`, or `RETIRED`.

The REST API supports creating, retrieving, filtering, sorting, replacing, permanently deleting,
and lifecycle-transitioning inventory items. Successful lifecycle transitions are retained in a
separately browsable status-history collection.

## Prerequisites

- Java 25.
- Docker Engine with the Docker Compose plugin. Docker is required for the PostgreSQL 18.4 and
  Apache Kafka 4.3.1 local stack and for Testcontainers during integration tests.
- `curl` for the examples and container smoke test.
- Maven 3.9.x is optional because the repository includes Maven Wrapper 3.3.4, pinned to Maven
  3.9.16.

Ports `8080`, `5432`, and `9092` must be available for the default local stack. Set
`POSTGRES_PORT` or `KAFKA_PORT` before starting Compose when a host port needs to differ.

## Build

Use the Maven Wrapper from the repository root:

```bash
./mvnw -B -ntp clean verify
```

With a supported Maven installation:

```bash
mvn -B -ntp clean verify
```

The executable application is produced at `target/inventory.jar`.

## Run Locally

Start only the PostgreSQL service:

```bash
docker compose up --detach --wait rentflow-postgres
docker compose ps rentflow-postgres
```

Then provide the Inventory role credentials and run the application on the host:

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/rentflow'
export SPRING_DATASOURCE_USERNAME='inventory'
export SPRING_DATASOURCE_PASSWORD='inventory-local'
./mvnw -B -ntp spring-boot:run
```

The values above are development-only defaults from `compose.yaml`. If `POSTGRES_PORT` was
overridden, use the same port in `SPRING_DATASOURCE_URL`.

Stop the local database while preserving its volume:

```bash
docker compose down
```

## Run With Docker Compose

Build and start the complete stack:

```bash
docker compose up --build --detach --wait
docker compose ps
curl --fail --silent --show-error http://localhost:8080/readyz
```

Follow application logs:

```bash
docker compose logs --follow inventory
```

Stop the stack while preserving PostgreSQL data:

```bash
docker compose down
```

Permanently delete the local database volume:

```bash
docker compose down --volumes
```

The volume reset is destructive. It is also required before changing first-run bootstrap
credentials for an existing local volume.

## Database Ownership

RentFlow microservices share the PostgreSQL database named `rentflow`, but each service owns its
own role and schema. Inventory connects as the `inventory` login role and owns only the `inventory`
schema.

On the first start of an empty local volume, `docker/postgres/init-inventory.sh` creates the
Inventory role and schema. The Compose defaults are:

| Setting | Development-only default |
| --- | --- |
| Database | `rentflow` |
| Bootstrap administrator | `rentflow_admin` |
| Bootstrap administrator password | `rentflow-admin-local` |
| Inventory role | `inventory` |
| Inventory role password | `inventory-local` |

These values are for local development only and are not production credentials. Production
deployments must provision the shared database, Inventory role, Inventory schema, and secrets
outside this repository before starting the service.

## Configuration

The application contains no datasource URL or credentials. Supply standard Spring Boot
environment variables at runtime:

| Variable | Required | Purpose |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | Yes | JDBC URL for the shared `rentflow` PostgreSQL database |
| `SPRING_DATASOURCE_USERNAME` | Yes | Inventory-owned database login role |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Secret for the Inventory-owned database login role |
| `SERVER_PORT` | No | HTTP port; defaults to `8080` |
| `KAFKA_BOOTSTRAP_SERVERS` | No | Kafka brokers; defaults to `localhost:9092` |
| `INVENTORY_CANCELLATION_TOPIC` | No | Source topic; defaults to `rentflow.reservation.cancelled.v1` |
| `INVENTORY_CANCELLATION_DLT` | No | DLT; defaults to `rentflow.inventory.reservation-cancellation.dlt.v1` |
| `INVENTORY_CANCELLATION_GROUP_ID` | No | Consumer group; defaults to `rentflow.inventory.reservation-cancellation.v1` |
| `INVENTORY_CANCELLATION_CONCURRENCY` | No | Listener concurrency; defaults to `3` |
| `INVENTORY_CANCELLATION_MAX_PAYLOAD_BYTES` | No | Maximum event value size; defaults to `4096` |
| `INVENTORY_CANCELLATION_MAX_ATTEMPTS` | No | Total processing attempts; defaults to `5` |
| `INVENTORY_CANCELLATION_INITIAL_BACKOFF` | No | First retry delay; defaults to `1s` |
| `INVENTORY_CANCELLATION_BACKOFF_MULTIPLIER` | No | Retry-delay multiplier; defaults to `2` |
| `INVENTORY_CANCELLATION_MAX_BACKOFF` | No | Maximum retry delay; defaults to `8s` |
| `JAVA_TOOL_OPTIONS` | No | JVM runtime options |

The local Compose bootstrap can be overridden with `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`, `INVENTORY_DB_USER`, `INVENTORY_DB_PASSWORD`, and `POSTGRES_PORT`. Compose
publishes PostgreSQL only on the host loopback interface.

## Asynchronous Reservation Cancellation

Inventory consumes the past-tense `ReservationCancelled` event because Reservation owns the fact
that a reservation was cancelled, while Inventory owns the resulting equipment lifecycle rule.
This is intentionally eventually consistent: Reservation does not wait for Inventory, and an
item can remain `RESERVED` briefly after cancellation.

The version-1 source record is a UTF-8 JSON object no larger than 4096 bytes:

```json
{
  "eventId": "d880f919-2b5c-4f7e-a56d-e047e7d932a6",
  "eventType": "ReservationCancelled",
  "eventVersion": 1,
  "occurredAt": "2026-09-15T15:30:00Z",
  "serialNumber": "DRILL-001"
}
```

The Kafka record key must be the UTF-8 encoding of the exact case-sensitive `serialNumber`.
Inventory ignores unknown JSON properties but validates all five known fields strictly. The event
contains no `reservationId`, and Inventory publishes no result event.

The source topic is `rentflow.reservation.cancelled.v1`, with three partitions, delete cleanup,
and seven-day retention. Inventory uses consumer group
`rentflow.inventory.reservation-cancellation.v1`. Production targets replication factor three and
minimum ISR two; the single-node local stack uses replication factor one.

For a new event, an item in `RESERVED` becomes `AVAILABLE` and receives the normal immutable
status-history row. An item already in `AVAILABLE` is a successful `ALREADY_AVAILABLE` no-op and
does not receive false same-state history. Inventory stores an inbox receipt indefinitely in the
same PostgreSQL transaction as the optional item and history writes.

That inbox is needed even though `AVAILABLE` is a safe no-op. Kafka can redeliver an old event
after the item has become available and then been reserved again; checking only the current state
would incorrectly release that later reservation. A matching completed `eventId` therefore
returns `DUPLICATE` without inspecting the item. Because the event has no reservation ownership
identifier, a delayed *first* delivery after out-of-band reassignment remains a known limitation.

Delivery is at least once, with an effectively-once Inventory database effect rather than a
distributed transaction. Transient failures receive five total attempts with default delays of
1, 2, 4, and 8 seconds per retry cycle. Exception-type changes and failed DLT publication reset
the cycle, so five attempts is not a lifetime limit for a source record. A configured single
attempt disables retries; zero backoff allows immediate retries bounded by the configured count.
Deterministic failures and Spring's standard fatal exceptions go directly to
`rentflow.inventory.reservation-cancellation.dlt.v1`; exhausted transient failures use
`PROCESSING_RETRIES_EXHAUSTED`, as do framework-fatal failures. The other stable failure codes are `INVALID_EVENT`,
`UNSUPPORTED_EVENT`, `EVENT_ID_REUSED`, `INVENTORY_ITEM_NOT_FOUND`, and
`INVALID_INVENTORY_STATUS`.

The DLT has three partitions, delete cleanup, and 30-day retention. It preserves the original key
and value and adds these bounded UTF-8 diagnostic headers: `rentflow.dlt.source-topic`,
`rentflow.dlt.source-partition`, `rentflow.dlt.source-offset`, `rentflow.dlt.consumer-group`,
`rentflow.dlt.failure-code`, `rentflow.dlt.failed-at`, and `rentflow.dlt.event-id` when parseable.
There is no automatic replay. Alert on `dead_letter`, `dead_letter_publish_failure`, and sustained
consumer lag from `inventory.reservation.cancellation.records{outcome="..."}`; the other bounded
outcomes are `received`, `released`, `already_available`, `duplicate`, and `retry`.

Automatic topic creation remains disabled. With the common stack running, create the
Reservation-owned source topic explicitly; Inventory creates only its DLT when the `local` profile
is active:

```bash
docker compose exec --no-TTY rentflow-kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:19092 \
  --create --if-not-exists \
  --topic rentflow.reservation.cancelled.v1 \
  --partitions 3 --replication-factor 1 \
  --config cleanup.policy=delete \
  --config retention.ms=604800000
```

For a guarded manual replay, first diagnose the DLT failure and verify current Reservation and
Inventory state, especially for missing-item or invalid-status failures. Do not replay an invalid
or unsupported record unchanged, and investigate `EVENT_ID_REUSED` as a producer defect. When a
valid replay is safe, republish the original bytes to the source topic with the original
case-sensitive key and unchanged `eventId`, then confirm the outcome through metrics and Inventory
history. The seven-day source-retention window means a prolonged outage requires the future
Reservation outbox to republish before its retained event is lost.

## Database Migrations

Flyway migrations live in `src/main/resources/db/migration` and follow the
`V{n}__description.sql` naming convention. Flyway writes its history to
`inventory.flyway_schema_history`; application tables and migration history remain inside the
Inventory-owned schema.

Migrations are append-only. Never edit a migration that has shipped; add a new versioned migration
instead. The `inventory` schema must already exist and be owned by the application role. Startup
fails if the database is unavailable, the schema or privileges are missing, a migration fails, or
JPA validation finds schema drift.

## API And Health

With the service running locally:

| Resource | URL |
| --- | --- |
| Inventory API | `http://localhost:8080/api/v1/inventory` |
| Inventory status history | `http://localhost:8080/api/v1/inventory-history` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI document | `http://localhost:8080/v3/api-docs` |
| Liveness | `http://localhost:8080/livez` |
| Actuator liveness | `http://localhost:8085/actuator/health/liveness` |
| Readiness | `http://localhost:8080/readyz` |
| Actuator readiness | `http://localhost:8085/actuator/health/readiness` |
| Prometheus (host execution) | `http://localhost:8085/actuator/prometheus` |

Health details are hidden. Liveness is independent of PostgreSQL; readiness returns HTTP `503`
when the database is unavailable.

## Observability

Inventory exports Prometheus metrics, writes ECS JSON logs to stdout, and instruments HTTP,
Kafka, JDBC connections/queries and idempotency cleanup with Micrometer observations and
OpenTelemetry tracing. The specification is in `.specs/observability/`.

Management serves only health and Prometheus on a separate port. Host execution binds it to
loopback. Compose binds management inside the container without publishing it on the host:

```bash
docker compose exec --no-TTY inventory \
  curl --fail --silent --show-error http://localhost:8085/actuator/prometheus
```

Application probes stay on port 8080. Readiness depends on PostgreSQL; neither Kafka nor telemetry
collectors become readiness dependencies. The shared Grafana/Prometheus/Loki/Tempo/Alloy stack
belongs to Common and is not included in this repository's Compose deployment.

| Variable | Default | Purpose |
| --- | --- | --- |
| `MANAGEMENT_SERVER_PORT` | `8085` | Separate internal management listener |
| `MANAGEMENT_SERVER_ADDRESS` | `127.0.0.1` | Host binding; Compose overrides to `0.0.0.0` |
| `OBSERVABILITY_ENVIRONMENT` | `local` | Bounded environment identity |
| `OBSERVABILITY_INSTANCE` | `HOSTNAME`, then `standalone` | Runtime instance for logs and traces |
| `TRACING_EXPORT_ENABLED` | `false` | Enable asynchronous OTLP trace export |
| `TRACING_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | Complete Alloy OTLP HTTP/protobuf endpoint |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Parent-based sampling probability; reduce as traffic grows |
| `INVENTORY_KAFKA_CLUSTER_ID` | Unset | Optional actual broker cluster ID; otherwise Spring Kafka discovers it |
| `INVENTORY_IDEMPOTENCY_CLEANUP_BACKLOG_REFRESH_DELAY` | `60s` | Independent backlog measurement interval |

For host development use `SPRING_PROFILES_ACTIVE=local`; enable export only
when Alloy is available. Restrict the management listener
with private networking, firewall/ingress rules and TLS outside local development.

HTTP latency histograms use route templates. Standard JVM/process/Hikari/Kafka client meters
remain available. Custom metrics retain their Micrometer names, converted to Prometheus naming
on scrape (dots become underscores; timers use seconds and counters have `_total` suffixes):

| Meter | Meaning |
| --- | --- |
| `inventory.idempotency.requests{outcome}` | `attempt`, `replay`, `mismatch`, `busy`; attempts include later rollbacks |
| `inventory.status.transition{outcome,error}` | Observation timer with latency histogram and invocation count; `accepted`, `rejected`, `error` describe method execution before the surrounding transaction commits |
| `inventory.status.transition.batch.size` | Distribution summary: one sample per service invocation, including rejected/failed executions; count is attempted batches, sum is submitted items |
| `inventory.reservation.cancellation.records{outcome}` | Listener invocations (`received`, including retries), committed `released`/`already_available`/`duplicate`, scheduled `retry`, confirmed `dead_letter`, failed `dead_letter_publish_failure` |
| `inventory.reservation.cancellation.completion.delay` | `occurredAt` to committed release/already-available; excludes duplicates/failures/future timestamps |
| `inventory.reservation.cancellation.clock.skew` | Successful nonduplicate events with future `occurredAt`; processing still succeeds |
| `inventory.idempotency.cleanup.deleted` / `.failures` / `.duration` | Committed chunk deletions, failed deletion runs, total run duration |
| `inventory.idempotency.cleanup.expired.backlog` | Last background count; `NaN` before the first successful measurement |
| `inventory.idempotency.cleanup.backlog.last.success.timestamp` | Epoch seconds of the last successful count; zero means unknown |
| `inventory.idempotency.cleanup.backlog.refresh.failures` | Failed measurements; prior count and timestamp remain unchanged |
| `inventory.idempotency.cleanup.last.run.timestamp` / `.last.success.timestamp` | Epoch seconds of worker start and successful deletion completion; zero means no run/success |

Status-transition traces include `batch.size` and, for business rejections, `rejected.items`
(validation failures, not all unchanged items). Exact sizes and equipment identifiers are not
metric labels. Idempotency replays and requests rejected before the service method do not
contribute. In Prometheus, use `inventory_status_transition_seconds_count` for invocation rates
by outcome and `inventory_status_transition_seconds_bucket` for latency percentiles. The batch
summary exports `inventory_status_transition_batch_size_count` and `_sum` without histogram
buckets; the explicit histogram override keeps the transition timer's prefix setting from
enabling buckets for the summary too.

Backlog counting has a five-second database query timeout and does not run on scrape. Daily
deletion remains at 03:00 UTC with the existing transaction/chunk/budget rules. Measurement
failure is separate from deletion failure. Backlog is database-wide; do not sum its values across
Inventory instances. Sum per-instance deletion/outcome counters when computing throughput.

Logs contain `service.name`, `service.environment`, `service.node.name`, `traceId` and `spanId`
when an observation is active. Cancellation results produce one INFO record with `eventId`,
`serialNumber`, `outcome`, `sourceTopic`, `partition` and `offset`; retries and recovery failures
add bounded failure categories and ordinary exception diagnostics. Spring Boot's native ECS
encoder retains exception messages, causes, suppressed exceptions and useful structured fields.
Printed stacks are limited to 16384 characters and 64 frames per throwable. Hibernate error
diagnostics remain enabled; Kafka consumer/producer/admin configuration loggers use WARN.
These logs are diagnostics, not a durable audit trail.

Actual request paths and equipment identifiers are permitted in internal diagnostics; metric
labels still use route templates and never individual equipment/event IDs. Automatic HTTP body,
header and query-string capture remains disabled, as do JDBC parameter capture and Hibernate
bind-value TRACE logging. Application logs do not dump complete Kafka payloads or undecoded keys,
or deliberately include credentials/tokens. Public unexpected-error responses remain generic.
There is no blanket field allowlist or arbitrary-text redactor: SQL literals and driver/exception
messages can contain values. Use targeted native configuration or collector controls for a
specific sensitive field if one is identified.

Tracing uses framework W3C `traceparent`/`tracestate` propagation through Spring Kafka, Micrometer
and OpenTelemetry. Baggage propagation is disabled with `management.tracing.baggage.enabled=false`.
The tracing libraries handle incoming context; missing or unusable context does not reject a
cancellation. DLT records retain their exact key/value/partition and
diagnostic headers, plus valid tracing context. Retries share the originating trace and DLT
publication has its own producer span. Reservation must separately preserve context in its
durable outbox for producer-restart correlation. JDBC spans retain timing, datasource identity
and SQL statement text with bound-parameter capture disabled. Framework span events, links and
error descriptions are retained. Export uses a bounded 2048-span queue, 512-span batches, and two-second
connection/call timeouts; overload drops telemetry rather than blocking business processing.

Kafka cluster identity normally comes from Spring Kafka discovery. A successful lookup is
cached; in installed Spring Kafka 4.1.0, failed discovery may recur during observed operations
and uses a default 30-second admin operation timeout. To bypass discovery when admin access is
restricted or delays are observed, set `INVENTORY_KAFKA_CLUSTER_ID` on Inventory to the existing
identity of its target broker cluster. Do not generate an application ID or use a placeholder.
The override identifies the remote service in traces; it does not choose brokers or change groups.

## API Examples

Create an available inventory item:

```bash
curl --fail-with-body --include \
  --request POST 'http://localhost:8080/api/v1/inventory' \
  --header 'Content-Type: application/json' \
  --data '{
    "serialNumber": "DRILL-001",
    "type": "Industrial drill",
    "name": "Bosch GBH 8-45 DV",
    "status": "AVAILABLE"
  }'
```

Retrieve it by its case-sensitive serial number:

```bash
curl --fail-with-body \
  'http://localhost:8080/api/v1/inventory/DRILL-001'
```

List available industrial drills, sorted by name:

```bash
curl --fail-with-body \
  'http://localhost:8080/api/v1/inventory?page=0&size=20&status=AVAILABLE&type=Industrial%20drill&sort=name&direction=asc'
```

Transition inventory statuses atomically with `PATCH /api/v1/inventory/status`. The body is an
array of 1–100 objects with unique, case-sensitive serial numbers. Each entry supplies its own
target status. Success returns `204 No Content`, changes only statuses, and creates one history
record per item. All item changes and history records commit together.

Generate a UUID v4 once per business attempt and keep it for every retry of that attempt.
This one-item batch transitions the item created above from `AVAILABLE` to `RESERVED`:

```bash
transition_key=$(cat /proc/sys/kernel/random/uuid)
curl --fail-with-body \
  --request PATCH \
  --header "Idempotency-Key: $transition_key" \
  --output /dev/null \
  --write-out '%{http_code}\n' \
  'http://localhost:8080/api/v1/inventory/status' \
  --header 'Content-Type: application/json' \
  --data '[{"serialNumber":"DRILL-001","status":"RESERVED"}]'
```

A disallowed transition from `RESERVED` to `RETIRED` rejects the entire batch. This example also
includes a missing item, so both failures are reported:

```bash
transition_key=$(cat /proc/sys/kernel/random/uuid)
curl --include \
  --request PATCH \
  --header "Idempotency-Key: $transition_key" \
  'http://localhost:8080/api/v1/inventory/status' \
  --header 'Content-Type: application/json' \
  --data '[
    {"serialNumber":"DRILL-001","status":"RETIRED"},
    {"serialNumber":"MISSING","status":"RENTED"}
  ]'
```

The response is `409 Conflict` with `Content-Type: application/problem+json`:

```json
{
  "type": "urn:rentflow:problem:invalid-inventory-status-transition",
  "title": "Invalid inventory status transition",
  "status": 409,
  "detail": "No inventory statuses were changed.",
  "instance": "/api/v1/inventory/status",
  "code": "INVALID_INVENTORY_STATUS_TRANSITION",
  "failedItems": [
    {"index": 0, "serialNumber": "DRILL-001", "status": "RETIRED",
     "code": "INVALID_INVENTORY_STATUS_TRANSITION",
     "message": "Inventory item 'DRILL-001' cannot transition from RESERVED to RETIRED."},
    {"index": 1, "serialNumber": "MISSING", "status": "RENTED",
     "code": "INVENTORY_ITEM_NOT_FOUND", "message": "Inventory item 'MISSING' was not found."}
  ]
}
```

`failedItems` lists all and only failed entries in original request order; `index` is zero-based
and `status` is the requested target. Any forbidden transition produces `409`, including mixed
failures. When every failed entry is missing, the response is `404 INVENTORY_ITEM_NOT_FOUND`.
Otherwise valid entries also remain unchanged when a batch fails. Same-status requests remain
forbidden. Invalid input, including empty/oversized arrays, null entries, and duplicate serials,
returns `400` before lifecycle evaluation, with input errors only. The former
`PATCH /api/v1/inventory/{serialNumber}/status` endpoint is removed; no API version is added.

The required `Idempotency-Key` is one plain, canonical 36-character UUID v4 (either hex case),
scoped globally to this endpoint. Missing, repeated or invalid keys return `400 VALIDATION_FAILED`.
Input validation finishes before checking the key. Equivalent payloads ignore JSON whitespace and
property order, but preserve array order, serial-number case and requested status.

| Retry condition | Response |
| --- | --- |
| Same unexpired key and payload | Original `204`, `404` or `409`, with no inventory evaluation or new history |
| Same completed key, different valid payload | `422 IDEMPOTENCY_KEY_REUSED` |
| Key currently executing | Immediate `409 IDEMPOTENCY_IN_PROGRESS`, `Retry-After: 1` |
| Expired key | New attempt, even if cleanup has not removed the old row |

Completed outcomes include `Idempotency-Replayed: true|false` and
`Idempotency-Key-Expires-At: <UTC instant>`. The guarantee lasts **seven days from database-recorded
completion**; replay never extends expiry. Saved lifecycle errors also replay after inventory changes;
use a new key for a new business attempt. A same-status target is still rejected on new attempts.
Validation failures and rolled-back server errors do not consume the key.

For network failures, uncertain responses or retryable server errors, retry the same key and payload
with exponential backoff and jitter, honoring `Retry-After`. Keep the original key instead of rerunning
the UUID-generation line. Beyond seven days, reconcile an uncertain outcome before retrying: replay
is no longer guaranteed. JSON property ordering and transport headers may differ on replay, but the
original status and problem fields are preserved.

Cleanup runs **daily at 03:00 UTC**, configured by `inventory.idempotency.cleanup.cron`
(default `0 0 3 * * *`). It deletes up to 1000 expired request records per transaction, skips locked
rows and continues full chunks within `inventory.idempotency.cleanup.runtime-budget` (default `60s`).
An already-running chunk may finish after that budget. Expiry is enforced on each request; retained
expired rows do not extend the guarantee. History is never cleaned by this job.

Metrics: `inventory.idempotency.requests` has bounded `outcome` values `attempt`, `replay`, `mismatch`
and `busy`; cleanup exposes `inventory.idempotency.cleanup.deleted`, `.failures`, `.duration` and
`.expired.backlog` (last successful run's count, initially zero). Attempts count executions started,
including those that later roll back. Monitor failures/backlog if cleanup falls behind. No key or
serial-number metric labels are used.

Deploy migration V3 before the coordinated endpoint cutover, update callers to send keys and drain
old instances. Mixed old/new application versions cannot provide the replay guarantee.

Browse status history using the defaults `page=0`, `size=20`, `sort=timestamp`, and
`direction=desc`:

```bash
curl --fail-with-body \
  'http://localhost:8080/api/v1/inventory-history'
```

Each history record exposes exactly `serialNumber`, `statusFrom`, `statusTo`, and `timestamp`.
Filter by a case-sensitive literal serial-number substring and sort by any of `serialNumber`,
`statusFrom`, `statusTo`, or `timestamp` in either direction:

```bash
curl --fail-with-body \
  'http://localhost:8080/api/v1/inventory-history?page=0&size=20&serialNumber=DRILL&sort=statusFrom&direction=asc'
```

History pages allow sizes from `1` through `100`. The default order is newest first, with an
internal deterministic tie-breaker that is not exposed by the API. History remains available after
the corresponding inventory item is deleted.

Fully replace its mutable values. The body serial number must match the path:

```bash
curl --fail-with-body \
  --request PUT 'http://localhost:8080/api/v1/inventory/DRILL-001' \
  --header 'Content-Type: application/json' \
  --data '{
    "serialNumber": "DRILL-001",
    "type": "Industrial drill",
    "name": "Bosch GBH 8-45 DV - Workshop A",
    "status": "UNDER_MAINTENANCE"
  }'
```

Permanently delete it:

```bash
curl --fail-with-body \
  --request DELETE \
  --output /dev/null \
  --write-out '%{http_code}\n' \
  'http://localhost:8080/api/v1/inventory/DRILL-001'
```

## Verification

The required verification lifecycle is:

```bash
mvn -B -ntp clean verify
```

It compiles production and test code, checks Palantir Java formatting, runs no-context unit tests,
runs the ArchUnit layer-boundary suite, and runs the full integration and generated-OpenAPI
contract suites against PostgreSQL 18.4 Testcontainers. Tests are not skipped. Docker must be
running.

The Maven Wrapper executes the same lifecycle without a separately installed Maven distribution:

```bash
./mvnw -B -ntp clean verify
```

Apply the pinned formatter and then rerun verification when Spotless reports a failure:

```bash
./mvnw -B -ntp spotless:apply
mvn -B -ntp clean verify
```

Run the isolated container acceptance test with:

```bash
./scripts/container-smoke-test.sh
```

The smoke test builds the image, verifies the role and schema bootstrap, checks the unprivileged
runtime image, exercises CRUD and persistence, confirms liveness/readiness behavior during a
database outage and recovery, and removes its containers and volume on exit.
