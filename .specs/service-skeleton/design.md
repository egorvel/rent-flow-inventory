# Inventory Service Skeleton Design

Status: Design and implementation tasks defined; ready for implementation.

This document implements the behavior in `requirements.md`. Numbered sections are stable targets
for task and requirements traceability.

## 1. Architecture

### 1.1 Runtime and platform baseline

The service is a single Maven module and a single deployable Spring Boot process. A layered
monolith is sufficient because the feature owns one aggregate and has no external service
integrations.

| Concern | Decision |
| --- | --- |
| Coordinates | `com.rentflow:rent-flow-inventory:0.0.1-SNAPSHOT` |
| Java | Java 25 source, target, build, and runtime |
| Build | Maven Wrapper 3.3.4 running Maven 3.9.16 |
| Framework | Spring Boot 4.1.0 and its dependency management |
| HTTP stack | Synchronous Spring MVC with embedded Tomcat |
| JSON | Boot-managed Jackson 3 |
| Persistence | Spring Data JPA with Hibernate and blocking JDBC |
| Database | PostgreSQL 18.4; shared database name `rentflow` |
| Schema management | Flyway SQL migrations |
| API documentation | Springdoc OpenAPI 3.0.3 with Swagger UI |
| Integration testing | Testcontainers 2.0.5, managed by Spring Boot |
| Architecture testing | ArchUnit 1.4.2 |
| Formatting | Spotless Maven Plugin 3.8.0 with palantir-java-format 2.96.0 |

These are exact stable versions verified against the maintainers' release sources on 2026-07-27.
Spring Boot-managed transitive dependencies remain controlled by the Boot dependency-management
baseline rather than being independently overridden. The exact Temurin `25.0.3_9` tags in section
9.1 are the newest Java 25 Noble Docker Official Image tags published at verification time; the
newer Temurin 25.0.4+7 binary release had not yet reached the official container manifest.

Spring MVC is used instead of WebFlux because JPA and the PostgreSQL JDBC driver are blocking.
Reactive request processing would add thread-handoff complexity without providing an end-to-end
non-blocking path. Spring Data REST is not used because the API requires explicit DTOs, stable
errors, an allowlisted query contract, and deliberate transaction handling.

The code uses Java records for transport types and ordinary Java classes for JPA entities. It does
not add Lombok, MapStruct, an interface-and-implementation pair for the service, or a generic base
controller. Those abstractions do not remove meaningful complexity for one resource.

### 1.2 Components and packages

The application root is `com.rentflow`. Production types use the project layout:

| Package | Primary types | Responsibility |
| --- | --- | --- |
| `com.rentflow` | `InventoryApplication` | Process entry point |
| `com.rentflow.config` | `OpenApiConfig` | OpenAPI metadata and documentation configuration |
| `com.rentflow.controller` | `InventoryController`, `ApiExceptionHandler` | HTTP binding and errors |
| `com.rentflow.converter` | `InventoryConverter` | Entity-to-response conversion |
| `com.rentflow.dto` | Request, response, page, problem, and violation records | Wire contract |
| `com.rentflow.service` | `InventoryService`, `InventorySortField`, exceptions | Use cases and transactions |
| `com.rentflow.repository` | `InventoryRepository`, `InventorySpecifications` | Persistence queries |
| `com.rentflow.model` | `InventoryItem`, `InventoryStatus` | Persisted domain state |
| `com.rentflow.util` | Initially empty | Shared helpers only if a later need is demonstrated |

The request flow is:

```text
HTTP request
  -> InventoryController
  -> InventoryService
  -> InventoryRepository
  -> PostgreSQL
  -> InventoryConverter
  -> response DTO
```

`InventoryController` binds and validates transport input. `InventoryService` owns existence
checks, state changes, and transaction boundaries. `InventoryRepository` owns database access.
`InventoryConverter` maps entities to immutable response records. JPA entities are never directly
serialized.

### 1.3 Dependency boundaries

ArchUnit enforces these application-package dependencies:

| Source layer | May depend on |
| --- | --- |
| `controller` | `converter`, `dto`, `model`, `service` |
| `converter` | `dto`, `model` |
| `dto` | `model` for the shared `InventoryStatus` enum |
| `service` | `model`, `repository` |
| `repository` | `model` |
| `model` | No other application layer |
| `config` | Framework APIs and `dto` only when documentation schemas require it |
| `util` | No other application layer |

Lower layers must not depend on controllers or DTOs. Controllers must not call repositories.
Package cycles are forbidden. Classes also follow suffix conventions: controllers end in
`Controller`, converters in `Converter`, services in `Service`, and Spring Data repository
interfaces in `Repository`.

### 1.4 Transaction boundaries

`InventoryService` is a concrete Spring `@Service`. Read operations use
`@Transactional(readOnly = true)`. Create, replace, and delete operations use `@Transactional`.
Create uses `saveAndFlush` so a primary-key conflict is observed inside the use case and translated
to the stable conflict response. Delete loads the entity before deleting it so a missing serial
number produces the required `404`.

`spring.jpa.open-in-view` is disabled. The entity has only eager scalar fields, so it can be mapped
to a response immediately after the service call without lazy-loading behavior.

## 2. HTTP foundations

### 2.1 Paths, media types, and versioning

All business operations are rooted at `/api/v1/inventory`. Paths and serial-number matching are
case-sensitive. Version `v1` is part of the path; there is no header-based version negotiation.

Successful representations use `application/json`. `POST` and `PUT` require an
`application/json` request body. Errors use `application/problem+json`. A successful
`DELETE` has no body and therefore no response content type.

Jackson is configured to reject unknown JSON properties. This makes misspelled attributes a
`400 Bad Request` instead of silently discarding client input. JSON property names use lower
camel case. Entity implementation details and database column names do not appear in the wire
contract.

### 2.2 Unauthenticated boundary

The service does not include Spring Security or any authentication, user, token, session, role, or
permission component. Inventory, Swagger UI, OpenAPI, and health endpoints are callable without
credentials. CORS remains disabled by default; unauthenticated does not imply unrestricted
cross-origin browser access.

This boundary is deliberate for the skeleton. Authentication can be introduced later without
changing persistence or service contracts.

### 2.3 Resource representations

`InventoryItemDTO` is the complete representation accepted by both `POST` and `PUT`:

```json
{
  "serialNumber": "DRILL-001",
  "type": "Industrial drill",
  "name": "Bosch GBH 8-45 DV",
  "status": "AVAILABLE"
}
```

`InventoryItemDTO` has the same four properties and no internal identifier:

```json
{
  "serialNumber": "DRILL-001",
  "type": "Industrial drill",
  "name": "Bosch GBH 8-45 DV",
  "status": "AVAILABLE"
}
```

The request record's compact constructor strips leading and trailing Unicode whitespace from
`type` and `name` before Bean Validation runs. It does not alter `serialNumber`. Response data is
therefore the normalized persisted representation.

`InventoryPageResponse` is a custom record rather than Spring Data's `Page` JSON shape:

```json
{
  "items": [
    {
      "serialNumber": "DRILL-001",
      "type": "Industrial drill",
      "name": "Bosch GBH 8-45 DV",
      "status": "AVAILABLE"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`items` is never null. `totalElements` and `totalPages` describe the filtered result before page
slicing. No transport record accepts or emits database-only data.

## 3. REST API contract

### 3.1 Create item

`POST /api/v1/inventory` accepts `InventoryItemDTO`.

| Result | Status | Body and headers |
| --- | --- | --- |
| Created | `201 Created` | Response DTO and `Location: /api/v1/inventory/{serialNumber}` |
| Invalid representation | `400 Bad Request` | Validation or malformed-JSON problem |
| Duplicate serial number | `409 Conflict` | Item-already-exists problem |
| Unsupported request media type | `415 Unsupported Media Type` | Media-type problem |

The service first performs a readable `existsById` check. It also translates a
`DataIntegrityViolationException` from `saveAndFlush` to the same conflict result, closing the race
between the existence check and insert. A failed create transaction does not modify the existing
row.

The controller builds `Location` from the current request URI and the validated serial number.
The serial-number alphabet is URI path-segment safe, so no lossy transformation is needed.

### 3.2 Retrieve item

`GET /api/v1/inventory/{serialNumber}` returns:

| Result | Status | Body |
| --- | --- | --- |
| Found | `200 OK` | `InventoryItemDTO` |
| Invalid serial-number syntax | `400 Bad Request` | Validation problem |
| Not found | `404 Not Found` | Item-not-found problem |

Repository lookup uses the case-sensitive natural key exactly as supplied in the path.

### 3.3 List, filter, and sort items

`GET /api/v1/inventory` accepts only these query parameters:

| Parameter | Type | Default | Rules |
| --- | --- | --- | --- |
| `page` | Integer | `0` | Minimum `0` |
| `size` | Integer | `20` | Minimum `1`, maximum `100` |
| `status` | `InventoryStatus` | None | Exact uppercase enum value |
| `type` | String | None | Strip whitespace, 1-100 chars, case-insensitive exact match |
| `sort` | String enum | `serialNumber` | `serialNumber`, `type`, `name`, or `status` |
| `direction` | String enum | `asc` | `asc` or `desc`, parsed case-insensitively |

The controller rejects unknown query parameter names and repeated values before executing the
query. Every declared parameter is also strictly validated. Invalid numeric conversion, bounds,
enums, blank filters, sort fields, or directions produce a validation problem with
`400 Bad Request`. The allowlist is a private constant owned by `InventoryController`; this does
not require a global interceptor for one collection endpoint.

The optional `status` and `type` predicates are combined with logical `AND`. Type filtering uses
`lower(type) = lower(:type)` against the stripped filter; it is not substring or fuzzy search.
Stored type capitalization is preserved.

API sort names are mapped through an allowlist to entity attributes. Client-provided JPA property
paths are never passed through. Sorting by `status` uses lexicographic enum-name order. Every sort
other than `serialNumber` adds `serialNumber ASC` as a tie-breaker. Sorting by `serialNumber`
uses only the requested direction because the key is unique.

A page beyond the last available page is valid and returns an empty `items` list with the
requested `page`, requested `size`, and accurate totals. The endpoint always returns `200 OK` for
a valid collection request, including an empty catalogue.

### 3.4 Replace item

`PUT /api/v1/inventory/{serialNumber}` accepts a complete `InventoryItemDTO`. The body
`serialNumber` is required and must equal the path value using case-sensitive comparison.

| Result | Status | Body |
| --- | --- | --- |
| Replaced | `200 OK` | Updated `InventoryItemDTO` |
| Invalid body or serial mismatch | `400 Bad Request` | Validation problem |
| Item does not exist | `404 Not Found` | Item-not-found problem |
| Unsupported request media type | `415 Unsupported Media Type` | Media-type problem |

The operation is update-only and never performs an upsert. After loading the entity, the service
calls a domain method that replaces `type`, `name`, and `status`; there is no serial-number setter.
All six defined statuses are accepted regardless of the previous status.

### 3.5 Delete item

`DELETE /api/v1/inventory/{serialNumber}` permanently removes a row.

| Result | Status | Body |
| --- | --- | --- |
| Deleted | `204 No Content` | Empty |
| Invalid serial-number syntax | `400 Bad Request` | Validation problem |
| Item does not exist | `404 Not Found` | Item-not-found problem |

Deletion applies equally to every status, including `RETIRED`. No tombstone, audit row, or recovery
record is written.

### 3.6 Unsupported methods

There is no `PATCH` mapping. `PATCH`, and any other method not supported for the selected inventory
resource, is translated to `405 Method Not Allowed` with an RFC 9457 body. Spring MVC supplies the
standards-based `Allow` header for mapped resources.

## 4. Persistence design

### 4.1 Entity and relational schema

`InventoryItem` maps explicitly to `inventory.inventory_items`. RentFlow services share the
`rentflow` database, but this service owns only the `inventory` schema. The confirmed immutable
serial number is both the JPA identifier and the database primary key; no hidden UUID is added.
`@Table(name = "inventory_items", schema = "inventory")` keeps generated SQL independent of the
connection's ambient search path.

| Java field | Column | SQL type | Constraints |
| --- | --- | --- | --- |
| `serialNumber` | `serial_number` | `varchar(64)` | Primary key, immutable, serial pattern |
| `type` | `type` | `varchar(100)` | Not null, nonblank, stripped |
| `name` | `name` | `varchar(200)` | Not null, nonblank, stripped |
| `status` | `status` | `varchar(32)` | Not null, status allowlist |

`InventoryStatus` is stored with `@Enumerated(EnumType.STRING)`. The entity has a protected
no-argument constructor for JPA, a creation constructor requiring all fields, getters, and a
`replaceDetails(type, name, status)` method. It has no generated-value annotation and no public
serial-number mutator. Equality and hash code use the assigned immutable serial number.

The first migration is `src/main/resources/db/migration/V1__create_inventory_items.sql`. The
database and `inventory` login role and schema already exist before Flyway starts. The migration
creates only Inventory-owned objects:

```sql
CREATE TABLE inventory.inventory_items (
    serial_number varchar(64) PRIMARY KEY,
    type varchar(100) NOT NULL,
    name varchar(200) NOT NULL,
    status varchar(32) NOT NULL,
    CONSTRAINT chk_inventory_serial_number
        CHECK (serial_number ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT chk_inventory_type
        CHECK (
            type <> ''
            AND type !~ '^[[:space:]]'
            AND type !~ '[[:space:]]$'
        ),
    CONSTRAINT chk_inventory_name
        CHECK (
            name <> ''
            AND name !~ '^[[:space:]]'
            AND name !~ '[[:space:]]$'
        ),
    CONSTRAINT chk_inventory_status
        CHECK (status IN (
            'AVAILABLE',
            'RESERVED',
            'RENTED',
            'INSPECTION_REQUIRED',
            'UNDER_MAINTENANCE',
            'RETIRED'
        ))
);

CREATE INDEX idx_inventory_items_status
    ON inventory.inventory_items (status);

CREATE INDEX idx_inventory_items_type_lower
    ON inventory.inventory_items (lower(type));
```

The primary key provides the serial lookup and sort index. Separate status and normalized-type
indexes serve the two optional filters without over-indexing this initial table. The migration
does not create the database, login role, service schema, extensions, or objects in `public` or
another service's schema.

### 4.2 Migration and JPA startup

The runtime includes `spring-boot-starter-flyway`,
`org.flywaydb:flyway-database-postgresql`, and the PostgreSQL JDBC driver. Flyway uses the primary
data source and default `classpath:db/migration` location. Its default and managed schema are both
`inventory`, `create-schemas` is disabled, and its default history table is therefore
`inventory.flyway_schema_history`. This isolates Inventory's migration versions and checksums from
every other service using `rentflow`. Migrations are append-only and contain Inventory-owned
object changes only; this feature has no seed data.

The application configures:

```yaml
spring:
  flyway:
    create-schemas: false
    default-schema: inventory
    schemas: inventory
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        default_schema: inventory
  sql:
    init:
      mode: never
```

Hibernate validates entity-to-schema compatibility but cannot create, update, or drop schema.
`schema.sql`, `data.sql`, and `import.sql` are absent. Flyway runs before JPA initialization; a
connection, missing `inventory` schema, insufficient privilege, migration, checksum, or
schema-validation failure aborts application startup. Production infrastructure provisions the
`rentflow` database, the `inventory` login role, and the `inventory` schema owned by that role.
The local Compose bootstrap in section 9.2 provides the same prerequisites without creating
application tables.

### 4.3 Repository and query implementation

`InventoryRepository` is declared as:

```java
interface InventoryRepository
        extends JpaRepository<InventoryItem, String>,
                JpaSpecificationExecutor<InventoryItem> {}
```

`InventorySpecifications` is a package-private utility in `repository`. It creates optional
status-equality and case-insensitive type-equality specifications. It returns conjunctions for
absent filters rather than embedding four query-method combinations in the service.

`InventorySortField` is a service-layer enum whose values contain one public API name and one
entity attribute name. Its parser rejects all other values before the service builds `PageRequest`.
The service never passes arbitrary request strings to Spring Data property resolution. Spring
Data's `Page` remains inside the service/converter boundary and is mapped to the custom page
response.

### 4.4 Persistence and concurrency behavior

PostgreSQL is the authority for serial uniqueness. The readable pre-check improves the normal
duplicate path; the primary key handles concurrent creates. There is no `@Version`, ETag, or
conditional update because concurrent-write protection is explicitly out of scope.

All write failures roll back their transaction. Replace mutates only a managed entity loaded in
the same transaction. Delete loads and removes in the same transaction. Committed records survive
application process restarts because no application lifecycle operation clears the schema.

## 5. Validation and invariants

### 5.1 Field and query validation

Validation annotations use explicit messages so the public violation text does not change with a
dependency's default message bundle.

| Input | Normalization | Validation |
| --- | --- | --- |
| Body `serialNumber` | None | Required; regex `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` |
| Path `serialNumber` | None | Same regex as body |
| Body `type` | Unicode `strip()` | Required, nonblank, maximum 100 characters after stripping |
| Body `name` | Unicode `strip()` | Required, nonblank, maximum 200 characters after stripping |
| Body `status` | None | Required member of `InventoryStatus` |
| Query `type` | Unicode `strip()` | If present, nonblank and maximum 100 characters |
| Query `page` | Integer conversion | At least 0 |
| Query `size` | Integer conversion | From 1 through 100 |
| Query `status` | Enum conversion | Exact uppercase enum name |
| Query `sort` | Allowlist conversion | One of the four public sort names |
| Query `direction` | Lowercase normalization | `asc` or `desc` |

Serial numbers are preserved and compared case-sensitively. Consequently, `DRILL-001` and
`drill-001` are distinct valid identifiers. Leading or trailing whitespace is rejected rather
than stripped. `type` and `name` preserve internal whitespace and capitalization after their
outer whitespace is removed.

The same serial constraint is represented in request DTO annotations, path validation, OpenAPI,
the entity column length, and the Flyway check constraint. The same text lengths are represented
in DTO annotations, OpenAPI, entity columns, and migration columns.

`InventoryController` is annotated with `@Validated`; request bodies use `@Valid`, and path/query
arguments carry their applicable constraints. Conditional rules such as path/body equality and
the query-name allowlist are explicit controller/service checks rather than custom annotations.

### 5.2 Business invariants

The following invariants hold in the service and database:

1. Exactly one row may have a given case-sensitive serial number.
2. The serial number never changes after creation.
3. Type and name are persisted in stripped, nonblank form.
4. Status is always one of the six defined enum values.
5. A valid replacement may move between any two statuses.
6. A missing item is never created by `PUT`.
7. Deletion is permanent.

No status transition matrix is present in the entity or service. `RETIRED` is a persisted business
status, not a soft-deletion marker.

### 5.3 Validation ordering and atomicity

Transport syntax and Bean Validation run before service mutation. `PUT` path/body serial equality
is checked before loading or changing the entity. Create duplicate detection occurs after request
validation. A rejected create or replacement leaves database state unchanged.

An invalid enum token in JSON is mapped to a field violation for `status`; malformed JSON syntax is
mapped to the separate malformed-JSON problem. This keeps invalid domain values distinguishable
from unreadable payloads.

## 6. Error contract

### 6.1 RFC 9457 representation

`ProblemResponse` is an immutable DTO with the five standard RFC 9457 members and RentFlow
extensions:

```json
{
  "type": "urn:rentflow:problem:validation-failed",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more request values are invalid.",
  "instance": "/api/v1/inventory",
  "code": "VALIDATION_FAILED",
  "violations": [
    {
      "field": "name",
      "message": "must not be blank"
    }
  ]
}
```

`type`, `title`, `status`, `detail`, `instance`, and `code` are always present. `violations` is
present only for validation problems and is sorted by field then message for deterministic output.
`instance` is the request path without host information. Error responses always set
`Content-Type: application/problem+json`.

The `violations` component is annotated for non-empty JSON inclusion, so non-validation problems
omit the member instead of returning null or an empty list.

### 6.2 Problem catalogue

| Condition | Status | Type | Code | Title |
| --- | --- | --- | --- | --- |
| Body, path, or query validation | `400` | `urn:rentflow:problem:validation-failed` | `VALIDATION_FAILED` | `Request validation failed` |
| Unreadable or malformed JSON | `400` | `urn:rentflow:problem:malformed-json` | `MALFORMED_JSON` | `Malformed JSON` |
| Inventory item absent | `404` | `urn:rentflow:problem:inventory-item-not-found` | `INVENTORY_ITEM_NOT_FOUND` | `Inventory item not found` |
| Serial already exists | `409` | `urn:rentflow:problem:inventory-item-already-exists` | `INVENTORY_ITEM_ALREADY_EXISTS` | `Inventory item already exists` |
| HTTP method unsupported | `405` | `urn:rentflow:problem:method-not-allowed` | `METHOD_NOT_ALLOWED` | `Method not allowed` |
| Response media unavailable | `406` | `urn:rentflow:problem:not-acceptable` | `NOT_ACCEPTABLE` | `Not acceptable` |
| Request media unsupported | `415` | `urn:rentflow:problem:unsupported-media-type` | `UNSUPPORTED_MEDIA_TYPE` | `Unsupported media type` |
| Unmapped request path | `404` | `urn:rentflow:problem:resource-not-found` | `RESOURCE_NOT_FOUND` | `Resource not found` |
| Other MVC client error | Original `4xx` | `urn:rentflow:problem:http-error` | `HTTP_ERROR` | `Request failed` |
| Unexpected server failure | `500` | `urn:rentflow:problem:internal-error` | `INTERNAL_ERROR` | `Internal server error` |

Titles and codes are stable. Validation details are generic, while violations identify rejected
fields. Item-not-found detail is `Inventory item '{serialNumber}' was not found.` and conflict
detail is `Inventory item '{serialNumber}' already exists.` The internal-error detail is always
`An unexpected error occurred.`.

### 6.3 Exception mapping

`ApiExceptionHandler` is a `@RestControllerAdvice` and maps:

- `MethodArgumentNotValidException`, method-validation exceptions, constraint violations, and
  request parameter conversion failures to validation problems.
- Jackson invalid-enum format failures to a `status` field violation.
- Other unreadable-message failures to malformed-JSON problems.
- `InventoryItemNotFoundException` to the item-not-found problem.
- `InventoryItemAlreadyExistsException` to the conflict problem.
- `HttpRequestMethodNotSupportedException` to the method problem.
- `HttpMediaTypeNotAcceptableException` to the not-acceptable problem.
- `HttpMediaTypeNotSupportedException` to the media-type problem.
- `NoResourceFoundException` to the resource-not-found problem.
- Any unhandled exception to the internal-error problem.

The advice extends Spring MVC's `ResponseEntityExceptionHandler`. Its shared response path converts
any remaining framework `ErrorResponse` to RFC 9457 while preserving the framework HTTP status;
the generic type and code in section 6.2 are used when no dedicated catalogue entry exists. No MVC
error falls back to an HTML or legacy error-map response.

The unexpected-error handler logs the exception server-side with method and request path but does
not log request bodies or credentials. No exception class, stack trace, SQL, constraint name,
database URL, username, password, or environment value is serialized.

## 7. OpenAPI and Swagger

### 7.1 Generation and endpoints

`org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3` generates OpenAPI from the MVC contract.
The machine-readable JSON is available at `/v3/api-docs`; Swagger UI is available at
`/swagger-ui.html`. Springdoc scans only `/api/v1/**`, so Actuator endpoints are not mixed into the
business API document.

`OpenApiConfig` declares:

- Title: `RentFlow Inventory API`.
- Version: `v1`.
- Inventory tag and service description.
- No security scheme, matching the unauthenticated boundary.

### 7.2 Operation and schema documentation

The controller uses stable operation IDs:

| Operation | Operation ID |
| --- | --- |
| Create | `createInventoryItem` |
| List | `listInventoryItems` |
| Retrieve | `getInventoryItem` |
| Replace | `replaceInventoryItem` |
| Delete | `deleteInventoryItem` |

DTO schema annotations describe required fields, sizes, serial pattern, all status values, page
fields, filters, sorting, success responses, and every problem response from section 6.2. The
examples in sections 2.3 and 6.1 are also represented in OpenAPI. `PATCH` is absent.

### 7.3 Contract consistency

An integration test requests `/v3/api-docs` and asserts:

- All five paths and operation IDs exist.
- No `PATCH` operation exists.
- Request and response schemas expose exactly the intended business properties.
- All six status values are present.
- Page/filter/sort parameter constraints and defaults are present.
- Success and error status codes are documented.
- Inventory and problem examples conform to their schemas.

This test fails if annotations, paths, or documented status values drift from the runtime API.

## 8. Configuration and health

### 8.1 Application configuration

`src/main/resources/application.yaml` contains non-secret defaults and framework invariants:

```yaml
spring:
  application:
    name: rent-flow-inventory
  jackson:
    deserialization:
      fail-on-unknown-properties: true
  flyway:
    create-schemas: false
    default-schema: inventory
    schemas: inventory
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        default_schema: inventory
  sql:
    init:
      mode: never

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
        add-additional-paths: true
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db
      show-details: never
```

The application does not define database credentials or a production database URL. Deployments
provide standard Boot environment variables:

| Variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | JDBC URL for the shared PostgreSQL database `rentflow` |
| `SPRING_DATASOURCE_USERNAME` | Service-specific database user; `inventory` by default locally |
| `SPRING_DATASOURCE_PASSWORD` | Password for the service-specific database user |
| `SERVER_PORT` | Optional HTTP port; defaults to `8080` |
| `JAVA_TOOL_OPTIONS` | Optional JVM runtime flags |

The fixed `inventory` schema is an application ownership invariant, not an environment-specific
setting. Deployments provision the `rentflow` database, `inventory` role, and role-owned
`inventory` schema before starting the process. Tests use a Testcontainers bootstrap script and
dynamic datasource properties instead of production variables.

### 8.2 Liveness and readiness

Actuator exposes only health over HTTP. Health details remain hidden.

| Purpose | Paths | Healthy response |
| --- | --- | --- |
| Liveness | `/actuator/health/liveness`, `/livez` | `200` with `status: UP` |
| Readiness | `/actuator/health/readiness`, `/readyz` | `200` with `status: UP` |

Liveness contains only `livenessState`; it does not depend on PostgreSQL. Readiness contains
`readinessState` and the auto-configured `db` indicator. Database loss therefore makes readiness
`DOWN` with HTTP `503` while liveness remains `UP`.

### 8.3 Startup failures

Flyway and JPA schema validation complete during application context startup. Until they complete,
readiness refuses traffic. A database connection, missing service schema, insufficient role
privilege, migration, checksum, or schema-validation failure aborts startup rather than exposing a
partially initialized service.

## 9. Container design

### 9.1 Application image

The root `Dockerfile` uses these stages:

1. `eclipse-temurin:25.0.3_9-jdk-noble` copies the Maven Wrapper and `pom.xml`, resolves
   dependencies, copies sources, and runs `./mvnw -B -ntp package`.
2. `eclipse-temurin:25.0.3_9-jre-noble` contains only the executable `target/inventory.jar`, Java
   runtime, CA certificates, and the small HTTP client needed for the health check.

The package lifecycle runs unit tests and does not use a skip flag. Full integration and
architecture verification remains the responsibility of `mvn verify`, because Testcontainers
requires a Docker daemon that is not mounted into the image build.

The runtime stage:

- Creates an `inventory` group and user with ID `10001`.
- Owns only `/opt/inventory` and runs with `USER 10001:10001`.
- Exposes port `8080`.
- Uses `java -jar /opt/inventory/inventory.jar`; optional JVM flags come from
  `JAVA_TOOL_OPTIONS`.
- Defines a health check against `http://localhost:8080/readyz`.
- Contains no source, Maven cache, Maven executable, compiler, or build-time credentials.

`.dockerignore` excludes `.git`, `.idea`, `.specs`, `target`, local environment files, and other
non-build inputs.

### 9.2 Docker Compose topology

`compose.yaml` defines:

| Service | Image/build | Responsibilities |
| --- | --- | --- |
| `rentflow-postgres` | `postgres:18.4-alpine` | Local `rentflow` database, bootstrap, and `pg_isready` health check |
| `inventory` | Local `Dockerfile` | Inventory API and `/readyz` health check |

The database uses development-only defaults: database `rentflow`, bootstrap administrator
`rentflow_admin` / `rentflow-admin-local`, and application role `inventory` /
`inventory-local`. The corresponding override inputs are `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`, `INVENTORY_DB_USER`, and `INVENTORY_DB_PASSWORD`. These are explicitly local
defaults, not production credentials.

An executable `docker/postgres/init-inventory.sh` is mounted read-only under
`/docker-entrypoint-initdb.d`. On first initialization of an empty volume, it connects with the
bootstrap administrator and uses identifier-safe SQL quoting to create the service login role and
the `inventory` schema owned by that role. It does not create Inventory tables or Flyway history,
does not grant access to another service schema, and does not print either password. Production
deployments perform this provisioning outside the application repository.

The `inventory` service maps
`jdbc:postgresql://rentflow-postgres:5432/${POSTGRES_DB:-rentflow}`,
`${INVENTORY_DB_USER:-inventory}`, and `${INVENTORY_DB_PASSWORD:-inventory-local}` to the three
`SPRING_DATASOURCE_*` variables. It depends on `rentflow-postgres` with
`condition: service_healthy`. Port `8080` is published for Inventory; the database port is
published as `127.0.0.1:${POSTGRES_PORT:-5432}:5432` so a host-run application can reuse the
Compose database without exposing PostgreSQL on every host interface. The named volume
`rentflow-postgres-data` mounts at `/var/lib/postgresql`, the PostgreSQL 18.4 official-image
persistence root. Compose does not set fixed `container_name` values or require an external
network, so this repository remains independently runnable.

### 9.3 Container lifecycle behavior

`docker compose up --build` starts PostgreSQL, waits for `pg_isready`, starts the application,
applies Flyway migrations, and marks the application healthy only when readiness is `UP`.
Restarting only `inventory` leaves the named database volume intact. `docker compose down`
preserves the volume; `docker compose down --volumes` is the documented destructive reset and is
required when changing first-run local bootstrap credentials.

Stopping `rentflow-postgres` makes the Inventory health check fail through readiness but does not
change Inventory liveness. Starting `rentflow-postgres` again allows the pool and readiness check
to recover without recreating the `inventory` container.

## 10. Build and contributor experience

### 10.1 Maven dependencies

The Spring Boot parent manages all supported dependency versions except explicitly listed
third-party tools.

Main dependencies:

- `spring-boot-starter-webmvc`
- `spring-boot-starter-validation`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-flyway`
- `spring-boot-starter-actuator`
- `org.flywaydb:flyway-database-postgresql`
- `org.postgresql:postgresql` with runtime scope
- `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3`

Test dependencies use Boot 4's focused test modules:

- `spring-boot-starter-webmvc-test`
- `spring-boot-starter-validation-test`
- `spring-boot-starter-data-jpa-test`
- `spring-boot-starter-flyway-test`
- `org.testcontainers:testcontainers-postgresql`
- `org.testcontainers:testcontainers-junit-jupiter`
- `com.tngtech.archunit:archunit-junit5:1.4.2`

No H2 database is present. No Spring Security dependency is present.

### 10.2 Maven lifecycle

The POM configures:

- Compiler release 25 and parameter metadata for Springdoc.
- Spring Boot executable-jar repackaging with final name `inventory.jar`.
- Surefire for `*Test` unit and architecture tests.
- Failsafe `integration-test` and `verify` goals for `*IT`.
- Spotless Maven Plugin 3.8.0 `check` in `verify`, using
  `<palantirJavaFormat><version>2.96.0</version><style>PALANTIR</style></palantirJavaFormat>`.
- Maven Enforcer rules requiring Java 25 and supported Maven 3.9.x.

No google-java-format dependency or formatter step is present. `mvn spotless:apply` applies the
same pinned Palantir formatter used by verification.

`mvn -B -ntp clean verify` is the single required verification command. It compiles production and test
code, runs unit tests, starts PostgreSQL Testcontainers for integration tests, runs architecture
tests, and checks formatting. Docker is therefore a documented prerequisite for the full
verification lifecycle.

### 10.3 Maven Wrapper

Maven Wrapper 3.3.4 uses its script-only distribution and downloads Maven 3.9.16. The wrapper
properties include the Maven distribution SHA-256 checksum. The repository commits `mvnw`,
`mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties`; no wrapper JAR is required.

Both `mvn -B -ntp clean verify` and `./mvnw -B -ntp verify` run the same lifecycle. Project invariants
continue to use the former when Maven is installed.

### 10.4 README

The root `README.md` contains:

1. Service purpose and inventory fields/statuses.
2. Java 25, Docker, and PostgreSQL 18.4 prerequisites.
3. Maven Wrapper and installed-Maven build commands.
4. Local database and application startup using environment variables.
5. Docker Compose startup, health check, shutdown, and destructive volume reset.
6. The shared `rentflow` database, Inventory-owned schema and role, and local-only bootstrap
   credentials.
7. Configuration variable table without production credentials.
8. Flyway migration location, schema-local history, and append-only rule.
9. Swagger UI, OpenAPI, liveness, and readiness URLs.
10. Executable `curl` examples for create, get, filtered/sorted list, replace, and delete.
11. The `mvn -B -ntp clean verify` command and its unit, integration, architecture, and formatting
    checks.

Commands are written from the repository root and use the exact paths and payloads in this design.

## 11. Verification design

### 11.1 Unit tests

Unit tests use JUnit Jupiter, AssertJ, and Mockito without a Spring context.

| Test | Focus |
| --- | --- |
| `InventoryServiceTest` | Create, duplicate handling, get, replace, delete, and missing-item paths |
| `InventoryConverterTest` | Exact entity-to-response and page-envelope mapping |
| `InventoryItemTest` | Immutable serial and replaceable mutable fields |
| `InventoryItemDTOTest` | Type/name normalization and validation profile |
| `ApiExceptionHandlerTest` | Framework and unexpected failures mapped without a Spring context |

Mockito mocks only the repository. Tests assert returned values, repository calls, state changes,
and non-interaction on rejected operations. Assertions are not weakened to accommodate an
implementation.

### 11.2 Integration tests

Integration tests end in `IT` and run under Failsafe. A shared test configuration declares a
`PostgreSQLContainer` with `postgres:18.4-alpine`, database `rentflow`, bootstrap administrator
`rentflow_admin`, and `@Container`.
`src/test/resources/testcontainers/init-inventory.sql` creates the test `inventory` login role
and its `inventory` schema. `@DynamicPropertySource` supplies the container JDBC URL and the
restricted Inventory credentials to Spring, so Flyway, Hibernate, and API tests never run as the
bootstrap administrator. No manually provisioned database or H2 fallback is allowed.

Full API tests use `@SpringBootTest` and MockMvc with the real repository, Hibernate, Flyway,
Jackson, validation, exception advice, and PostgreSQL. Database state is explicitly cleared
between tests through the Inventory-owned table; tests do not depend on execution order.

| Test | Required observations |
| --- | --- |
| `MigrationIT` | V1 applies in `inventory`, history is schema-local, no `public` objects are created, constraints reject invalid rows |
| `InventoryIT` | CRUD statuses/bodies, `Location`, persistence, delete, no upsert; body/path/query validation, malformed JSON, media type, method errors; query defaults, bounds, empty pages, filters, combined filters, and all sorts; sequential and concurrent duplicate creates return stable `409` without changing the original |
| `OpenApiIT` | Section 7.3 contract assertions |

The query suite includes duplicate sort values to prove the serial-number tie-breaker. It also
proves serial case sensitivity and type-filter case insensitivity.

### 11.3 Architecture tests

`ArchitectureTest` runs as a unit test and imports production classes under `com.rentflow` with
`ImportOption.DoNotIncludeTests`. It enforces section 1.3, package placement, naming suffixes, no
cycles, controller-to-repository prohibition, and the absence of layer violations. It also
verifies controllers are the only application types annotated with Spring MVC mapping
annotations.

### 11.4 Container smoke verification

The executable `scripts/container-smoke-test.sh` verifies container acceptance from a clean
checkout:

1. Validate Compose configuration and build the image.
2. Start the stack and wait for both health checks.
3. Create and retrieve an item through published port `8080`.
4. Restart the `inventory` container and retrieve the same item.
5. Stop `rentflow-postgres` and observe readiness fail while liveness remains `UP`.
6. Start `rentflow-postgres` and observe readiness recover.
7. Shut down without deleting the volume, start again, and retrieve the item.

These checks validate image construction, non-root execution, migration startup, health semantics,
and volume persistence. The script uses strict shell error handling, bounded health polling, and a
cleanup trap; it must not print environment secrets.

## 12. Edge cases

| Case | Result |
| --- | --- |
| `DRILL-001` and `drill-001` | Distinct serial numbers and resources |
| Serial with a slash, space, Unicode, or more than 64 chars | `400` validation problem |
| Type or name containing only whitespace | `400` validation problem after stripping |
| Type filter `" drill "` | Stripped and matched case-insensitively to exact type `drill` |
| Unknown or repeated query parameter | `400` validation problem |
| Type filter with no matches | `200` with an empty page |
| Page past the last page | `200` with requested page metadata and empty `items` |
| Two items with equal primary sort value | Ordered by `serialNumber ASC` tie-breaker |
| `PUT` body serial differs only by case | `400`; case-sensitive identity mismatch |
| `PUT` targets a missing item | `404`; no row is created |
| Any valid status replacement | Accepted without transition checks |
| Delete a `RETIRED` item | Permanent `204` deletion |
| Concurrent create of the same serial | One insert; loser receives `409` |
| Flyway checksum mismatch | Application startup fails |
| `inventory` schema missing or not owned by the service role | Application startup fails |
| Another service schema already exists in `rentflow` | Inventory startup and migrations leave it unchanged |
| Database lost after startup | Readiness `503`, liveness `200`, API failures sanitized |

## 13. Requirements traceability

| Acceptance criteria | Design coverage |
| --- | --- |
| AC1.1-AC1.4 | Sections 2.3, 3.1, 4.3-4.4, 5.1, and 6 |
| AC2.1-AC2.2 | Sections 3.2, 4.3, and 6 |
| AC3.1-AC3.8 | Sections 2.3, 3.3, 4.3, 5.1, and 12 |
| AC4.1-AC4.6 | Sections 3.4, 4.4, 5.1-5.3, and 12 |
| AC5.1-AC5.3 | Sections 3.5, 4.4, 5.2, and 12 |
| AC6.1-AC6.6 | Sections 2.1, 3.6, and 6 |
| AC7.1-AC7.3 | Section 7 |
| AC8.1-AC8.6 | Sections 4.1-4.2, 8.1, 8.3, and 11.2 |
| AC9.1-AC9.7 | Sections 8.1-8.2, 9, and 11.4 |
| AC10.1-AC10.4 | Sections 7.1, 8.1-8.2, and 10.3-10.4 |
| AC11.1-AC11.6 | Sections 10.1-10.2 and 11 |
| AC12.1-AC12.2 | Section 2.2 |

Every acceptance criterion has an implementation section and an observable verification path.
Task DoD traceability will be added in `tasks.md`.

## 14. Primary references

- [Spring Boot 4.1 system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot build systems and focused starters](https://docs.spring.io/spring-boot/reference/using/build-systems.html)
- [Spring Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot Flyway initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Spring Boot health probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)
- [Spring Boot Testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Springdoc 3 documentation](https://springdoc.org/v4/index.html)
- [Flyway PostgreSQL module](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)
- [PostgreSQL 18.4 release notes](https://www.postgresql.org/docs/release/18.4/)
- [PostgreSQL official container](https://hub.docker.com/_/postgres)
- [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/)
- [Apache Maven release history](https://maven.apache.org/docs/history.html)
- [Spotless releases](https://github.com/diffplug/spotless/releases)
- [Spotless Maven palantir-java-format configuration](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md#palantir-java-format)
- [palantir-java-format releases](https://github.com/palantir/palantir-java-format/releases)
- [Testcontainers Java releases](https://github.com/testcontainers/testcontainers-java/releases)
- [ArchUnit releases](https://github.com/TNG/ArchUnit/releases)
- [Eclipse Temurin official image manifest](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin)
