# RentFlow Inventory Service

The Inventory service owns the RentFlow equipment catalogue. Each inventory item has:

- A unique, manually assigned, immutable serial number.
- An equipment type and human-readable name.
- One of these statuses: `AVAILABLE`, `RESERVED`, `RENTED`, `INSPECTION_REQUIRED`,
  `UNDER_MAINTENANCE`, or `RETIRED`.

The REST API supports creating, retrieving, filtering, sorting, replacing, and permanently deleting
inventory items.

## Prerequisites

- Java 25.
- Docker Engine with the Docker Compose plugin. Docker is required for the PostgreSQL 18.4 local
  stack and for Testcontainers during integration tests.
- `curl` for the examples and container smoke test.
- Maven 3.9.x is optional because the repository includes Maven Wrapper 3.3.4, pinned to Maven
  3.9.16.

Ports `8080` and `5432` must be available for the default local stack. Set `POSTGRES_PORT` before
starting Compose when the host PostgreSQL port needs to differ.

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
| `JAVA_TOOL_OPTIONS` | No | JVM runtime options |

The local Compose bootstrap can be overridden with `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`, `INVENTORY_DB_USER`, `INVENTORY_DB_PASSWORD`, and `POSTGRES_PORT`. Compose
publishes PostgreSQL only on the host loopback interface.

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
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI document | `http://localhost:8080/v3/api-docs` |
| Liveness | `http://localhost:8080/livez` |
| Actuator liveness | `http://localhost:8080/actuator/health/liveness` |
| Readiness | `http://localhost:8080/readyz` |
| Actuator readiness | `http://localhost:8080/actuator/health/readiness` |

Health details are hidden. Liveness is independent of PostgreSQL; readiness returns HTTP `503`
when the database is unavailable.

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
