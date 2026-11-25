# RentFlow Inventory Service

Owns the equipment catalogue, lifecycle transitions, and status history. Built with Java 25,
Spring Boot, PostgreSQL/Flyway, and Kafka.

## Prerequisites

- Java 25 and Docker Engine with Docker Compose.
- Common cloned alongside this repository; Python 3 for its infrastructure scripts.
- `curl` for the API example and container smoke test.

Use `./mvnw` below, or `mvn` with Maven 3.9.x installed.

## Run locally

Complete [Common's first-time Keycloak setup](../rent-flow-common/README.md#local-keycloak),
including the `127.0.0.1 auth.rentflow.test` hosts entry. Then, from this repository:

```bash
../rent-flow-common/scripts/local-dev.sh start

export SPRING_PROFILES_ACTIVE='local'
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/rentflow'
export SPRING_DATASOURCE_USERNAME='inventory'
export SPRING_DATASOURCE_PASSWORD='inventory-local'
./mvnw -B -ntp spring-boot:run
```

These are development-only database defaults. Match any overridden ports or credentials in
[Common's infrastructure configuration](../rent-flow-common/README.md#local-development-infrastructure).
The local profile supplies the Keycloak issuer/JWKS URLs and enables Swagger.

For IntelliJ IDEA, run `com.rentflow.InventoryApplication` with Java 25, the repository as the
working directory, the `local` profile, and the three datasource environment variables above.

Stop shared infrastructure while keeping its data:

```bash
../rent-flow-common/scripts/local-dev.sh stop
```

## Run with Docker Compose

For the complete service stack, use [Common's secured stack setup](../rent-flow-common/README.md#local-keycloak).

This repository's [compose.yaml](compose.yaml) starts Inventory and PostgreSQL only. Before
starting it, set `INVENTORY_JWT_ISSUER` and `INVENTORY_JWT_JWKS` to the trusted issuer and its
JWKS endpoint. They must be reachable from the container. Using Common's Keycloak requires a
network override; this Compose project does not join Common's network automatically.

```bash
docker compose up --build --detach --wait
docker compose logs --follow inventory
```

`INVENTORY_PORT` and `POSTGRES_PORT` override the default host ports `8080` and `5432`.
Stop with `docker compose down`; add `--volumes` only to permanently delete the database.
Do not run this database alongside Common's PostgreSQL on the same host port.

## Configuration

Defaults and supported overrides are in [application.yaml](src/main/resources/application.yaml)
and [application-local.yaml](src/main/resources/application-local.yaml). Key runtime settings:

| Variable | Purpose / default |
| --- | --- |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Required database connection |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Required trusted issuer; supplied by the local profile |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | Required JWKS endpoint; supplied by the local profile |
| `SERVER_PORT` | Application port; `8080` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers; `localhost:9092` |
| `MANAGEMENT_SERVER_PORT`, `MANAGEMENT_SERVER_ADDRESS` | Management listener; `8085`, `127.0.0.1` |
| `TRACING_EXPORT_ENABLED` | OTLP trace export; `false` |
| `TRACING_OTLP_ENDPOINT` | OTLP HTTP endpoint; `http://localhost:4318/v1/traces` |
| `TRACING_SAMPLING_PROBABILITY` | Sampling probability; currently `1.0` |

Inventory connects to the shared `rentflow` database as the `inventory` role and owns only the
`inventory` schema. Provision the database, role, and schema before startup; local infrastructure
provides them for development. Flyway applies [versioned migrations](src/main/resources/db/migration)
automatically. Shipped migrations are append-only.

For existing deployments, review [security cutover and rollback](.specs/security/design.md#9-common-handoff-and-rollout)
before upgrading.

## API and health

| Resource | Local URL |
| --- | --- |
| Inventory API | `http://localhost:8080/api/v1/inventory` |
| Status history | `http://localhost:8080/api/v1/inventory-history` |
| Swagger UI (local profile) | <http://localhost:8080/swagger-ui.html> |
| OpenAPI (local profile) | <http://localhost:8080/v3/api-docs> |
| Public probes | `http://localhost:8080/livez`, `http://localhost:8080/readyz` |
| Management health | `http://localhost:8085/actuator/health` |
| Prometheus | `http://localhost:8085/actuator/prometheus` |

Business APIs require a bearer access token for `inventory-api`. In local Swagger, use **Authorize**
to sign in through Keycloak with PKCE. Use a staff account for catalogue administration and history.
See the [permission matrix](.specs/security/design.md#3-permission-matrix-and-http-rules) and
[identity walkthrough](../rent-flow-common/SECURITY_WALKTHROUGH.md) for caller setup.

Readiness returns `503` when PostgreSQL is unavailable; liveness does not depend on it. Management
is private, and Compose does not publish its port. Swagger and OpenAPI are disabled outside the
local profile by default.

### Example

Set `INVENTORY_CURL_CONFIG` to a private mode-0600 curl config file containing
`header = "Authorization: Bearer <access-token>"`. Use a staff token, keep the file outside version
control, and remove it after use.

```bash
curl --config "$INVENTORY_CURL_CONFIG" --fail-with-body --include \
  --request POST 'http://localhost:8080/api/v1/inventory' \
  --header 'Content-Type: application/json' \
  --data '{"serialNumber":"DRILL-001","type":"Industrial drill","name":"Workshop drill","status":"AVAILABLE"}'
```

Status changes use `PATCH /api/v1/inventory/status` with a batch body and a UUID v4
`Idempotency-Key`. Keep the same key and payload when retrying an uncertain request; generate a
new key for a new business attempt. Replays are scoped to the caller and retained for seven days. See the
[transition API](.specs/inventory-status-transition/design.md#2-http-api) and
[idempotency contract](.specs/inventory-status-transition/design.md#9-durable-idempotency-amendment)
for request/response details.

## Cancellation processing

Inventory consumes `rentflow.reservation.cancelled.v1` and releases reserved equipment
asynchronously. Reservation's local profile creates the source topic; Inventory's local profile
creates `rentflow.inventory.reservation-cancellation.dlt.v1`. For production, provision both topics
before startup.

See the [event and topic contract](.specs/async-reservation-cancellation/design.md#2-kafka-contract),
[consumer configuration](.specs/async-reservation-cancellation/design.md#4-inventory-processing-design),
and [manual DLT replay procedure](.specs/async-reservation-cancellation/design.md#64-manual-replay-procedure).

## Observability

Prometheus metrics, ECS JSON logs, and OTLP traces integrate with
[Common's optional observability stack](../rent-flow-common/README.md#shared-observability).
Set `TRACING_EXPORT_ENABLED=true` when a collector is available. For a container, set
`TRACING_OTLP_ENDPOINT` to the collector's reachable `/v1/traces` URL.

Scrape container metrics without publishing management:

```bash
docker compose exec --no-TTY inventory \
  curl --fail --silent --show-error http://localhost:8085/actuator/prometheus
```

Metric definitions, trace/log behavior, and monitoring guidance live in the
[observability spec](.specs/observability/design.md) and
[Common integration handoff](COMMON_OBSERVABILITY_HANDOFF.md).

## Build and verify

Docker must be running for the PostgreSQL and Kafka Testcontainers suites:

```bash
./mvnw -B -ntp clean verify
```

This checks formatting, unit tests, architecture boundaries, and integration/OpenAPI contracts.
The executable artifact is `target/inventory.jar`. If formatting fails, run
`./mvnw -B -ntp spotless:apply`, then repeat verification.

Run the isolated container smoke test (cleans up its own containers and volume):

```bash
./scripts/container-smoke-test.sh
```

For security test coverage and verification status, see
[the security tasks](.specs/security/tasks.md#regression-test-entry-points).
