# Inventory Status Transition Requirements

Status: Durable idempotency and daily cleanup implemented and verified locally; ready for review.

## Context

The inventory service currently permits catalogue operators to set an item's status through the
full-replacement endpoint without lifecycle restrictions. Rental operations also need a dedicated
way to advance only the lifecycle status while preventing transitions that contradict the
equipment workflow.

This feature replaces the single-item operation with `PATCH /api/v1/inventory/status`, without
adding an API version or retaining the previous endpoint. A request supplies an array of 1–100
objects, each containing a unique `serialNumber` and a defined target `status`. Success changes
only those items' statuses and returns `204 No Content` with an empty response body. The entire
batch, including all history records, commits atomically; any failed item prevents every change.

The dedicated endpoint enforces the following exhaustive transition matrix:

| Current status | Permitted target statuses |
| --- | --- |
| `AVAILABLE` | `RESERVED`, `RENTED`, `INSPECTION_REQUIRED`, `UNDER_MAINTENANCE`, `RETIRED` |
| `RESERVED` | `AVAILABLE`, `RENTED` |
| `RENTED` | `INSPECTION_REQUIRED` |
| `INSPECTION_REQUIRED` | `AVAILABLE`, `UNDER_MAINTENANCE`, `RETIRED` |
| `UNDER_MAINTENANCE` | `AVAILABLE`, `RETIRED` |
| `RETIRED` | None |

A transition to the current status is not a permitted transition. A syntactically valid request
for any target not listed for the current status conflicts with the item's lifecycle state and is
rejected with `409 Conflict`.

`AVAILABLE` is the rental workflow's availability gate. An item is eligible for a new reservation
only while it is `AVAILABLE`; a successful transition to `RESERVED` claims that availability for
one active reservation. Because competing transitions for the same item are serialized, only one
reservation claim can succeed before the item is released or rented. Cancelling the reservation
returns the item from `RESERVED` to `AVAILABLE`, while fulfilling it moves the item to `RENTED`.
A direct `AVAILABLE` to `RENTED` transition remains permitted for rentals that do not first create
a reservation.

An item does not become available immediately after a rental. `RENTED` can transition only to
`INSPECTION_REQUIRED`. A passing inspection returns the item to `AVAILABLE`; an item needing work
moves to `UNDER_MAINTENANCE` and becomes `AVAILABLE` only after maintenance completes. Inspection
or maintenance may instead retire an item that should no longer be rented. These guarantees apply
to the dedicated lifecycle endpoint; the existing full-replacement endpoint remains outside the
lifecycle workflow as described in US4.

Every successful transition through the dedicated endpoint creates an immutable history record in
an Inventory-owned database table. Each record contains the item's serial number, the status
before the transition, the status after the transition, and the time of the transition. The item
update and its history record are one atomic operation. History is retained if the current
inventory item is later deleted.

Clients can browse the retained records through `GET /api/v1/inventory-history`. The endpoint
returns the same bounded page shape and uses the same pagination defaults and limits as the
existing inventory collection endpoint: zero-based `page` defaults to `0`, `size` defaults to
`20`, and the maximum size is `100`. Each response record exposes only `serialNumber`,
`statusFrom`, `statusTo`, and `timestamp`; any persistence identifier remains internal.

The history collection accepts an optional `serialNumber` filter that performs a case-sensitive
substring match. It accepts one primary `sort` field selected from `serialNumber`, `statusFrom`,
`statusTo`, and `timestamp`, together with one `direction`. The default order is newest timestamp
first with a deterministic internal tie-breaker. Records remain visible through this endpoint
after their corresponding inventory item is deleted.

The existing `PUT /api/v1/inventory/{serialNumber}` full-replacement contract remains unchanged:
it continues to accept every defined status without applying the transition matrix, and it does
not create status-transition history.

These requirements extend the service-skeleton specification and supersede only its statements
that status-transition restrictions, partial `PATCH` operations, and status history are wholly out
of scope. Unsupported `PATCH` requests on other inventory paths remain unsupported. All other
service-skeleton requirements continue to apply.

## User stories

### US1 - Transition inventory statuses atomically

As a rental workflow operator, I want to transition multiple items together through a dedicated
endpoint so that the whole operation succeeds only when every equipment-state progression is valid.

- **AC1.1 (Event-driven):** When a client requests a batch of permitted transitions for existing items
  through `PATCH /api/v1/inventory/status`, the inventory service shall persist all requested
  target statuses and return `204 No Content` with an empty response body.
- **AC1.2 (Ubiquitous):** The inventory service shall apply the transition matrix in the Context
  section exhaustively, with no permitted transition other than those listed for the item's
  current status.
- **AC1.3 (Event-driven):** When a status transition succeeds, the inventory service shall leave
  the item's `serialNumber`, `type`, and `name` unchanged.
- **AC1.4 (Unwanted):** If a syntactically valid batch requests a target equal to the item's current status, then the
  inventory service shall return `409 Conflict` and shall leave every batch item unchanged.
- **AC1.5 (Unwanted):** If a syntactically valid batch requests a target not permitted from the item's current status,
  then the inventory service shall return `409 Conflict` and shall leave every batch item unchanged.
- **AC1.6 (Unwanted):** If a syntactically valid batch has failures and every failed item is missing, then the inventory service
  shall return `404 Not Found` and shall leave every batch item unchanged without creating items.
- **AC1.7 (Unwanted):** If a status-transition request is malformed, has an invalid or missing serial number,
  omits its required status, supplies a null status, or contains an undefined status, then the
  inventory service shall return `400 Bad Request` and shall leave every batch item unchanged.
- **AC1.8 (Unwanted):** If a status-transition request uses an unsupported media type, then the
  inventory service shall return `415 Unsupported Media Type` and shall leave every batch item unchanged.

- **AC1.9 (Unwanted):** If the body is not an array of 1–100 non-null objects or contains repeated
  case-sensitive serial numbers, then the inventory service shall return `400 Bad Request` and
  shall leave every batch item and all history unchanged.
- **AC1.10 (Unwanted):** If a syntactically valid batch has any missing item or forbidden
  transition, then the inventory service shall report all and only failed items in request order
  in `failedItems`, each with zero-based `index`, `serialNumber`, requested `status`, `code`, and
  `message`, and shall commit no status changes or history records for the batch.
- **AC1.11 (Unwanted):** If a syntactically valid batch contains both missing items and forbidden transitions, then
  the inventory service shall return `409 Conflict` with both kinds of failure in `failedItems`.
- **AC1.12 (Unwanted):** If request input validation fails, then the inventory service shall
  return only input errors with `400 Bad Request` and shall not evaluate lifecycle rules or
  perform inventory writes.
- **AC1.13 (Ubiquitous):** The inventory service shall treat `AVAILABLE` as the only current status
  from which the dedicated lifecycle endpoint permits a transition to `RESERVED`, so at most one
  serialized reservation claim can succeed before the item is released or rented.
- **AC1.14 (Event-driven):** When an active reservation is cancelled while its item is `RESERVED`,
  the inventory service shall permit the dedicated lifecycle transition from `RESERVED` to
  `AVAILABLE`.
- **AC1.15 (Event-driven):** When a rental starts without a preceding reservation, the inventory
  service shall permit the dedicated lifecycle transition from `AVAILABLE` directly to `RENTED`.
- **AC1.16 (State-driven):** While an item is `RENTED`, the inventory service shall permit only
  `INSPECTION_REQUIRED` as its next status through the dedicated lifecycle endpoint and shall not
  permit it to become `AVAILABLE` directly.
- **AC1.17 (Event-driven):** When post-rental inspection or maintenance completes, the inventory
  service shall permit the item to become `AVAILABLE` only from `INSPECTION_REQUIRED` after a
  passing inspection or from `UNDER_MAINTENANCE` after completed maintenance; inspection and
  maintenance shall also permit retirement instead of renewed availability.

### US2 - Retain an audit trail of dedicated status transitions

As an inventory operator, I want successful lifecycle transitions recorded so that the service
retains an immutable account of how each item's status changed over time.

- **AC2.1 (Event-driven):** When a dedicated status transition succeeds, the inventory service
  shall persist exactly one new history record containing the item's serial number, its status
  immediately before the transition, its status immediately after the transition, and the
  transition timestamp.
- **AC2.2 (Ubiquitous):** The inventory service shall commit the inventory-item status change and
  all corresponding history records for the complete batch atomically so that either all changes
  persist or none persist.
- **AC2.3 (Unwanted):** If a dedicated status-transition request is rejected or fails, then the
  inventory service shall not persist a history record for that request.
- **AC2.4 (Event-driven):** When an inventory item is deleted after one or more successful
  dedicated status transitions, the inventory service shall preserve all of that item's existing
  status-history records.
- **AC2.5 (Ubiquitous):** The inventory service shall confine the status-history table and all
  migration changes for it to the Inventory-owned `inventory` schema.

### US3 - Browse status-transition history

As an inventory operator, I want to page, filter, and sort retained transition history so that I
can inspect lifecycle changes across the equipment catalogue.

- **AC3.1 (Event-driven):** When a client requests `GET /api/v1/inventory-history` without query
  parameters, the inventory service shall return `200 OK` with page `0` containing at most `20`
  records, a non-null `content` array, nested `page` metadata, and deterministic
  newest-transition-first ordering.
- **AC3.2 (Event-driven):** When a client supplies valid `page` and `size` parameters, the
  inventory service shall return `200 OK` with the requested zero-based page and accurate nested
  page metadata when `page` is at least `0` and `size` is between `1` and `100`, inclusive.
- **AC3.3 (Event-driven):** When a client supplies a non-blank `serialNumber` filter, the inventory
  service shall return only history records whose serial number contains the supplied substring
  using case-sensitive matching.
- **AC3.4 (Event-driven):** When a client requests ascending or descending sorting by
  `serialNumber`, `statusFrom`, `statusTo`, or `timestamp`, the inventory service shall return the
  page in the requested deterministic order using one selected primary sort field and direction.
- **AC3.5 (Event-driven):** When a valid history collection request omits an explicit sort field
  and direction, the inventory service shall order records by `timestamp` descending with a
  deterministic internal tie-breaker.
- **AC3.6 (Event-driven):** When no history records match a valid collection request, the
  inventory service shall return `200 OK` with an empty `content` array and nested `page` metadata.
- **AC3.7 (Unwanted):** If a history collection request contains an unknown or repeated query
  parameter, an invalid page or size, a blank serial-number filter, an unsupported sort field, or
  an unsupported direction, then the inventory service shall return `400 Bad Request`.
- **AC3.8 (Ubiquitous):** The inventory service shall expose exactly `serialNumber`, `statusFrom`,
  `statusTo`, and `timestamp` for each history record and shall not expose an internal persistence
  identifier.
- **AC3.9 (Event-driven):** When history exists for an inventory item that has since been deleted,
  the inventory service shall include that history in otherwise matching collection results.

### US4 - Preserve full-replacement behavior

As an existing inventory API consumer, I want full replacement to retain its current semantics so
that adding the dedicated lifecycle operation does not break existing integrations.

- **AC4.1 (Event-driven):** When a client submits a valid full replacement through
  `PUT /api/v1/inventory/{serialNumber}` with any defined status, the inventory service shall
  continue to accept that status without applying the dedicated endpoint's transition matrix.
- **AC4.2 (Event-driven):** When a full replacement changes an item's status, the inventory
  service shall not create a status-history record.
- **AC4.3 (Ubiquitous):** The inventory service shall leave all other existing full-replacement
  request validation, success response, error response, and persistence behavior unchanged.

### US5 - Receive a consistent feature API contract

As an API consumer, I want the new operations and their failures documented consistently so that
my integration can invoke them and diagnose rejected requests.

- **AC5.1 (Unwanted):** If a syntactically valid dedicated status transition is rejected because the target is not
  permitted from the current status, including a transition to the same status, then the inventory
  service shall return an RFC 9457 Problem Details response with `409 Conflict` and a stable
  machine-readable problem code for an invalid status transition and a `failedItems` array. Each
  entry shall contain its zero-based request `index`, `serialNumber`, requested `status`, individual
  `code`, and `message`.
- **AC5.2 (Unwanted):** If another client or server error occurs for the dedicated transition or
  history collection endpoint, then the inventory service shall return an RFC 9457 Problem Details
  response consistent with the existing inventory API error contract.
- **AC5.3 (Ubiquitous):** The OpenAPI contract shall document the dedicated status-transition path,
  request schema, all permitted status values, `204 No Content` success response, history
  collection path, history response schema, collection query parameters, and applicable error
  responses.
- **AC5.4 (Event-driven):** When a client invokes the dedicated status-transition or history
  collection endpoint without authentication credentials, the inventory service shall process the
  request according to the endpoint's functional rules.

### US6 - Retry a batch safely

As an API consumer, I want durable replay of completed requests so that a lost response cannot cause a second transition.

- **AC6.1 (Unwanted):** If the request lacks exactly one canonical UUID v4 `Idempotency-Key` header, then the inventory service shall return `400 VALIDATION_FAILED` with an `Idempotency-Key` violation without consuming a key.
- **AC6.2 (Event-driven):** When validated input uses a new or expired key, the inventory service shall execute the batch and atomically persist its terminal `204`, `404`, or `409` outcome with any inventory and history changes.
- **AC6.3 (Event-driven):** When the same unexpired key and equivalent validated payload are retried, the inventory service shall replay the original status and problem fields without inventory evaluation or additional history, including after item deletion or application restart.
- **AC6.4 (Unwanted):** If an unexpired completed key is reused with a different validated payload, then the inventory service shall return `422 IDEMPOTENCY_KEY_REUSED` without inventory changes.
- **AC6.5 (State-driven):** While another transaction owns the key's execution lock, the inventory service shall return `409 IDEMPOTENCY_IN_PROGRESS` immediately with `Retry-After: 1`, including for a different payload.
- **AC6.6 (Ubiquitous):** The inventory service shall scope keys globally to this endpoint, normalize UUID case, ignore JSON whitespace and object property order in payload comparison, and preserve serial casing and array order.
- **AC6.7 (Event-driven):** When a terminal outcome is returned, the inventory service shall include `Idempotency-Replayed` and `Idempotency-Key-Expires-At`, with expiry seven days after database-recorded completion and unchanged by replay.
- **AC6.8 (Unwanted):** If input validation or a rolled-back server failure prevents completion, then the inventory service shall leave no new terminal record and permit retry with that key.
- **AC6.9 (Ubiquitous):** The OpenAPI contract and README shall document the required key, response headers and errors, retry/backoff guidance, seven-day guarantee, expiry reconciliation, and coordinated client/server cutover.

### US7 - Remove expired replay records

As an operator, I want bounded daily cleanup so that expired request records do not accumulate indefinitely.

- **AC7.1 (Ubiquitous):** The inventory service shall schedule cleanup daily at 03:00 UTC by default with a configurable cron, deleting only expired request records in transactions of at most 1000 records.
- **AC7.2 (Event-driven):** When a cleanup chunk fills its limit, the inventory service shall continue in a new transaction only while the configurable runtime budget (default 60 seconds) has not elapsed, skipping locked records safely across instances.
- **AC7.3 (Ubiquitous):** The inventory service shall honor logical expiry independently of physical cleanup and preserve all history and unexpired replay records during cleanup.
- **AC7.4 (Ubiquitous):** The inventory service shall expose attempt, replay, mismatch, busy, deleted-record and cleanup-failure counters, cleanup duration and expired-backlog metrics without key or serial-number labels.

For US1–US2 and AC5.1, lifecycle evaluation and new history apply to new attempts; replay takes precedence as specified in US6.

## Out of scope

- Applying the transition matrix to item creation or the existing full-replacement endpoint.
- Creating history records for item creation, full replacement, deletion, rejected requests, or
  failed requests.
- Retrieving one history record by an internal identifier.
- Filtering history by status or timestamp, or by any field other than a serial-number substring.
- Multiple primary sort clauses in one history collection request.
- Exporting, changing, or deleting status-history records through an API.
- Backfilling history for status changes that occurred before this feature is deployed.
- Adding, removing, or renaming values in the existing inventory status enum.
- Changing an item's serial number, type, or name through the dedicated status endpoint.
- Capturing an actor, reason, correlation identifier, request payload, or other metadata in a
  history record.
- Authentication, authorization, user identity, roles, or permissions.
- Optimistic locking, ETags, client-supplied expected statuses, or conditional requests for
  concurrent writes.
- Implementing reservation, rental, inspection, or maintenance workflows beyond enforcement of
  the specified status-transition matrix.
- Cross-service events, messaging, callbacks, or direct access to another service's schema.
- A retention period or automatic cleanup policy for status history.

## Resolved questions

1. **Dedicated endpoint:** Status transitions use an array at
   `PATCH /api/v1/inventory/status`; the former single-item path is removed without a new version. A successful transition returns
   `204 No Content`. This is reflected in AC1.1; the API rationale and precise request contract
   are defined in `design.md` §2.1.
2. **No-op requests:** A request whose target equals the current status is rejected rather than
   treated as a successful transition. This is reflected in AC1.4. See `design.md` §3.1 and §5.1.
3. **Full replacement:** The existing `PUT` endpoint remains unchanged, continues to bypass the
   transition matrix, and does not create history. This is reflected in US4; the interaction with
   the dedicated operation is defined in `design.md` §3.2.
4. **Invalid-transition response:** A disallowed transition returns `409 Conflict` because a valid
   target status conflicts with the item's current lifecycle state. This is reflected in AC1.4,
   AC1.5, and AC5.1. See `design.md` §5.1.
5. **History scope:** Only successful transitions through the dedicated endpoint create history
   records. This is reflected in AC2.1, AC2.3, and AC4.2. See `design.md` §3.3 and §4.2.
6. **History retention:** Deleting the current inventory item preserves its previously recorded
   status history. This is reflected in AC2.4. See `design.md` §4.1 and §4.4.
7. **History collection:** Retained history is listed through `GET /api/v1/inventory-history`.
   This is reflected in AC3.1. The non-conflicting route and API contract are defined in
   `design.md` §1.1 and §2.2.
8. **History representation:** Each API record exposes only `serialNumber`, `statusFrom`,
   `statusTo`, and `timestamp`; any persistence identifier remains internal. This is reflected in
   AC3.8. See `design.md` §2.2 and §4.2.
9. **Serial-number filtering:** The optional filter performs a case-sensitive substring match.
   This is reflected in AC3.3. See `design.md` §2.2 and §4.3.
10. **History sorting:** A request selects one primary sort field and one direction, consistently
    with the inventory collection, and every exposed history field is sortable. This is reflected
    in AC3.4. See `design.md` §2.2 and §3.4.
11. **History pagination:** The history collection shares the inventory collection's page defaults
    and bounds. This is reflected in AC3.1 and AC3.2. See `design.md` §2.2.
12. **Default history order:** Requests without an explicit sort use descending timestamp order
    with an internal deterministic tie-breaker. This is reflected in AC3.5. See `design.md` §2.2
    and §3.4.
13. **Deleted-item visibility:** Preserved history remains visible after its inventory item is
    deleted. This is reflected in AC2.4 and AC3.9. See `design.md` §4.4.
14. **Original implementation sequence:** The delivered baseline used four linear, independently verified commits for
    persistence, transition, history browsing, and final documentation/acceptance. See `tasks.md`
    §2 and §3. The batch amendment is the single increment T5.
15. **Original vertical endpoint increments:** The transition and history collection operations each ship
    with their service, controller, persistence collaboration, and tests in one safe commit. See
    `tasks.md` T2 and T3.
16. **Contract-test placement:** `OpenApiIT` changes in the same task as each endpoint so no
    intermediate commit carries a stale or failing generated-contract assertion. See `tasks.md`
    §1, T2, and T3.
17. **Concurrency verification:** The pessimistic-lock concurrency test belongs to the transition
    increment that introduces the locking behavior. See `tasks.md` T2.
18. **Final acceptance:** Original documentation used T4; the batch amendment includes documentation
    and acceptance together in T5. Acceptance runs
    `mvn -B -ntp clean verify` without rerunning the unchanged container smoke suite. See
    `tasks.md` §1 and T5.

19. **Batch shape and bounds:** The body is a direct array of 1–100 objects with `serialNumber`
    and `status`; duplicate serials are invalid input. See `design.md` §2.1 and §5.2.
20. **Mixed failures:** Any forbidden transition makes the batch response `409`; when all failures
    are missing items, it is `404`. Both report every failed item. See `design.md` §5.1.
21. **Failure entries:** `failedItems` contains zero-based `index`, `serialNumber`, requested
    `status`, `code`, and `message`, ordered by request index. See `design.md` §5.1.
22. **Validation precedence:** Input errors return `400` before lifecycle evaluation. Parse errors
    need not be aggregated; validly parsed field errors use indexed violations. See `design.md` §5.2.

23. **Idempotency decisions:** Required UUID v4 header; global endpoint scope; validated ordered payload fingerprint; seven-day durable terminal replay; busy `409` and mismatch `422`. See `design.md` §9.1–§9.3.
24. **Cleanup cadence:** Daily at 03:00 UTC, configurable, with 1000-row transactions and a 60-second runtime budget. Logical expiry does not depend on the job. See `design.md` §9.4 and `tasks.md` T6.
25. **Implementation sequence:** Extend persistence and cleanup in T6, then deliver the required API contract and retry tests in T7. See `tasks.md` §5.
26. **Reservation availability invariant:** `AVAILABLE` is the only state eligible for a new
    reservation claim, and the serialized `AVAILABLE` to `RESERVED` transition permits at most one
    active claim. Cancellation returns `RESERVED` to `AVAILABLE`; fulfillment moves it to
    `RENTED`; direct `AVAILABLE` to `RENTED` remains permitted. See AC1.13–AC1.15 and `design.md`
    §3.1.
27. **Post-rental availability:** A rented item must move to `INSPECTION_REQUIRED` and cannot
    become available directly. It becomes `AVAILABLE` after passing inspection, or after required
    maintenance completes; inspection or maintenance may retire it instead. The full-replacement
    endpoint remains outside this lifecycle enforcement. See AC1.16–AC1.17 and `design.md` §3.1,
    §3.2, and §7.1.
