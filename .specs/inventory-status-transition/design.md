# Inventory Status Transition Design

Status: Design defined; implementation tasks pending.

## 1. Scope and architecture

### 1.1 Design boundary

This feature extends the implemented service-skeleton design without replacing its CRUD API,
database ownership, error representation, paging representation, security boundary, or package
layout. It adds two unauthenticated operations:

| Capability | Method and path |
| --- | --- |
| Transition one item's status | `PATCH /api/v1/inventory/{serialNumber}/status` |
| Browse retained transition history | `GET /api/v1/inventory-history` |

Only the dedicated `PATCH` operation enforces lifecycle transitions and creates history. Item
creation and `PUT /api/v1/inventory/{serialNumber}` continue to accept every defined status and do
not create history. No history mutation operation is exposed.

The top-level `/api/v1/inventory-history` collection deliberately sits outside
`/api/v1/inventory/{serialNumber}`. It therefore cannot shadow retrieval of an inventory item whose
serial number happens to be `history` or another collection-like term.

### 1.2 Resolved design decisions

| Decision | Resolution and rationale |
| --- | --- |
| Unknown `PATCH` members | Reject with `400`; the existing Jackson `fail-on-unknown-properties` setting already provides strict request binding. |
| Timestamp authority | PostgreSQL supplies `CURRENT_TIMESTAMP` into a `timestamptz` column, keeping one database-authoritative clock. |
| Internal identity | A generated `bigint` identity is the primary key and deterministic paging tie-breaker; it is never exposed by the API. |
| Concurrent transitions | A pessimistic write lock serializes transition validation and mutation for one inventory row. |
| Component boundary | `InventoryHistoryController` owns the history HTTP collection while the existing `InventoryService` owns both transition and history-list use cases. |
| Rule ownership | `InventoryStatus` owns the exhaustive `canTransitionTo` matrix. |
| Partial matching | PostgreSQL `LIKE` implements case-sensitive literal substring matching; no `pg_trgm` extension is introduced. |

### 1.3 Component flow

The new runtime paths follow the existing layer direction:

```text
PATCH request
  -> InventoryController
  -> InventoryService
  -> InventoryRepository (locked item)
  -> InventoryStatusHistoryRepository (one insert)

GET history request
  -> InventoryHistoryController
  -> InventoryService
  -> InventoryStatusHistoryRepository
  -> InventoryStatusHistoryConverter
  -> PagedModel<InventoryStatusHistoryDTO>
```

Controllers do not access repositories. `InventoryHistoryController` is separate because its path,
query allowlist, response type, and OpenAPI operation are independent of the inventory-item
collection. A separate history service would only delegate to the same repositories and
transaction rules, so it is not added.

## 2. HTTP API

### 2.1 Transition an inventory status

`PATCH /api/v1/inventory/{serialNumber}/status` consumes `application/json`. The path value uses
the existing case-sensitive `InventoryItemDTO.SERIAL_NUMBER_PATTERN` and its 64-character limit.
The request body is an `InventoryStatusUpdateDTO` with exactly one required property:

```json
{
  "status": "RENTED"
}
```

`status` is annotated with `@NotNull` and an OpenAPI enum containing all six status values. The
existing `spring.jackson.deserialization.fail-on-unknown-properties: true` setting rejects every
other JSON member. Malformed JSON and unknown members follow the existing unreadable-message
problem path; a missing or null `status` follows Bean Validation; an unknown enum token follows the
existing invalid-`InventoryStatus` mapping.

The controller passes `serialNumber` and `request.status()` to
`InventoryService.transitionStatus`. A successful call returns
`ResponseEntity.noContent().build()` and has no response content or `Content-Type`.

| Result | Status | Body |
| --- | --- | --- |
| Permitted transition committed | `204 No Content` | Empty |
| Invalid path or body | `400 Bad Request` | Problem Details |
| Item absent | `404 Not Found` | Problem Details |
| Same-status or disallowed transition | `409 Conflict` | Problem Details |
| Response representation unacceptable | `406 Not Acceptable` | Problem Details |
| Request media type unsupported | `415 Unsupported Media Type` | Problem Details |
| Unexpected failure | `500 Internal Server Error` | Problem Details |

The stable OpenAPI operation ID is `transitionInventoryStatus`.

### 2.2 Browse status history

`GET /api/v1/inventory-history` produces `application/json` and accepts only the following query
parameters:

| Parameter | Type | Default | Validation and behavior |
| --- | --- | --- | --- |
| `page` | Integer | `0` | Minimum `0` |
| `size` | Integer | `20` | Minimum `1`, maximum `100` |
| `serialNumber` | String | None | Raw value nonblank and 1-64 characters; case-sensitive literal substring |
| `sort` | String enum | `timestamp` | `serialNumber`, `statusFrom`, `statusTo`, or `timestamp` |
| `direction` | String enum | `desc` | `asc` or `desc`, parsed case-insensitively |

One sort field and one direction are accepted. The service maps public sort names through
`InventoryStatusHistorySortField`; client values are never passed to JPA as property paths. The
internal `id` is appended in the same direction as the requested primary order. The resulting
orders are therefore deterministic, including the default `timestamp DESC, id DESC` order.

The serial filter is not stripped or case-folded. It is treated as a literal substring, so `%`,
`_`, and the SQL escape character are escaped before building the Criteria API `LIKE` pattern
`%escapedValue%` with `\` as its explicit escape character. This preserves literal matching for
valid serial numbers such as `DRILL_001` rather than treating `_` as a wildcard. PostgreSQL
`LIKE`, rather than `ILIKE`, preserves the required case sensitivity.

The return type is `PagedModel<InventoryStatusHistoryDTO>`. It uses the existing non-HATEOAS
Spring Data page representation with `content` and nested `page` metadata. Each content element has
exactly four properties:

```json
{
  "content": [
    {
      "serialNumber": "DRILL-001",
      "statusFrom": "RESERVED",
      "statusTo": "RENTED",
      "timestamp": "2026-08-04T19:10:30.123456Z"
    }
  ],
  "page": {
    "size": 20,
    "number": 0,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

`timestamp` is a Java `Instant` serialized as an ISO-8601/RFC 3339 UTC date-time. PostgreSQL may
retain microsecond precision; the API does not pad or truncate fractional seconds beyond the
normal driver and Jackson mapping.

A valid request always returns `200 OK`, including an empty result or a page past the last page.
Invalid parameters return the existing `400` validation problem; unacceptable response media
returns `406`; unexpected failures return the sanitized `500` problem. The stable operation ID is
`listInventoryStatusHistory`.

### 2.3 Query validation and routing

`InventoryHistoryController` is annotated with `@RestController`, `@Validated`, and the existing
`Inventory` OpenAPI tag. Its class-level mapping is `/api/v1/inventory-history`. A private
allowlist contains exactly `page`, `size`, `serialNumber`, `sort`, and `direction`.

The controller follows the inventory collection's validation behavior:

- unknown query names are rejected;
- every supplied parameter must occur exactly once;
- every supplied raw value must be nonblank;
- `page` and `size` use `@Min` and `@Max`;
- `serialNumber` uses `@Size(min = 1, max = 64)` plus a nonblank pattern;
- sort fields use an enum-backed allowlist;
- directions use `Sort.Direction.fromString` and are case-insensitive.

These checks remain private controller logic rather than introducing a shared query-validation
abstraction solely for two endpoints. The literal top-level history path has no overlap with the
existing inventory-item path mapping. `PATCH` remains unsupported on `/api/v1/inventory` and
`/api/v1/inventory/{serialNumber}`; only the new `/status` subresource accepts it.

### 2.4 OpenAPI and access boundary

Both operations remain unauthenticated, consistent with the service skeleton. No security scheme
or per-operation security requirement is added.

Controller and DTO annotations document:

- both paths and stable operation IDs;
- the transition request's sole required field and all status values;
- the empty `204` response;
- history page and record schemas;
- query defaults, bounds, allowlists, and matching semantics;
- all applicable Problem Details responses.

`OpenApiIT` expands its exact path, operation, schema, parameter, response-code, and example
assertions. README examples add one permitted transition, one rejected transition, and one
filtered/sorted history request without changing existing examples.

## 3. Domain and service behavior

### 3.1 Transition matrix in `InventoryStatus`

`InventoryStatus` gains `boolean canTransitionTo(InventoryStatus target)`. A `switch` on `this`
implements the exhaustive matrix:

| Source | Targets returning `true` |
| --- | --- |
| `AVAILABLE` | Every defined status except `AVAILABLE` |
| `RESERVED` | `AVAILABLE`, `RENTED` |
| `RENTED` | `INSPECTION_REQUIRED` |
| `INSPECTION_REQUIRED` | `AVAILABLE`, `UNDER_MAINTENANCE`, `RETIRED` |
| `UNDER_MAINTENANCE` | `AVAILABLE`, `RETIRED` |
| `RETIRED` | None |

A null target and every same-status target return `false`. The enum is the single application
source of transition truth; the controller and service do not reproduce status-pair lists.

### 3.2 Inventory entity mutation and unchanged replacement

`InventoryItem` gains a dedicated `transitionStatus(InventoryStatus target)` mutation method. The
service calls it only after `item.getStatus().canTransitionTo(target)` succeeds. It changes only
the `status` field and requires a non-null target.

The existing `replaceDetails` path remains separate and unchanged. `InventoryService.replace`
continues to call `replaceDetails`, so `PUT` bypasses `canTransitionTo` and does not write history.
Creation likewise does not write history. This separation makes the intentional difference between
catalogue replacement and lifecycle transition explicit without altering the public `PUT`
contract.

### 3.3 Transition transaction and locking

`InventoryService.transitionStatus(String serialNumber, InventoryStatus target)` is
`@Transactional` and performs these steps in order:

1. Load the inventory item through `InventoryRepository.findByIdForUpdate`, which uses
   `@Lock(LockModeType.PESSIMISTIC_WRITE)` and a query by serial number.
2. Throw the existing `InventoryItemNotFoundException` if no row exists.
3. Capture the managed item's current status as `statusFrom`.
4. Evaluate `statusFrom.canTransitionTo(target)`.
5. If false, throw `InvalidInventoryStatusTransitionException` before mutation or history insert.
6. Call `item.transitionStatus(target)` on the managed entity.
7. Persist one new `InventoryStatusHistory(serialNumber, statusFrom, target)` through the history
   repository.
8. Let transaction commit flush both the managed-item update and history insert.

The exception contains serial number, source status, and target status for deterministic problem
construction. It is a service-layer exception, matching the existing not-found and conflict
exceptions; the domain enum itself has no dependency on the service or controller layers.

The item update and history insert share one transaction. A persistence failure during either
flush rolls back both. Validation, not-found, same-status, and disallowed-transition failures occur
before a history entity is saved.

The pessimistic row lock makes two concurrent dedicated transitions execute serially. The second
request evaluates its rule against the status committed by the first request, then either proceeds
from that status or receives `409`. No version column, retry loop, ETag, or client-supplied expected
status is introduced.

Existing `PUT` and delete implementations are not changed to acquire this explicit lock. Their
concurrent interaction with `PATCH` remains ordinary PostgreSQL row-update serialization and
last-committer behavior, consistent with the requirements' concurrency exclusions and the rule
that existing endpoints remain unchanged.

### 3.4 History list service flow

The existing `InventoryService` gains a read-only list method accepting page, size, optional serial
substring, `InventoryStatusHistorySortField`, and `Sort.Direction`. It creates the primary order
and appends `id` in the same direction, constructs a `PageRequest`, and delegates to the history
repository's filtered `findAll` method.

`InventoryHistoryController` maps the returned page through
`InventoryStatusHistoryConverter.toResponse` and wraps it in `PagedModel`. The count query supplies
accurate `totalElements` and `totalPages`; no in-memory filtering, sorting, or pagination occurs.

## 4. Persistence design

### 4.1 Append-only V2 migration

The shipped V1 migration is not edited. A new
`src/main/resources/db/migration/V2__create_inventory_status_history.sql` creates only
Inventory-owned objects:

```sql
CREATE TABLE inventory.inventory_status_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    serial_number varchar(64) NOT NULL,
    status_from varchar(32) NOT NULL,
    status_to varchar(32) NOT NULL,
    transitioned_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_inventory_status_history_serial_number
        CHECK (serial_number ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT chk_inventory_status_history_status_from
        CHECK (status_from IN (
            'AVAILABLE', 'RESERVED', 'RENTED', 'INSPECTION_REQUIRED',
            'UNDER_MAINTENANCE', 'RETIRED'
        )),
    CONSTRAINT chk_inventory_status_history_status_to
        CHECK (status_to IN (
            'AVAILABLE', 'RESERVED', 'RENTED', 'INSPECTION_REQUIRED',
            'UNDER_MAINTENANCE', 'RETIRED'
        )),
    CONSTRAINT chk_inventory_status_history_changed
        CHECK (status_from <> status_to)
);

CREATE INDEX idx_inventory_status_history_transitioned_at_id
    ON inventory.inventory_status_history (transitioned_at DESC, id DESC);
```

The database constraints enforce serial syntax, defined status values, and the invariant that a
history record represents a change. The detailed transition matrix remains in `InventoryStatus`;
duplicating it as a database check would create a second application rule source and is not needed
because no external writer is in scope.

`CURRENT_TIMESTAMP` is evaluated by PostgreSQL and stored as an absolute `timestamptz` value. The
identity key is not a business field. Its only uses are persistence identity and deterministic
ordering among equal public sort values.

No foreign key references `inventory_items`. A foreign key with deletion restriction would break
item deletion, while a cascading foreign key would destroy history. A nullable/set-null relation
would lose the required serial number. The copied, constrained serial value therefore remains
self-contained after item deletion.

### 4.2 History entity

`InventoryStatusHistory` maps to `inventory.inventory_status_history` with:

| Java property | Column | Mapping |
| --- | --- | --- |
| `id` | `id` | `Long`, `@Id`, identity-generated, no public API exposure |
| `serialNumber` | `serial_number` | `String`, non-null, length 64, not updatable |
| `statusFrom` | `status_from` | `InventoryStatus`, string enum, non-null, not updatable |
| `statusTo` | `status_to` | `InventoryStatus`, string enum, non-null, not updatable |
| `timestamp` | `transitioned_at` | `Instant`, DB-generated, non-null, not insertable or updatable |

The entity has a protected no-argument constructor and one production constructor accepting serial
number, source status, and target status. It has getters and no public setters or mutation methods.
Hibernate's `@Immutable` marks loaded history entities read-only. The write path does not need the
DB-generated timestamp after insert because `PATCH` returns no representation; later read
transactions load it normally.

`InventoryStatusHistoryDTO` is an immutable record with only `serialNumber`, `statusFrom`,
`statusTo`, and `timestamp`. `InventoryStatusHistoryConverter` performs the direct entity-to-DTO
mapping. No DTO exposes `id`.

### 4.3 Repository and substring specification

`InventoryStatusHistoryRepository` extends `JpaRepository<InventoryStatusHistory, Long>` and
`JpaSpecificationExecutor<InventoryStatusHistory>`, following `InventoryRepository`. Its default
filtered `findAll` method composes `InventoryStatusHistorySpecifications.withSerialNumber` and a
caller-supplied `Pageable`.

The package-private specification returns a conjunction when no filter is supplied. When a filter
is present, it escapes `\\`, `%`, and `_` and creates a Criteria API `LIKE` predicate with an
explicit escape character. It does not call `lower`, use `ILIKE`, or join `inventory_items`.
Consequently, matching is case-sensitive, treats the query as a literal substring, and continues
to find rows whose item was deleted.

### 4.4 Retention and immutability

Production code inserts history only in `InventoryService.transitionStatus`. It never updates or
deletes history, and no API operation exposes those actions. Entity immutability and non-updatable
column mappings prevent dirty-checking updates. The absence of an item foreign key preserves rows
through `InventoryService.delete` and through direct deletion of an Inventory-owned item row.

Integration-test cleanup explicitly deletes history rows between tests before clearing inventory
items. This test-only cleanup is not application behavior and prevents retained records from
coupling test order.

### 4.5 Query performance

The timestamp/identity index serves the default and most common history order. The identity primary
key supports deterministic tie resolution. Arbitrary contains predicates have a leading wildcard,
so an ordinary B-tree serial index would not accelerate them. The first implementation therefore
uses PostgreSQL `LIKE` without adding `pg_trgm`, extensions, generated columns, or specialized
indexes. Additional indexing requires measured history volume and query evidence in a later
migration.

## 5. Validation and error contract

### 5.1 Invalid transition problem

`ApiExceptionHandler` maps `InvalidInventoryStatusTransitionException` to:

```json
{
  "type": "urn:rentflow:problem:invalid-inventory-status-transition",
  "title": "Invalid inventory status transition",
  "status": 409,
  "detail": "Inventory item 'DRILL-001' cannot transition from RENTED to AVAILABLE.",
  "instance": "/api/v1/inventory/DRILL-001/status",
  "code": "INVALID_INVENTORY_STATUS_TRANSITION"
}
```

The same response is used for a same-status target and every other disallowed pair. No field
violation is included because the target enum value is syntactically valid; it conflicts with the
current persisted resource state.

### 5.2 Validation and framework failures

The feature reuses the existing RFC 9457 catalogue except for the new `409` entry:

| Condition | Status and mapping |
| --- | --- |
| Missing or null body `status` | `400 VALIDATION_FAILED`, violation field `status` |
| Undefined status token | `400 VALIDATION_FAILED`, violation field `status` |
| Malformed JSON or unknown JSON member | `400 MALFORMED_JSON` |
| Invalid serial path syntax | `400 VALIDATION_FAILED`, violation field `serialNumber` |
| Missing item | Existing `404 INVENTORY_ITEM_NOT_FOUND` |
| Same or disallowed transition | New `409 INVALID_INVENTORY_STATUS_TRANSITION` |
| Unsupported request media | Existing `415 UNSUPPORTED_MEDIA_TYPE` |
| Invalid history query | `400 VALIDATION_FAILED` with the rejected query field |
| Unacceptable response media | Existing `406 NOT_ACCEPTABLE` |
| Unexpected persistence or server error | Existing sanitized `500 INTERNAL_ERROR` |

Rejected requests do not call the history repository's save path. If a persistence exception
occurs after mutation begins, transaction rollback leaves both tables unchanged and the existing
unexpected-error handler prevents SQL, constraint, connection, and credential details from
reaching the response.

## 6. Package and class changes

All additions use the existing prescribed packages:

| Package | New or changed types |
| --- | --- |
| `controller` | Change `InventoryController`; add `InventoryHistoryController`; change `ApiExceptionHandler` |
| `dto` | Add `InventoryStatusUpdateDTO`, `InventoryStatusHistoryDTO` |
| `converter` | Add `InventoryStatusHistoryConverter` |
| `service` | Change `InventoryService`; add `InventoryStatusHistorySortField`, `InvalidInventoryStatusTransitionException` |
| `repository` | Change `InventoryRepository`; add `InventoryStatusHistoryRepository`, `InventoryStatusHistorySpecifications` |
| `model` | Change `InventoryStatus`, `InventoryItem`; add `InventoryStatusHistory` |
| `resources/db/migration` | Add `V2__create_inventory_status_history.sql` |

No dependency, framework, package, folder, global paging customization, event bus, or new service
layer is introduced. `ArchitectureTest` continues to enforce the same controller-to-service,
service-to-repository, converter-to-DTO/model, and repository-to-model directions.

## 7. Invariants and edge cases

### 7.1 Invariants

1. Only a successful dedicated `PATCH` changes status and inserts exactly one history row in the
   same transaction.
2. Every history row has a valid serial, two defined statuses, different source and target values,
   and a PostgreSQL-authored timestamp.
3. A dedicated transition changes no inventory field other than status.
4. `PUT` and create neither enforce the matrix nor write history.
5. History has no foreign-key lifecycle dependency on the current item.
6. No public history representation exposes the identity key.
7. Every history page has a deterministic primary order plus identity tie-breaker.

### 7.2 Edge-case outcomes

| Case | Result |
| --- | --- |
| Target equals current status | `409`; item unchanged; no history row |
| Source is `RETIRED` | Every target receives `409`; no history row |
| Target token is unknown | `400` validation problem before service invocation |
| Body contains an extra member | `400` malformed-JSON problem before service invocation |
| Item disappears before locked lookup | `404`; no history row |
| Two concurrent valid transitions | Row lock serializes them; the second evaluates the first committed target as its source |
| History insert fails | Item update rolls back |
| Item update fails | History insert rolls back |
| `PUT` performs a normally disallowed status change | Existing `200` replacement; no history row |
| Item is deleted after transitions | Item retrieval returns `404`; matching history remains listed |
| Serial filter contains `_` or `%` | Character is matched literally, not as a SQL wildcard |
| Filter differs only by case | No match unless the stored serial contains that exact case |
| Empty or past-end history page | `200` with empty `content` and accurate page metadata |
| Equal public sort values | Internal identity order makes the result deterministic |
| Timestamp values share an instant | Identity tie-breaker makes their order deterministic |
| Serial number is exactly `history` | Item retrieval remains available because history uses the separate top-level path |

## 8. Verification design

### 8.1 Unit tests

Unit tests use JUnit Jupiter, AssertJ, and Mockito without a Spring context.

| Test | Required coverage |
| --- | --- |
| `InventoryStatusTest` | All source/target pairs, all same-status pairs, null targets, and exact matrix exhaustiveness |
| `InventoryItemTest` | Dedicated transition changes only status; full replacement still bypasses lifecycle rules |
| `InventoryStatusUpdateDTOTest` | Required status validation |
| `InventoryStatusHistoryConverterTest` | Exact four-field mapping and no identity exposure |
| `InventoryServiceTest` | Locked lookup; allowed transition and one history save; same/disallowed/not-found non-interaction; unchanged `PUT`; list filter/page/sort delegation and identity tie-breaker |
| `ApiExceptionHandlerTest` | Stable invalid-transition Problem Details and continued failure sanitization |

Tests use explicit Java types and Mockito argument capture where repository collaboration matters.
They do not start Spring for domain, service, converter, or handler behavior.

### 8.2 Migration and integration tests

`MigrationIT` verifies V2 is applied once in the `inventory` schema, the table and default-order
index exist, `id` is identity-generated, PostgreSQL supplies a non-null timestamp, constraints
reject invalid serial/status/same-status rows, no item foreign key exists, another service's schema
is untouched, and a retained history row survives item deletion and application restart.

`InventoryIT` adds externally observable coverage for:

- every permitted source/target pair;
- representative rejected pairs from every source, every same-status request, and all `RETIRED`
  targets;
- `204` with an empty body and mutation of status only;
- exactly one persisted row with correct before/after values and a bounded DB timestamp;
- malformed, missing, null, unknown, extra-member, unsupported-media, invalid-path, missing-item,
  and invalid-transition requests producing no history;
- two concurrent dedicated transitions proving lock serialization;
- unchanged `PUT` acceptance and absence of `PUT` history;
- history survival and list visibility after item deletion;
- history defaults, page bounds, past-end and empty pages, accurate totals, literal partial serial
  filtering, case sensitivity, every sort field in both directions, equal-value tie-breakers,
  unknown/repeated/blank/invalid query handling, and unauthenticated access.

Database cleanup clears `inventory_status_history` and `inventory_items` explicitly between tests.
Assertions inspect the real PostgreSQL tables rather than replacing persistence with mocks.

### 8.3 OpenAPI and architecture verification

`OpenApiIT` changes its exact operation count and asserts both new operation IDs, request and
response schemas, four history fields, absence of history `id`, status enum values, query defaults
and allowlists, `204` without content, and all applicable error schemas. The prior blanket
PATCH-absence assertion is replaced with an assertion that PATCH exists only on the `/status`
subresource; the other CRUD contract assertions remain.

`ArchitectureTest` needs no new layer. It automatically includes the new controller, converter,
DTO, service collaborators, repositories, and entity in the existing package, suffix, mapping,
cycle, and dependency rules.

The final implementation task runs `mvn -B -ntp clean verify` and must finish with `BUILD SUCCESS`.

## 9. Requirements traceability

| Acceptance criteria | Design coverage |
| --- | --- |
| AC1.1 | §2.1, §3.3 |
| AC1.2 | §3.1 |
| AC1.3 | §3.2-§3.3, §7.1 |
| AC1.4-AC1.5 | §3.1, §3.3, §5.1 |
| AC1.6 | §2.1, §3.3, §5.2 |
| AC1.7-AC1.8 | §2.1, §2.3, §5.2 |
| AC2.1-AC2.3 | §3.3, §4.1-§4.2, §5.2 |
| AC2.4-AC2.5 | §4.1, §4.4 |
| AC3.1-AC3.2 | §2.2, §3.4 |
| AC3.3 | §2.2, §4.3, §4.5 |
| AC3.4-AC3.5 | §2.2, §3.4 |
| AC3.6-AC3.7 | §2.2-§2.3, §5.2 |
| AC3.8 | §2.2, §4.2 |
| AC3.9 | §2.2, §4.3-§4.4 |
| AC4.1-AC4.3 | §1.1, §3.2-§3.3 |
| AC5.1 | §5.1 |
| AC5.2 | §5.2 |
| AC5.3-AC5.4 | §2.1-§2.4 |

Every acceptance criterion has a concrete HTTP, domain, persistence, error, documentation, or
verification design surface. Task-level traceability will be added in `tasks.md`.
