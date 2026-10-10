# Asynchronous Reservation Cancellation: Implementation Walkthrough

This document explains the asynchronous reservation-cancellation feature implemented in the
Inventory service. It is intended as both a code walkthrough and a practical introduction to the
Kafka concepts used by the feature.

The short version is:

1. Reservation publishes a `ReservationCancelled` event to Kafka after a cancellation is committed.
2. Inventory consumes the event in its own consumer group.
3. Inventory validates the raw Kafka key and JSON value.
4. One Inventory database transaction checks a durable inbox receipt, locks the inventory item,
   optionally changes `RESERVED` to `AVAILABLE`, writes history, and stores the receipt.
5. The Kafka offset is committed only after that database transaction succeeds.
6. Deterministic failures go directly to a dead-letter topic. Unexpected failures are retried first.

The Reservation-side cancellation operation and outbox publisher are deliberately outside this
repository. This repository implements the Inventory consumer side of the integration.

## 1. Why this is an event rather than a command

The message is named `ReservationCancelled`, in the past tense, because it describes a business
fact that has already happened in the Reservation service.

- Reservation owns reservation state and decides whether a reservation can become `CANCELLED`.
- Inventory owns equipment state and decides what a cancellation means for an inventory item.
- Reservation does not tell Inventory how to implement its lifecycle rule.

A command would read more like `ReleaseInventory` and would mean that Reservation is explicitly
requesting a particular action from Inventory. That creates tighter coupling: the sender needs to
know which action Inventory should execute. With an event, Reservation publishes its own fact and
Inventory independently applies the rule that a cancelled reservation releases a `RESERVED` item.

This also means the operation is eventually consistent. Reservation can finish its local
cancellation before Inventory consumes the event, so the item may remain `RESERVED` briefly.
There is no Inventory HTTP cancellation endpoint and no Kafka result event in this increment.

The Kafka listener is still an application "endpoint" in the architectural sense: it is an inbound
adapter, like an HTTP controller, but its input arrives from a Kafka topic rather than an HTTP
request.

## 2. Kafka concepts used by the implementation

### Topic

A topic is an append-only stream of records. This feature uses:

| Purpose | Topic |
| --- | --- |
| Reservation cancellation source | `rentflow.reservation.cancelled.v1` |
| Inventory dead-letter topic | `rentflow.inventory.reservation-cancellation.dlt.v1` |

Reservation will own publishing to the source topic. Inventory owns consuming the source topic and
publishing failures to the DLT.

### Partition and record key

Each topic has three partitions. A Kafka record belongs to exactly one partition, and ordering is
guaranteed only within a partition.

The record key is the exact, case-sensitive inventory serial number. Kafka hashes that key to
choose a partition. Events for one serial number therefore go to the same partition and keep their
Kafka order.

Three partitions allow up to three records for different keys to be processed concurrently by one
Inventory consumer group. Changing the partition count later is not a harmless scaling setting: it
can change the key-to-partition mapping and must be coordinated carefully.

### Consumer group

Inventory uses the consumer group:

```text
rentflow.inventory.reservation-cancellation.v1
```

Kafka gives each partition to at most one active consumer in a group. When several Inventory
instances run with this same group, they divide the partitions rather than all processing the same
record. A different application that also wants every cancellation event must use its own group.

### Offset

An offset is a record's position inside a partition. The consumer group's committed offset records
how far that group has processed.

The offset is not committed before the Inventory transaction succeeds. If the process fails after
the database commit but before the offset commit, Kafka can deliver the record again. The inbox
receipt makes that redelivery safe.

### At-least-once delivery

Kafka plus the future Reservation outbox provide at-least-once delivery: a committed event should
not be silently lost, but the same logical event can be delivered more than once.

This implementation does not claim Kafka exactly-once delivery. Instead, it provides an
effectively-once Inventory database effect for repeated deliveries of the same event ID.

## 3. End-to-end component flow

```text
Reservation database transaction (future work)
  -> reservation becomes CANCELLED
  -> outbox row is inserted in the same transaction

Reservation outbox relay (future work)
  -> publishes key=serialNumber, value=ReservationCancelled JSON

Kafka source topic
  -> ReservationCancellationListener
       -> ReservationCancellationEventConverter
            validates bytes, JSON, fields, key, and version
            computes canonical fingerprint and advisory-lock ID
       -> InventoryService.releaseAfterCancellation
            -> event-scoped PostgreSQL advisory lock
            -> durable inbox lookup
            -> inventory row lock
            -> optional status and history changes
            -> inbox receipt
       -> successful return allows source offset commit

Any listener exception
  -> DefaultErrorHandler
       -> deterministic rejection: publish directly to DLT
       -> unexpected/transient failure: retry, then publish to DLT
```

The important boundary is that Kafka-specific types stop at the converter/listener. The service
method receives a validated application model and contains no Kafka offset or serialization logic.

## 4. Event contract

The source value is a UTF-8 JSON object no larger than 4096 bytes:

```json
{
  "eventId": "d880f919-2b5c-4f7e-a56d-e047e7d932a6",
  "eventType": "ReservationCancelled",
  "eventVersion": 1,
  "occurredAt": "2026-09-15T15:30:00Z",
  "serialNumber": "DRILL-001"
}
```

The Kafka key for this example must be the UTF-8 bytes of:

```text
DRILL-001
```

The five known fields have these rules:

| Field | Rule |
| --- | --- |
| `eventId` | Canonical 36-character UUID syntax, UUID version 4, RFC 4122 variant |
| `eventType` | Exactly `ReservationCancelled`, including case |
| `eventVersion` | JSON integer exactly equal to `1` |
| `occurredAt` | An `Instant`-compatible timestamp ending in `Z` |
| `serialNumber` | `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` |

Unknown JSON properties are ignored. This permits additive version-1 evolution without forcing an
immediate Inventory deployment. Unknown properties are also excluded from the idempotency
fingerprint.

Missing fields, nulls, wrong JSON types, duplicate member names, trailing JSON content, invalid
UTF-8, an unsupported type/version, a missing key, or a key that differs from `serialNumber` are
rejected.

The message intentionally contains no `reservationId`. That keeps the agreed version-1 scope small,
but creates the stale-first-delivery limitation described later.

## 5. Maven dependencies

`pom.xml` adds three Kafka-related dependencies:

| Dependency | Purpose |
| --- | --- |
| `spring-boot-starter-kafka` | Kafka listener container, producer template, error handler, and Boot auto-configuration |
| `spring-boot-starter-kafka-test` | Spring Kafka test support |
| `testcontainers-kafka` | A real disposable Kafka broker for integration tests |

Versions remain managed by Spring Boot and the repository's existing Testcontainers version. No
separate Kafka client or Spring Kafka version is pinned.

## 6. Application configuration

`src/main/resources/application.yaml` now configures both the source consumer and the DLT producer.

### Consumer configuration

| Setting | Meaning |
| --- | --- |
| `bootstrap-servers` | Kafka broker addresses; locally defaults to `localhost:9092` |
| `group-id` | The Inventory use-case-specific consumer group |
| `enable-auto-commit: false` | Prevents Kafka's timer from committing work that the application has not completed |
| `auto-offset-reset: earliest` | A new group starts from retained records rather than skipping to the end |
| byte-array deserializers | Preserve original key/value bytes even when they contain invalid UTF-8 or JSON |
| `ack-mode: record` | Each successfully handled record is eligible for an offset commit individually |
| `sync-commits: true` | The listener waits for the broker to confirm the offset commit |
| `concurrency: 3` | Allows one listener container to use all three source partitions |
| `missing-topics-fatal: false` | Inventory can start before the Reservation-owned source topic exists |

Using byte arrays is important for poison-message handling. If Jackson deserialization happened in
Kafka's deserializer, a malformed event could fail before application code could classify it or
preserve its original bytes for the DLT.

### Producer configuration

Inventory produces only to its DLT in this feature. The producer uses byte-array serializers,
`acks=all`, and Kafka producer idempotence. This reduces the chance of losing or accidentally
duplicating a DLT record during a producer retry.

It does not make the DLT publication and PostgreSQL transaction one distributed transaction.

### Environment variables

| Variable | Default |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `INVENTORY_CANCELLATION_GROUP_ID` | `rentflow.inventory.reservation-cancellation.v1` |
| `INVENTORY_CANCELLATION_TOPIC` | `rentflow.reservation.cancelled.v1` |
| `INVENTORY_CANCELLATION_DLT` | `rentflow.inventory.reservation-cancellation.dlt.v1` |
| `INVENTORY_CANCELLATION_CONCURRENCY` | `3` |
| `INVENTORY_CANCELLATION_MAX_PAYLOAD_BYTES` | `4096` |
| `INVENTORY_CANCELLATION_MAX_ATTEMPTS` | `5` |
| `INVENTORY_CANCELLATION_INITIAL_BACKOFF` | `1s` |
| `INVENTORY_CANCELLATION_BACKOFF_MULTIPLIER` | `2` |
| `INVENTORY_CANCELLATION_MAX_BACKOFF` | `8s` |

`ReservationCancellationProperties` binds and validates the feature-specific values. Attempts must
be between one and five, durations cannot be negative, the multiplier cannot be below one, and the
maximum delay cannot be below the initial delay. Invalid configuration fails startup rather than
silently creating an unsafe retry policy.

## 7. `ReservationCancellationListener`: the inbound adapter

`ReservationCancellationListener` is placed in the controller layer because it is the Kafka
equivalent of an HTTP controller.

Its `@KafkaListener` method performs four small orchestration steps:

1. Count the listener invocation as `received`.
2. Convert and validate the raw `ConsumerRecord<byte[], byte[]>`.
3. Call `InventoryService.releaseAfterCancellation`.
4. Count the returned `released`, `already_available`, or `duplicate` outcome.

The listener deliberately does not access repositories or mutate entities itself.

`ReservationCancellationRejectedException` is rethrown unchanged because it already contains a
stable deterministic failure code. Any other runtime exception occurring after conversion is
wrapped in `ReservationCancellationProcessingException`, preserving the parsed event ID for later
DLT diagnostics while keeping the original cause for retry handling.

The listener does not manually acknowledge or commit an offset. Spring Kafka's container does that
after a successful return, using the configured record acknowledgement mode. Because
`releaseAfterCancellation` is transactional, its Spring proxy commits the database transaction
before control returns successfully to the listener.

## 8. `ReservationCancellationEventConverter`: strict boundary validation

The converter owns all wire-format concerns. It uses a dedicated Jackson mapper so its compatibility
rules do not weaken the application's strict HTTP JSON configuration.

Its processing order is:

1. Reject a null value or a value over the configured size limit.
2. Decode value bytes with a strict UTF-8 decoder.
3. Parse one JSON value with duplicate-member detection and trailing-token rejection.
4. Parse and validate the event ID first so later validation failures can carry that ID.
5. Read and validate the remaining known fields directly from the JSON tree.
6. Strictly decode and validate the Kafka key.
7. Require the key to equal the payload serial number exactly.
8. Compute a canonical fingerprint and an event-scoped PostgreSQL advisory-lock ID.
9. Return an immutable `ReservationCancellationEvent` application value.

### Canonical fingerprint

The fingerprint is lowercase SHA-256 hex over these normalized values in order:

1. the namespace `rentflow.reservation.cancelled.v1`;
2. normalized lowercase `eventId`;
3. `ReservationCancelled`;
4. version `1`;
5. `Instant.toString()` for `occurredAt`;
6. the exact serial number; and
7. the exact decoded Kafka key.

Each value is prefixed with its four-byte length before hashing. Length prefixes avoid ambiguous
concatenations such as `ab` + `c` versus `a` + `bc`.

This makes semantically equivalent JSON produce the same fingerprint even if it has different
whitespace, field ordering, UUID letter case, equivalent instant formatting, or additional unknown
properties. A meaningful known-field change produces a different fingerprint.

The event ID alone answers "have I seen this logical event?" The fingerprint answers "is this
really the same content, or did a producer accidentally reuse an ID?"

### Advisory-lock ID

The converter separately hashes an advisory-lock namespace plus the normalized event ID and takes
the first signed 64 bits. This ID is used only to serialize concurrent processing of the same event
ID. The inbox still uses the full UUID as its primary key, so a 64-bit lock hash collision can only
make unrelated work wait; it cannot treat two events as identical.

### Best-effort event-ID extraction

`extractEventId` safely attempts to read an ID from an otherwise invalid value. It is used only for
bounded DLT metadata and logging. Failure to extract an ID does not change error classification and
does not cause another processing failure.

## 9. Why the inbox is necessary

At first glance, setting `AVAILABLE` to `AVAILABLE` looks idempotent. It is idempotent only if no
other state change happens between deliveries.

Consider this sequence:

```text
1. Event E arrives for item DRILL-001 in RESERVED.
2. Inventory changes it to AVAILABLE.
3. The database commits, but the process fails before Kafka commits the offset.
4. A new reservation changes the item back to RESERVED.
5. Kafka redelivers event E.
```

Without an inbox, step 5 would see `RESERVED` and release the new reservation. Treating only
`AVAILABLE -> AVAILABLE` as a no-op does not protect this case.

With the inbox, event E's receipt was committed in step 3. The redelivery checks that receipt before
loading the item and returns `DUPLICATE`. The newer reservation remains `RESERVED`.

This is the Consumer Inbox Pattern. It complements the future Reservation Outbox Pattern:

- the producer outbox prevents a committed cancellation from being lost before Kafka publication;
- the Inventory inbox prevents a repeated Kafka delivery from repeating the database effect.

The receipt must be in the same transaction as the business effect. If it were saved separately,
the service could end up with a receipt but no release, or a release but no receipt.

## 10. Inbox database model

Flyway migration `V4__create_inventory_reservation_cancellation_inbox.sql` creates:

```text
inventory.inventory_reservation_cancellation_inbox
```

| Column | Purpose |
| --- | --- |
| `event_id uuid` | Primary key and logical event identity |
| `fingerprint varchar(64)` | Canonical lowercase SHA-256 content identity |
| `serial_number varchar(64)` | Item named by the event |
| `occurred_at timestamptz` | Timestamp supplied by Reservation |
| `outcome varchar(32)` | `RELEASED` or `ALREADY_AVAILABLE` |
| `processed_at timestamptz` | PostgreSQL-generated processing time |

Database constraints enforce the fingerprint format, serial-number format, and allowed outcomes.
The table is confined to the Inventory-owned schema.

There is intentionally no foreign key to the inventory item. Deleting an item must not delete the
receipt and make an old event processable again. There is also no payload column, reservation ID,
expiry column, cleanup job, or public API.

Receipts are retained indefinitely in this increment. That is the simplest safe behavior, but it
also means production capacity planning must account for continuous table growth.

`InventoryReservationCancellationReceipt` maps the row as an immutable JPA entity. Its assigned
fields are non-updatable, `processedAt` is database-generated, and `@Immutable` prevents managed
updates.

`ReservationCancellationInboxRepository` provides normal primary-key lookup and insertion through
`JpaRepository`. Its custom native query calls `pg_advisory_xact_lock`, which is released
automatically when the transaction commits, rolls back, loses its connection, or terminates.

## 11. The transactional service method

The new behavior is a separate `@Transactional` method on the existing `InventoryService`:

```text
releaseAfterCancellation(ReservationCancellationEvent event)
```

No `ReservationCancellationService` was added. The operation is an Inventory lifecycle use case,
and the existing service already owns inventory status changes and status history.

The method executes in this order:

1. Acquire the transaction-scoped advisory lock for the event ID.
2. Look up the inbox row by full `eventId`.
3. If the receipt exists and its fingerprint matches, return `DUPLICATE` immediately.
4. If the receipt exists but its fingerprint differs, reject with `EVENT_ID_REUSED`.
5. Load the item through `findForUpdateBySerialNumber`, which uses `PESSIMISTIC_WRITE`.
6. Reject a missing item with `INVENTORY_ITEM_NOT_FOUND`.
7. For `RESERVED`, set `AVAILABLE`, add one `RESERVED -> AVAILABLE` history row, and choose
   receipt outcome `RELEASED`.
8. For `AVAILABLE`, change nothing, add no history, and choose receipt outcome
   `ALREADY_AVAILABLE`.
9. Reject every other status with `INVALID_INVENTORY_STATUS`.
10. Save and flush the inbox receipt.
11. Return the processing outcome; Spring then commits the transaction.

The flush forces pending database writes and constraint checks to execute before the method
finishes. Any database exception rolls back the item, history, and receipt together.

The async method does not call the existing `transitionStatus` method. The HTTP method supports
atomic batches and correctly rejects same-state transitions. Cancellation has different semantics:
one event, one receipt, and a successful `AVAILABLE` no-op. Both paths still reuse the same item
entity, history entity, and pessimistic item-locking repository method.

### Why there are two locks

The advisory lock is event-scoped. It ensures concurrent records using the same event ID are
checked against one inbox state in a predictable order, even if malicious or broken records use
that ID with different serial numbers.

The pessimistic row lock is item-scoped. It serializes this transition with other status operations
for the same inventory item.

Different events for different items can run concurrently. Different events for the same item
serialize at the item row. Duplicate deliveries of the same event serialize earlier at the event
lock and normally avoid touching the item at all.

## 12. Database transaction versus Kafka offset

PostgreSQL and Kafka are two independent systems. There is no atomic transaction covering both.
The design handles the unavoidable failure window explicitly:

| Failure point | Database state | Kafka behavior |
| --- | --- | --- |
| Before/during database work | Transaction rolls back | Offset is not advanced; record is delivered again |
| After DB commit, before offset commit | Receipt and optional release/history exist | Record may be delivered again; inbox returns `DUPLICATE` |
| After DB and offset commits | Durable completed effect | Group continues with later records |

This is why the correct description is "at-least-once delivery with an effectively-once local
database effect," not exactly-once delivery.

## 13. Outcomes and failure classification

There are two outcome enums because they describe different things:

- `InventoryReservationCancellationOutcome` is persisted in the inbox and contains only
  `RELEASED` and `ALREADY_AVAILABLE`.
- `ReservationCancellationProcessingOutcome` is returned to the listener and also contains
  `DUPLICATE`.

`DUPLICATE` is not stored as a new receipt outcome because no new processing result is written; it
means an existing receipt suppressed this delivery.

`ReservationCancellationFailureCode` supplies bounded, operationally stable categories:

| Code | Meaning | Retry? |
| --- | --- | --- |
| `INVALID_EVENT` | Invalid bytes, JSON, known field, timestamp, serial, key, or size | No |
| `UNSUPPORTED_EVENT` | Valid structure but another event type or version | No |
| `EVENT_ID_REUSED` | Existing event ID has a different canonical fingerprint | No |
| `INVENTORY_ITEM_NOT_FOUND` | The serial number does not exist | No |
| `INVALID_INVENTORY_STATUS` | Item is neither `RESERVED` nor `AVAILABLE` | No |
| `PROCESSING_RETRIES_EXHAUSTED` | An otherwise retryable exception failed every attempt | Four retries, then DLT |

Deterministic failures are not retried because repeating the same record immediately will not fix
them and would block later records in the partition. Other runtime failures are treated as
potentially transient, for example an unexpected database or infrastructure problem.

Rejected events do not receive inbox rows. The DLT record is the durable evidence of their failure.

## 14. Retry handling

`InventoryKafkaConfig` creates a Spring Kafka `DefaultErrorHandler`.

With the defaults, one record can have five total attempts:

```text
initial attempt
  -> wait 1 second  -> retry 1
  -> wait 2 seconds -> retry 2
  -> wait 4 seconds -> retry 3
  -> wait 8 seconds -> retry 4
  -> DLT if the final attempt still fails
```

These are blocking retries. The affected source partition waits while the record is retried. This
is appropriate for the short bounded schedule and preserves record order. Retry topics and
`@RetryableTopic` were not introduced.

Every failed service attempt has already rolled back its database transaction before the error
handler waits. A retry starts a new listener invocation and a new transaction.

`ReservationCancellationRejectedException` is explicitly configured as non-retryable. The retry
listener increments the `retry` metric only when another retry will actually occur.

## 15. Dead-letter handling

After a deterministic rejection or exhausted retries, `DeadLetterPublishingRecoverer` publishes
the original key and value bytes to:

```text
rentflow.inventory.reservation-cancellation.dlt.v1
```

The DLT record uses the same partition number as the source record. Therefore the DLT has the same
three-partition count as the source topic.

These stable UTF-8 diagnostic headers are added:

| Header | Content |
| --- | --- |
| `rentflow.dlt.source-topic` | Original topic |
| `rentflow.dlt.source-partition` | Original partition number |
| `rentflow.dlt.source-offset` | Original offset |
| `rentflow.dlt.consumer-group` | Inventory consumer group |
| `rentflow.dlt.failure-code` | Stable failure category |
| `rentflow.dlt.failed-at` | UTC time of recovery |
| `rentflow.dlt.event-id` | Included only if a valid ID can be extracted |

The custom recoverer deliberately removes framework exception headers and does not copy arbitrary
source headers. This prevents exception messages, stack traces, and repeated recovery metadata from
causing unbounded or sensitive headers.

The observed Kafka template also adds valid W3C `traceparent`/`tracestate` for the DLT producer
span within the originating trace. Baggage and arbitrary source headers are excluded. See the
observability section in `README.md` for the trace and log contract.

The error log includes source topic, partition, offset, failure code, and optional event ID. It does
not include the original key or payload.

The recoverer is configured to fail when Kafka does not confirm the DLT send. Only a confirmed DLT
publication allows the error handler to consider the source record handled and commit its offset.
If DLT publication fails, the source offset remains uncommitted and recovery can be attempted again.

A crash after DLT publication but before source offset commit can produce a duplicate DLT record.
This is preferable to losing the failure.

## 16. Topic lifecycle

| Setting | Source | DLT |
| --- | --- | --- |
| Partitions | 3 | 3 |
| Cleanup | `delete` | `delete` |
| Retention | 7 days | 30 days |
| Local replication | 1 | 1 |
| Production target replication | 3 | 3 |
| Production target minimum ISR | 2 | 2 |

The common local broker disables automatic topic creation. The source topic is created explicitly
because it belongs to the Reservation contract. Inventory creates its DLT through a `NewTopic` bean
only when the `local` Spring profile is active.

Production infrastructure must provision both topics and ACLs. Application identities should not
create or alter production topics.

Seven-day source retention bounds automatic recovery. If Inventory remains unable to consume past
that window, recovery depends on the future Reservation outbox retaining and republishing the event
or on a separate reconciliation process.

## 17. Metrics and logs

All feature counters use one bounded metric family:

```text
inventory.reservation.cancellation.records{outcome="..."}
```

Possible outcomes are:

| Outcome | Incremented when |
| --- | --- |
| `received` | The listener is invoked, including a retry/redelivery |
| `released` | A real release transaction succeeds |
| `already_available` | A successful no-op receipt is committed |
| `duplicate` | A matching receipt suppresses processing |
| `retry` | The error handler will make another attempt |
| `dead_letter` | Kafka confirms DLT publication |
| `dead_letter_publish_failure` | DLT recovery itself fails |

Event IDs and serial numbers are deliberately not metric labels. Labels create a time series for
each distinct value, so putting per-event data in labels would create unbounded cardinality and
eventually harm the monitoring system.

The counters are registered with the configured Micrometer `MeterRegistry`. The current HTTP
Actuator exposure remains limited to health endpoints; a production metrics registry/exporter is a
separate operational configuration concern.

Recommended alerts are any increase in DLT or DLT-publication failures, plus sustained consumer
lag early enough to respond before the seven-day source retention limit.

## 18. Local common-stack changes

The supporting `rent-flow-common` repository was updated as part of the implementation:

- Inventory receives `KAFKA_BOOTSTRAP_SERVERS=rentflow-kafka:19092`.
- Inventory runs with the `local` profile so its DLT topic bean is active.
- Inventory waits for the Kafka health check before starting.
- The container smoke test explicitly creates and validates the source topic.
- The smoke test checks source and DLT partition counts and retention settings.
- It creates a `RESERVED` item, publishes a keyed cancellation, waits for `AVAILABLE`, and verifies
  the public history API.
- It reserves the item again and republishes the same event. The item must remain `RESERVED`, and
  no additional release history may appear. This is the key end-to-end inbox proof.

The common repository continues to own the local broker, KRaft configuration, listeners, volume,
and health check. Inventory owns only its consumer behavior and DLT declaration.

## 19. Test coverage

### Converter unit tests

`ReservationCancellationEventConverterTest` verifies:

- exact contract conversion;
- canonical fingerprints for equivalent JSON;
- ignored unknown properties;
- maximum payload and serial lengths;
- malformed JSON, duplicate fields, invalid UTF-8, bad keys, tombstones, and oversize values;
- unsupported event type/version classification;
- UUID v4 enforcement; and
- fingerprint changes for semantic changes.

### Configuration unit tests

`ReservationCancellationPropertiesTest` verifies the documented defaults and invalid attempt,
duration, multiplier, and maximum-backoff bounds.

### Service unit tests

`InventoryCancellationServiceTest` tests the cancellation method on the existing
`InventoryService`; despite the test filename, no separate production cancellation service exists.
It verifies lock/lookup/write order, release history, `AVAILABLE` no-op behavior, duplicate
short-circuiting, event-ID conflict rejection, missing items, and incompatible statuses.

### PostgreSQL integration tests

`ReservationCancellationIT` uses real PostgreSQL to verify:

- item, history, and receipt commit together;
- a duplicate does not release a later reservation;
- `AVAILABLE` creates only a no-op receipt;
- a receipt constraint failure rolls back item and history changes; and
- conflicts and deterministic business failures add no state.

`MigrationIT` verifies that V4 is owned by the Inventory role, exists only in the `inventory`
schema, has the expected columns and only its primary-key index, has no item foreign key, generates
`processed_at`, and enforces its constraints.

### Kafka integration tests

`ReservationCancellationKafkaIT` runs a real Apache Kafka 4.3.1 container plus real PostgreSQL. It
verifies:

- release, history, inbox persistence, and committed source offset;
- formatting-equivalent duplicate suppression after the item is reserved again;
- a deterministic missing-item failure reaching the same DLT partition without retry;
- exact original DLT key/value and exact bounded headers; and
- event-ID conflict quarantine without replacing the original receipt.

### Architecture and regression checks

`ArchitectureTest` permits the configuration layer to depend on converter and model types needed by
the error handler while retaining the project's layer boundaries. Existing `InventoryServiceTest`
instances were updated for the new inbox repository constructor dependency.

The completed implementation was checked with:

```text
mvn -B -ntp clean verify
```

The build completed successfully with 165 tests, zero failures, and zero errors. The combined
common-stack container smoke test also completed with:

```text
Combined container smoke verification passed
```

## 20. Known limitations and intentional non-features

### A delayed first delivery can still be stale

The inbox prevents repeated delivery of a completed event. It cannot identify a stale *first*
delivery because the event has no `reservationId` or ownership token.

For example, if a cancellation event is delayed, an operator releases the item through another
path, and a different reservation later reserves it, the first arrival of the old event can still
release the item. Solving that requires reservation ownership information or another concurrency
contract and is intentionally deferred.

### No automatic DLT replay

The DLT is a quarantine, not a retry loop. Operators must diagnose the failure and current business
state before replaying. A safe replay preserves the original key, value, and event ID.

- Do not replay an invalid or unsupported event unchanged.
- Investigate `EVENT_ID_REUSED` as a producer identity defect.
- For missing-item or invalid-status failures, verify that replay is still correct without
  reservation ownership information.

### No result event

Inventory does not publish `InventoryReleased` or a cancellation acknowledgement. Consumers can
observe real transitions through the existing history API and operators can observe metrics, logs,
consumer lag, and the DLT.

### No inbox cleanup

Receipts never expire in this increment because deleting them would allow old retained or replayed
events to run again. Any future cleanup policy must be designed together with Kafka retention,
producer outbox retention, backup/replay practices, and the maximum possible event lifetime.

### No distributed transaction

Kafka and PostgreSQL are intentionally not placed in one distributed transaction. The explicit
outbox/inbox model handles their failure windows with simpler, observable local transactions.

## 21. File-by-file map

| File | Responsibility |
| --- | --- |
| `pom.xml` | Runtime and test Kafka dependencies |
| `src/main/resources/application.yaml` | Consumer, producer, listener, topic, payload, and retry defaults |
| `config/ReservationCancellationProperties.java` | Typed and validated feature configuration |
| `config/InventoryKafkaConfig.java` | Kafka enablement, retry/error handler, DLT recovery, DLT headers, local DLT topic |
| `controller/ReservationCancellationListener.java` | Kafka inbound adapter and success metrics |
| `converter/ReservationCancellationEventConverter.java` | Raw byte decoding, contract validation, fingerprinting, lock ID, event-ID extraction |
| `model/ReservationCancellationEvent.java` | Validated application input to the service |
| `model/ReservationCancellationProcessingOutcome.java` | Listener-visible success outcomes |
| `model/InventoryReservationCancellationOutcome.java` | Persisted receipt outcomes |
| `model/ReservationCancellationFailureCode.java` | Stable bounded DLT categories |
| `model/ReservationCancellationRejectedException.java` | Non-retryable deterministic rejection |
| `model/ReservationCancellationProcessingException.java` | Retryable wrapper preserving a parsed event ID |
| `model/InventoryReservationCancellationReceipt.java` | Immutable JPA inbox entity |
| `repository/ReservationCancellationInboxRepository.java` | Receipt persistence and PostgreSQL advisory lock |
| `service/InventoryService.java` | Transactional idempotency and lifecycle algorithm |
| `db/migration/V4__create_inventory_reservation_cancellation_inbox.sql` | Inventory-owned inbox table and constraints |
| `README.md` | Public event contract, operations, topic setup, and replay guidance |
| `ArchitectureTest.java` | Layer-boundary update for Kafka configuration dependencies |
| `MigrationIT.java` | V4 migration and database-contract verification |
| `ReservationCancellationEventConverterTest.java` | Wire-contract unit tests |
| `ReservationCancellationPropertiesTest.java` | Configuration validation tests |
| `InventoryCancellationServiceTest.java` | Existing `InventoryService` cancellation unit tests |
| `ReservationCancellationIT.java` | Real-PostgreSQL transaction and idempotency tests |
| `ReservationCancellationKafkaIT.java` | Real-Kafka end-to-end consumer and DLT tests |

## 22. How to reason about one successful record

For a final concrete walkthrough, assume `DRILL-001` is `RESERVED` and event E arrives:

1. Kafka chooses a source partition from key `DRILL-001`.
2. One Inventory consumer in the group receives E at a specific offset.
3. The listener increments `received`.
4. The converter validates the original key/value bytes.
5. It creates a normalized application event, fingerprint F, and lock ID L.
6. `InventoryService` starts a PostgreSQL transaction.
7. It acquires advisory lock L.
8. No inbox row exists for E.
9. It locks the `DRILL-001` item row.
10. The item is `RESERVED`, so it becomes `AVAILABLE`.
11. One `RESERVED -> AVAILABLE` history row is inserted.
12. One inbox row `(E, F, DRILL-001, RELEASED)` is inserted.
13. The persistence context is flushed and the transaction commits.
14. The service returns `RELEASED` and the listener increments `released`.
15. The listener returns successfully.
16. Spring Kafka synchronously commits the next offset for that partition.

If step 16 never completes, Kafka can repeat steps 2-7. Step 8 then finds `(E, F)`, the service
returns `DUPLICATE` without reading the item, and the offset can safely advance on that delivery.
