# Inventory Service Skeleton Requirements

Status: Requirements, design, and implementation tasks defined; ready for implementation.

## Context

RentFlow needs an inventory service that owns the equipment catalogue and provides a dependable
foundation for later rental workflows. The repository currently has no application skeleton, so
the first increment must establish both the smallest useful catalogue API and the engineering
baseline required to build, run, inspect, and verify it.

The initial resource is an inventory item with exactly these business attributes:

- `serialNumber`: a unique, manually assigned, immutable identifier.
- `type`: the equipment category or type, such as `Industrial drill`, `Concrete mixer`, or
  `Jackhammer`.
- `name`: the human-readable equipment name.
- `status`: one of `AVAILABLE`, `RESERVED`, `RENTED`, `INSPECTION_REQUIRED`,
  `UNDER_MAINTENANCE`, or `RETIRED`.

The service exposes an unauthenticated JSON REST API under `/api/v1/inventory` for:

| Capability | Method and resource |
| --- | --- |
| Create an item | `POST /api/v1/inventory` |
| List and filter items | `GET /api/v1/inventory` |
| Retrieve one item | `GET /api/v1/inventory/{serialNumber}` |
| Fully replace one item | `PUT /api/v1/inventory/{serialNumber}` |
| Permanently delete one item | `DELETE /api/v1/inventory/{serialNumber}` |

This feature also establishes the service's executable documentation, database migration path,
container packaging, local developer documentation, health reporting, and automated verification.
The mandated platform is Java 25, Spring Boot 4, Maven, PostgreSQL 18.4, Flyway, Spring Data JPA,
Hibernate, and Testcontainers.

At this stage, RentFlow microservices share one PostgreSQL database named `rentflow`. The inventory
service connects with its own `inventory` role and owns only the `inventory` schema and the objects
within it. In deployed environments, database, role, and schema provisioning are platform
responsibilities; the local Compose stack bootstraps equivalent development prerequisites.
Inventory Flyway migrations own the service's tables, indexes, constraints, and schema history
without modifying objects owned by another service.

## User stories

### US1 - Create an inventory item

As a catalogue operator, I want to register a piece of equipment under its assigned serial number
so that it becomes part of the equipment catalogue.

- **AC1.1 (Event-driven):** When a client submits a valid item representation containing a
  non-blank `serialNumber`, `type`, and `name` and one of the defined `status` values, the inventory
  service shall persist the item and return `201 Created` with the created representation and a
  `Location` header for that item.
- **AC1.2 (Ubiquitous):** The inventory service shall use the client-supplied `serialNumber` and
  shall not generate or replace it.
- **AC1.3 (Unwanted):** If a client submits a `serialNumber` that already identifies an item, then
  the inventory service shall return `409 Conflict` and shall leave the existing item unchanged.
- **AC1.4 (Unwanted):** If a create request is malformed, omits a required attribute, contains a
  blank required string, or contains an undefined status, then the inventory service shall return
  `400 Bad Request` and shall not persist an item.

### US2 - Retrieve an inventory item

As a catalogue consumer, I want to retrieve equipment by serial number so that I can use its
current catalogue data.

- **AC2.1 (Event-driven):** When a client requests an existing `serialNumber`, the inventory service
  shall return `200 OK` with that item's `serialNumber`, `type`, `name`, and `status`.
- **AC2.2 (Unwanted):** If a requested `serialNumber` does not identify an item, then the inventory
  service shall return `404 Not Found`.

### US3 - Browse the equipment catalogue

As a catalogue consumer, I want a bounded, sortable, filterable item list so that I can browse the
catalogue without retrieving the entire data set.

- **AC3.1 (Event-driven):** When a client requests the collection without query parameters, the
  inventory service shall return `200 OK` with the first bounded page, deterministic ordering, and
  page metadata.
- **AC3.2 (Event-driven):** When a client supplies valid page and page-size parameters, the
  inventory service shall return the requested page subject to a documented maximum page size.
- **AC3.3 (Event-driven):** When a client filters by one defined `status`, the inventory service
  shall return only items with that exact status.
- **AC3.4 (Event-driven):** When a client filters by `type`, the inventory service shall return only
  items matching that exact type according to the documented matching rules.
- **AC3.5 (Event-driven):** When a client supplies both `status` and `type` filters, the inventory
  service shall apply both filters to the result.
- **AC3.6 (Event-driven):** When a client requests ascending or descending sorting by
  `serialNumber`, `type`, `name`, or `status`, the inventory service shall return the page in the
  requested deterministic order.
- **AC3.7 (Unwanted):** If pagination, sorting, or filter parameters are invalid or unsupported,
  then the inventory service shall return `400 Bad Request`.
- **AC3.8 (Event-driven):** When no items match a valid collection request, the inventory service
  shall return `200 OK` with an empty item list and page metadata.

### US4 - Replace an inventory item

As a catalogue operator, I want to replace an item's mutable catalogue data so that the catalogue
reflects its current description and status.

- **AC4.1 (Event-driven):** When a client submits a valid full replacement for an existing item,
  the inventory service shall replace its `type`, `name`, and `status` and return `200 OK` with the
  updated representation.
- **AC4.2 (Ubiquitous):** The inventory service shall keep an item's `serialNumber` immutable after
  creation.
- **AC4.3 (Unwanted):** If a replacement representation contains a `serialNumber` different from
  the path identifier, then the inventory service shall return `400 Bad Request` and shall leave
  the item unchanged.
- **AC4.4 (Unwanted):** If a replacement is malformed, omits a required attribute, contains a blank
  required string, or contains an undefined status, then the inventory service shall return
  `400 Bad Request` and shall leave the item unchanged.
- **AC4.5 (Unwanted):** If a client attempts to replace a non-existent item, then the inventory
  service shall return `404 Not Found` and shall not create an item.
- **AC4.6 (Event-driven):** When a replacement contains any defined status, the inventory service
  shall accept that status without applying lifecycle-transition restrictions.

### US5 - Delete an inventory item

As a catalogue operator, I want to permanently delete an item so that incorrectly or no longer
retained catalogue records can be removed.

- **AC5.1 (Event-driven):** When a client deletes an existing item, the inventory service shall
  permanently remove it and return `204 No Content` with an empty response body.
- **AC5.2 (Event-driven):** When a successfully deleted serial number is subsequently retrieved,
  the inventory service shall return `404 Not Found`.
- **AC5.3 (Unwanted):** If a client attempts to delete a non-existent item, then the inventory
  service shall return `404 Not Found`.

### US6 - Receive consistent API failures

As an API consumer, I want predictable error responses so that my integration can distinguish and
diagnose rejected requests.

- **AC6.1 (Unwanted):** If a REST operation returns a client or server error, then the inventory
  service shall return an RFC 9457 Problem Details response using `application/problem+json` and
  the documented problem fields.
- **AC6.2 (Unwanted):** If request validation fails, then the inventory service shall include
  machine-readable field violations in the Problem Details response.
- **AC6.3 (Unwanted):** If a request contains malformed JSON, then the inventory service shall
  return `400 Bad Request` without exposing an internal exception or stack trace.
- **AC6.4 (Unwanted):** If a write request uses an unsupported media type, then the inventory
  service shall return `415 Unsupported Media Type`.
- **AC6.5 (Unwanted):** If a client invokes an unsupported HTTP method on an inventory resource,
  including `PATCH`, then the inventory service shall return `405 Method Not Allowed`.
- **AC6.6 (Unwanted):** If an unexpected server failure occurs, then the inventory service shall
  return `500 Internal Server Error` without exposing secrets, database details, internal
  exceptions, or stack traces.

### US7 - Inspect and exercise the API contract

As an API consumer, I want executable API documentation so that I can discover and try the service
without reading its implementation.

- **AC7.1 (State-driven):** While the service is running, the inventory service shall expose a
  machine-readable OpenAPI document and an interactive Swagger UI at documented locations.
- **AC7.2 (Ubiquitous):** The OpenAPI contract shall describe every in-scope operation, request and
  response schema, status enum value, query parameter, success response, and documented error
  response.
- **AC7.3 (Ubiquitous):** The OpenAPI contract shall provide representative inventory-item and
  Problem Details examples that conform to the implemented schemas.

### US8 - Initialize and preserve the database schema

As a service operator, I want deterministic database migrations so that each environment starts
with a compatible, versioned schema.

- **AC8.1 (Event-driven):** When the service starts against a PostgreSQL 18.4 `rentflow` database
  with a pre-provisioned empty `inventory` schema, the inventory service shall apply Flyway
  migrations that create the required Inventory-owned objects before accepting inventory traffic.
- **AC8.2 (Event-driven):** When the service starts against a database with pending valid
  migrations, the inventory service shall apply each pending migration once and preserve existing
  inventory data.
- **AC8.3 (Unwanted):** If database connectivity, required role or schema availability, or schema
  migration fails during startup, then the inventory service shall not report itself ready to
  accept inventory traffic.
- **AC8.4 (Event-driven):** When the application process restarts while its database remains
  intact, the inventory service shall make previously committed inventory items available.
- **AC8.5 (Ubiquitous):** The inventory service shall use Flyway migrations as the sole mechanism
  for creating and evolving its tables, indexes, and constraints after the database role and
  schema have been provisioned.
- **AC8.6 (Ubiquitous):** The inventory service shall store its Flyway history and application
  objects only in the `inventory` schema and shall not create, alter, or drop objects owned by
  another RentFlow service.

### US9 - Run and observe the service in containers

As a developer or service operator, I want a production-style application image and a local
multi-container stack so that I can run the service consistently.

- **AC9.1 (Event-driven):** When the application image is built from a clean checkout, the
  container build shall compile the application in a build stage and produce a separate Java 25
  runtime image.
- **AC9.2 (State-driven):** While the application container is running, the operating-system
  process shall run as an unprivileged user.
- **AC9.3 (Event-driven):** When the documented Docker Compose command is run with Docker
  available, the container stack shall start the inventory service and a PostgreSQL 18.4 database
  named `rentflow` with no manually provisioned dependency.
- **AC9.4 (State-driven):** While the application and its required dependencies are healthy, the
  inventory service shall report `UP` from its documented liveness and readiness endpoints.
- **AC9.5 (Unwanted):** If the database becomes unavailable, then the inventory service shall
  report a non-ready state while keeping liveness independent of database availability.
- **AC9.6 (Event-driven):** When the Compose application container is restarted without deleting
  the database volume, the inventory service shall retain previously committed inventory items.
- **AC9.7 (Ubiquitous):** The containerized service shall obtain environment-specific database and
  server configuration from environment variables rather than baked-in production credentials.

### US10 - Onboard a contributor

As a contributor, I want accurate setup and usage documentation so that I can build, run, inspect,
and test the service from a clean checkout.

- **AC10.1 (Ubiquitous):** The repository README shall document prerequisites, the supported Java
  and PostgreSQL versions, local startup, Docker Compose startup and shutdown, configuration,
  database migrations, health endpoints, Swagger UI, and the OpenAPI document.
- **AC10.2 (Ubiquitous):** The repository README shall document the single Maven verification
  command and explain which automated checks it runs.
- **AC10.3 (Ubiquitous):** The repository README shall contain executable request examples for
  create, retrieve, list with filters and sorting, replace, and delete operations.
- **AC10.4 (Ubiquitous):** The repository shall include the Maven Wrapper so that a contributor can
  run the documented Maven lifecycle without installing a separate Maven distribution.

### US11 - Verify the service automatically

As a maintainer, I want one repeatable verification lifecycle so that regressions are detected
before changes are integrated.

- **AC11.1 (Event-driven):** When `mvn -B -ntp clean verify` is run in an environment satisfying the
  documented prerequisites, the build shall compile the service and execute formatting checks,
  unit tests, integration tests, and architecture tests.
- **AC11.2 (Ubiquitous):** The inventory service's unit tests shall run without a Spring
  application context and shall use Mockito for test doubles where collaboration behavior requires
  mocking.
- **AC11.3 (Ubiquitous):** The inventory service's persistence and full-stack integration tests
  shall use Testcontainers with a real PostgreSQL database and shall not require a manually
  provisioned test database.
- **AC11.4 (Ubiquitous):** The inventory service's integration tests shall verify the Flyway
  migration, successful CRUD behavior, collection pagination/filtering/sorting, validation
  failures, conflict handling, and not-found handling through externally observable results.
- **AC11.5 (Ubiquitous):** The inventory service's architecture tests shall enforce the prescribed
  configuration, controller, converter, DTO, service, repository, entity, and utility package
  boundaries.
- **AC11.6 (Ubiquitous):** The inventory service's automated tests shall verify that the exposed
  OpenAPI contract contains every in-scope inventory operation and remains consistent with the
  implemented API paths.

### US12 - Use the initial service without credentials

As a trusted internal API consumer, I want to call the initial catalogue API without credentials
so that authentication concerns do not block validation of the service skeleton.

- **AC12.1 (Event-driven):** When a client invokes an in-scope inventory operation without
  authentication credentials, the inventory service shall process the request according to the
  operation's functional rules.
- **AC12.2 (Ubiquitous):** The inventory service shall not expose user-registration, login, token,
  role, or permission-management capabilities in this feature.

## Out of scope

- API authentication, authorization, application users, application roles, permissions, and
  tenant isolation.
- Status-transition workflows or restrictions between defined status values.
- Partial updates with `PATCH`.
- Soft deletion, deletion recovery, history, and audit logging.
- Optimistic locking, ETags, and conditional requests for concurrent writes.
- Generated serial numbers or changing an item's serial number after creation.
- A separately managed equipment-type taxonomy.
- Full-text search, fuzzy matching, bulk operations, import, and export.
- Reservations, rentals, inspections, maintenance workflows, and their business rules.
- Business attributes beyond serial number, type, name, and status, including timestamps.
- A user interface other than Swagger UI.
- Production orchestration, cloud infrastructure, CI/CD pipelines, database backup, replication,
  and high-availability configuration.
- Cross-service database queries, foreign keys, and migrations against objects owned by other
  RentFlow services.
- API compatibility guarantees for versions beyond `/api/v1`.

## Resolved questions

1. **Access control:** The initial API is unauthenticated; authentication and authorization are out
   of scope. This is reflected in US12 and the Out of scope section. See `design.md` §2.2.
2. **Resource identity:** The manually assigned serial number is the immutable public resource
   identifier. This is reflected in the API paths, AC1.2, and AC4.2-AC4.3. See `design.md` §4.1
   and §5.1.
3. **Deletion semantics:** `DELETE` permanently removes a record; `RETIRED` remains an ordinary
   lifecycle status until deletion. This is reflected in US5. See `design.md` §3.5.
4. **Update semantics:** The initial API supports full replacement through `PUT` and does not
   support partial `PATCH` updates. This is reflected in US4, AC6.5, and the Out of scope section.
   See `design.md` §3.4 and §3.6.
5. **Collection retrieval:** Listing supports bounded pagination, sorting, exact status filtering,
   and exact type filtering. This is reflected in US3. See `design.md` §3.3.
6. **Status lifecycle:** The service validates membership in the status enum but does not enforce a
   transition matrix. This is reflected in AC4.6 and the Out of scope section. See `design.md`
   §5.2.
7. **Container delivery:** The service includes a production-style multi-stage application image
   and a Docker Compose stack containing the application and a local PostgreSQL service. This is
   reflected in US9. See `design.md` §9.
8. **PostgreSQL patch baseline:** The service targets PostgreSQL 18.4, the current stable 18.x
   patch release at design time. This is reflected in AC8.1 and AC9.3. See `design.md` §1.1,
   §9.2, and §11.2.
9. **Shared database namespace:** RentFlow services share the `rentflow` database, while Inventory
   owns the `inventory` schema with schema-local Flyway history. This is reflected in AC8.1,
   AC8.5, and AC8.6. See `design.md` §4.1, §4.2, and §8.1.
10. **Database role:** Inventory connects through a service-specific `inventory` role whose
    privileges are limited to its schema. See `design.md` §4.2, §8.1, and §9.2.
11. **Local database:** This repository's Compose stack provisions PostgreSQL locally for
    standalone development, while deployments may supply the shared database externally. See
    `design.md` §8.1 and §9.2.
12. **Compose service names:** Compose names the application service `inventory` and the shared
    database service `rentflow-postgres`; generic `app` and `db` service names are not used. See
    `design.md` §9.2 and §9.3.
13. **Container artifact names:** The executable JAR and runtime path use Inventory-specific
    names rather than `app.jar` or `/app`. See `design.md` §9.1 and §10.2.
14. **Planning scope:** This specification increment defines the complete implementation plan
    without mixing application-code changes into the spec change. Implementation starts with T1.
    See `tasks.md` §1 and §3.
15. **Increment strategy:** Implementation proceeds through vertical increments after the build
    and persistence foundations; implementation and its tests remain in the same commit. See
    `tasks.md` §1 and §2.
16. **Task granularity:** The implementation is decomposed into ten ordered tasks, each producing
    one independently verifiable commit. See `tasks.md` §2 and §3.
17. **Per-task verification:** Every implementation task runs `mvn -B -ntp verify` before its
    commit; final acceptance additionally runs the clean lifecycle. See `tasks.md` §1 and T10.
18. **Container smoke automation:** Container acceptance is encoded in the executable
    `scripts/container-smoke-test.sh` rather than being only a manual README checklist. See
    `design.md` §11.4 and `tasks.md` T9.
19. **Commit guidance:** Every task records its proposed Conventional Commit title. See
    `tasks.md` §1 and §3.

The rationale and implementation mapping for every resolution is recorded in the cited design
or task section.
