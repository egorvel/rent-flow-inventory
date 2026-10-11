# Async Reservation Cancellation Inventory Consumer Design

Status: Design resolved; implementation tasks documented.

## 1. Scope and architecture

### 1.1 Design boundary

This feature adds one Kafka inbound adapter to Inventory. It consumes a
`ReservationCancelled` integration event and converges the named inventory item on `AVAILABLE`.
It does not add an Inventory HTTP operation, change the existing synchronous status-transition
contract, or add a Kafka result event.

The design records the event contract and assumptions about its future Reservation producer so
that Inventory can be implemented and tested against a stable boundary. The concrete Reservation
cancellation endpoint, outbox table, relay implementation, publisher retry schedule, and outbox
cleanup are deliberately left to a feature spec in the Reservation repository. In particular,
this design does not prescribe polling versus change-data capture on the producer side.

The source topic is an at-least-once transport. Inventory provides an effectively-once database
effect for repeated delivery of the same stable `eventId`; it does not claim exactly-once Kafka
delivery or use a distributed transaction between Kafka and PostgreSQL.

### 1.2 Resolved design decisions

| Decision | Resolution and rationale |
| --- | --- |
| Interaction type | Consume the past-tense `ReservationCancelled` fact; cancellation is owned by Reservation and release behavior is owned by Inventory. |
| Consistency | Cancellation and inventory release are eventually consistent; Reservation does not wait for Inventory consumption. |
| Message granularity | One Kafka record represents one cancelled item, including when several reservations are cancelled together. |
| Partition key | Use the exact case-sensitive Inventory serial number, preserving per-item Kafka order. |
| Wire representation | Use bounded UTF-8 JSON with an explicit type and integer version; do not emit or consume Java type headers. |
| Compatibility | Validate known fields strictly and ignore unknown object properties so additive version-1 changes remain consumable. |
| Idempotency | Persist an indefinite inbox receipt under `eventId`; duplicate delivery never reevaluates the item's current state. |
| Already available | Record `ALREADY_AVAILABLE` as a successful no-op without creating false same-state history. |
| Reservation identity | Carry no `reservationId` or ownership token; the accepted stale-first-delivery limitation remains explicit. |
| Retry | Use four blocking retries per cycle after the initial attempt, with default delays of 1, 2, 4, and 8 seconds; retain Spring's state resets on exception changes and failed recovery. |
| Recovery | Publish deterministic failures immediately and exhausted failures eventually to an Inventory-owned dead-letter topic. |
| Feedback | Publish no result event; logs, metrics, the DLT, and the existing history API are the observable surfaces. |

### 1.3 Runtime component flow

The Inventory runtime path follows the existing package and layer direction:

```text
Kafka record<byte[], byte[]>
  -> ReservationCancellationListener                  (controller: inbound adapter)
       -> ReservationCancellationEventConverter       (converter: parse and validate)
       -> InventoryService.releaseAfterCancellation   (service: transaction and outcome)
            -> ReservationCancellationInboxRepository (repository: advisory lock and receipt)
            -> InventoryRepository                     (repository: item row lock)
            -> InventoryStatusHistoryRepository        (repository: transition history)

listener exception
  -> DefaultErrorHandler
       -> blocking retry, or
       -> DeadLetterPublishingRecoverer
            -> DLT record<byte[], byte[]>
```

`ReservationCancellationListener` resides in `controller` because it is an inbound transport
adapter analogous to an HTTP controller. It contains no persistence logic. The existing
`InventoryService` gains a focused `releaseAfterCancellation(ReservationCancellationEvent)`
method alongside its existing lifecycle operations. The method accepts the validated application
model rather than a Kafka type, so Kafka conversion, retry, offset, and DLT concerns remain in the
inbound adapter and configuration.

The new method does not call or change `transitionStatus`. That operation retains its existing
HTTP batch semantics, including rejection of same-state transitions. Cancellation needs different
application semantics: one event, a durable inbox receipt, and an `AVAILABLE` no-op. Both methods
reuse the same item mutation, history entity, and row-locking repository pattern without adding a
new service abstraction.

Kafka bean wiring belongs in `com.rentflow.config`. `ArchitectureTest` is extended only as needed
to permit configuration to reference the cancellation rejection exception type; controllers still
cannot access repositories, services remain the only orchestrators of writes, and no package cycle
is introduced.

## 2. Kafka contract

### 2.1 Names and ownership

| Purpose | Stable name | Owner |
| --- | --- | --- |
| Cancellation source topic | `rentflow.reservation.cancelled.v1` | Reservation contract and publisher |
| Inventory consumer group | `rentflow.inventory.reservation-cancellation.v1` | Inventory |
| Inventory dead-letter topic | `rentflow.inventory.reservation-cancellation.dlt.v1` | Inventory |

The `.v1` suffix is a wire-contract major version, not an application release version. Additive
fields do not create a new topic. A breaking change requires a new topic/version and an explicit
consumer migration period.

Every logical consumer use case needs its own group. All running Inventory instances use the same
group above, so Kafka assigns each source partition to at most one group member at a time. A new
independent consumer must use a different group rather than sharing Inventory's offsets.

### 2.2 Version-1 source record

The record value is a UTF-8 JSON object of at most 4096 bytes:

```json
{
  "eventId": "d880f919-2b5c-4f7e-a56d-e047e7d932a6",
  "eventType": "ReservationCancelled",
  "eventVersion": 1,
  "occurredAt": "2026-09-15T15:30:00Z",
  "serialNumber": "DRILL-001"
}
```

| Field | JSON type | Contract |
| --- | --- | --- |
| `eventId` | string | One canonical 36-character UUID v4; the producer emits lowercase hex and the consumer accepts either hex case. |
| `eventType` | string | Exactly `ReservationCancelled`, case-sensitive. |
| `eventVersion` | integer | Exactly `1`; numeric strings and fractional values are invalid. |
| `occurredAt` | string | An ISO-8601/RFC 3339 UTC instant accepted by `Instant.parse`, with a trailing `Z`. |
| `serialNumber` | string | Matches `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` exactly; no trimming or case folding. |

The Kafka key is the UTF-8 encoding of the exact `serialNumber` value. A null key, invalid UTF-8,
invalid serial syntax, or any byte difference between the decoded key and payload serial makes the
record invalid. The producer emits no `reservationId`, correlation identifier, customer, order,
or Java serialization/type header.

The payload must be a non-null JSON object. Null values, missing known fields, duplicate JSON
member names, wrong JSON types, trailing non-whitespace content, and values outside the table are
invalid. A Kafka tombstone is not a cancellation event and is invalid.

### 2.3 Parsing, validation, and compatibility

The consumer uses `ByteArrayDeserializer` for both key and value. This keeps the original bytes
available for dead-letter publication even when UTF-8 or JSON is invalid and avoids a poison record
failing before the application can classify it. The DLT producer correspondingly uses
`ByteArraySerializer` for both key and value.

`ReservationCancellationEventConverter` performs these steps without opening a database
transaction:

1. Reject a null value, a value larger than 4096 bytes, or key/value bytes that are not strict
   UTF-8.
2. Parse the value with a dedicated Jackson mapper configured for duplicate-member detection,
   Java time support, and ignored unknown properties. The application's existing strict HTTP
   `ObjectMapper` behavior is not changed.
3. Parse and validate `eventId` first, then read the other four known fields directly from the JSON
   tree and validate their presence, JSON types, exact constants, timestamp format, and serial
   syntax. Parsing the identifier first lets later validation failures retain a validated event ID
   for bounded diagnostics without parsing it twice.
4. Decode and validate the record key, then require exact equality with `serialNumber`.
5. Produce an immutable `ReservationCancellationEvent` model value containing the normalized UUID,
   parsed `Instant`, exact serial number, and canonical fingerprint from §4.3.

Unknown properties are ignored by the consumer and excluded from the fingerprint. This permits a
producer to add optional version-1 information without making existing Inventory instances reject
the record. Removing or changing a required field, changing a JSON type, or altering the meaning
of a field is breaking and requires a new major event version.

### 2.4 Topic configuration

| Setting | Source topic | Dead-letter topic |
| --- | --- | --- |
| Partitions | `3` | `3` |
| Cleanup policy | `delete` | `delete` |
| Retention | 7 days (`604800000` ms) | 30 days (`2592000000` ms) |
| Local/test replication factor | `1` | `1` |
| Production target replication factor | `3` | `3` |
| Production target minimum in-sync replicas | `2` | `2` |

Retention makes records eligible for Kafka deletion after the configured duration; it is not a
business timestamp guarantee or an exact deletion schedule. Neither topic is compacted because
each cancellation fact and each failed delivery is a distinct record rather than the latest value
for a key.

The DLT has the same partition count because recovery retains the original partition number. The
source producer uses `acks=all` and Kafka producer idempotence. Inventory's DLT producer uses the
same safety settings. Production infrastructure provisions both topics before deployment and
grants Reservation write access to the source plus Inventory read access to the source and write
access to the DLT.

Seven-day source retention bounds automatic recovery. Operations must restore consumption or
republish the retained outbox event before the source record expires. A sustained-lag alert fires
well before that boundary; unlimited outage recovery would require a longer-lived producer outbox
or a separate reconciliation feature and is outside this design.

### 2.5 Ordering and accepted limitation

Records with the same serial-number key remain ordered within the source topic. Three partitions
allow concurrent work for different items; Inventory listener concurrency is three so one
instance can use all partitions, while additional instances divide those partitions among the
same group.

Kafka ordering does not include synchronous HTTP status changes or manual database operations.
The inbox prevents a completed event's duplicate from releasing a subsequent reservation, even if
the item cycles through `AVAILABLE` and back to `RESERVED`. Without a `reservationId` or ownership
token, Inventory cannot identify a first delivery that became stale after an out-of-band release
and reassignment. Operators must consider this limitation before replaying business-state DLT
records.

The partition count is fixed for version 1. Increasing it changes the key-to-partition mapping and
can break ordering with records already in the topic, so any increase requires a coordinated
producer/consumer migration rather than an incidental runtime override.

## 3. External Reservation producer assumptions

### 3.1 Assumed producer contract

The Reservation implementation is external to this feature. The Inventory design assumes a future
producer will satisfy the following contract before the real end-to-end workflow is enabled:

- a successful `HELD`-to-`CANCELLED` or `CONFIRMED`-to-`CANCELLED` change and one outbox record for
  that cancelled item commit in the same Reservation-owned PostgreSQL transaction;
- a rollback leaves neither the cancellation nor its outbox event;
- a write that does not make one of those two transitions emits no cancellation event;
- a transaction cancelling several reservations creates one logical event per item;
- every publication attempt for one outbox row preserves the event ID, key, and value;
- the source record follows §2.2, uses the serial-number key, and is published with `acks=all` and
  producer idempotence; and
- Reservation completes the cancellation without waiting for Inventory processing.

The specific HTTP route, idempotency behavior of that route, outbox schema, publisher process,
claim/locking strategy, delivery timeouts, polling or CDC choice, publication backoff, and
published-row cleanup belong in the Reservation feature's own requirements, design, and tasks.
These are integration assumptions, not acceptance criteria for this Inventory consumer plan.
Inventory never reads the Reservation schema or calls a private Reservation implementation API.

### 3.2 End-to-end delivery semantics

The producer outbox prevents a committed cancellation from being lost between PostgreSQL and
Kafka. It may publish the same logical event more than once if the broker acknowledges a send but
the producer cannot persist its publication result. That ambiguity is expected: the outbox keeps
the event ID stable, and the Inventory inbox makes repeated consumption safe.

No Kafka transaction attempts to include either service's PostgreSQL work. Inventory publishes no
acknowledgement or result event. A Reservation API response therefore reports only Reservation's
local cancellation outcome; Inventory completion is observed through Inventory history and
operational telemetry.

## 4. Inventory processing design

### 4.1 Dependencies and application configuration

Inventory adds the Spring Boot-managed `spring-boot-starter-kafka` dependency. Tests add
`spring-boot-starter-kafka-test` and Testcontainers' `testcontainers-kafka` module at the existing
Testcontainers version. Spring Boot 4.1.0 manages Spring Kafka 4.1.0, so no independent Spring
Kafka version property is added.

The service configuration keeps addresses and operational values externalized:

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: ${INVENTORY_CANCELLATION_GROUP_ID:rentflow.inventory.reservation-cancellation.v1}
      enable-auto-commit: false
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.ByteArrayDeserializer
      value-deserializer: org.apache.kafka.common.serialization.ByteArrayDeserializer
    producer:
      acks: all
      key-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
      value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
      properties:
        enable.idempotence: true
    listener:
      ack-mode: record
      concurrency: ${INVENTORY_CANCELLATION_CONCURRENCY:3}
      sync-commits: true
      missing-topics-fatal: false

inventory:
  kafka:
    reservation-cancellation:
      topic: ${INVENTORY_CANCELLATION_TOPIC:rentflow.reservation.cancelled.v1}
      dead-letter-topic: ${INVENTORY_CANCELLATION_DLT:rentflow.inventory.reservation-cancellation.dlt.v1}
      max-payload-bytes: ${INVENTORY_CANCELLATION_MAX_PAYLOAD_BYTES:4096}
      retry:
        max-attempts: ${INVENTORY_CANCELLATION_MAX_ATTEMPTS:5}
        initial-backoff: ${INVENTORY_CANCELLATION_INITIAL_BACKOFF:1s}
        multiplier: ${INVENTORY_CANCELLATION_BACKOFF_MULTIPLIER:2}
        max-backoff: ${INVENTORY_CANCELLATION_MAX_BACKOFF:8s}
```

`auto-offset-reset=earliest` is required: a newly deployed Inventory group must process
cancellations that were published while it was absent rather than begin at the end of the topic.
`enable-auto-commit=false` and record acknowledgement keep offset progression under the listener
container's control. Successful returns are committed synchronously per record. A source topic
temporarily missing during producer rollout does not crash Inventory; the listener waits for topic
metadata while automatic topic creation remains disabled.

### 4.2 Inbound adapter and outcome

`ReservationCancellationListener` is a record listener for the source topic. It receives the full
`ConsumerRecord<byte[], byte[]>`, increments the `received` metric, converts the raw record, and
calls `InventoryService.releaseAfterCancellation`. It increments `released`, `already_available`,
or `duplicate` only after the transactional method returns successfully. It performs no manual
offset seek or commit.

The method returns a small immutable outcome enum:

| Outcome | Meaning |
| --- | --- |
| `RELEASED` | This attempt committed `RESERVED` to `AVAILABLE`, history, and its inbox receipt. |
| `ALREADY_AVAILABLE` | This attempt found `AVAILABLE`, made no lifecycle change, and committed its inbox receipt. |
| `DUPLICATE` | The same event ID and fingerprint had already completed; current inventory was not read. |

Contract and deterministic business failures throw
`ReservationCancellationRejectedException`, which carries a stable failure code and a nullable
parseable event ID but never stores the raw payload in its message. Spring's standard fatal
exceptions also bypass retry; remaining exceptions are retryable by default. After successful
conversion, the listener wraps an otherwise retryable processing exception with the already parsed
event ID and original cause so exhausted recovery can
include the identifier without reparsing or logging the payload. Before conversion completes, the
converter attaches an event ID only when that field was independently parseable.

### 4.3 Canonical fingerprint and event lock

The fingerprint is lowercase SHA-256 hex over a versioned, length-prefixed UTF-8 sequence. The
sequence contains, in order:

1. namespace `rentflow.reservation.cancelled.v1`;
2. normalized lowercase UUID text;
3. exact event type;
4. decimal event version;
5. `Instant.toString()` for the parsed occurrence time;
6. exact serial number; and
7. decoded Kafka key.

Each component is prefixed by its four-byte big-endian byte length, preventing ambiguous
concatenation. JSON whitespace, JSON member order, accepted UUID hex case, and unknown JSON fields
do not affect the fingerprint. Serial-number case does affect it.

Before reading the inbox, the service acquires a blocking PostgreSQL transaction-scoped advisory
lock. Its signed 64-bit lock ID is the first eight SHA-256 bytes of the separate namespace
`inventory.reservation-cancellation.event-lock` plus the normalized event UUID. The UUID remains
the actual inbox primary key. A hash collision can only serialize two otherwise unrelated events;
it cannot make one event appear to be another.

The lock prevents concurrent deliveries that reuse one event ID, including malicious records with
different serials, from reaching different item locks simultaneously. PostgreSQL releases it on
commit, rollback, connection loss, or process death. It waits rather than returning a busy
outcome; the wait is an infrastructure failure only if PostgreSQL itself times out or fails.

### 4.4 Transactional processing algorithm

`InventoryService.releaseAfterCancellation` is a separate `@Transactional` method and executes in
this order:

1. Acquire the event advisory lock from §4.3.
2. Load the inbox row by `eventId`.
3. If a row exists with the same fingerprint, return `DUPLICATE` without loading inventory,
   changing state, or adding history.
4. If a row exists with a different fingerprint, throw the non-retryable
   `EVENT_ID_REUSED` rejection without changing the existing row.
5. Load the named item using `InventoryRepository.findForUpdateBySerialNumber` and hold its
   pessimistic write lock through transaction completion.
6. If the item is absent, throw the non-retryable `INVENTORY_ITEM_NOT_FOUND` rejection.
7. If its status is `RESERVED`, change only status to `AVAILABLE`, add one existing
   `InventoryStatusHistory(serialNumber, RESERVED, AVAILABLE)` row, and prepare a `RELEASED`
   receipt.
8. If its status is `AVAILABLE`, make no item or history change and prepare an
   `ALREADY_AVAILABLE` receipt.
9. For `RENTED`, `INSPECTION_REQUIRED`, `UNDER_MAINTENANCE`, or `RETIRED`, throw the non-retryable
   `INVALID_INVENTORY_STATUS` rejection.
10. Save the inbox receipt and flush the persistence context so item, history, and receipt failures
    surface before returning the outcome.

The transaction commits after the proxied service method returns. Any exception or commit failure
rolls back all three Inventory-owned effects. Rejected events create no inbox receipt; the DLT is
their durable failure record.

The asynchronous path reuses the existing `InventoryItem.setStatus` mutation and
`InventoryStatusHistory` entity but does not call `InventoryService.transitionStatus`. The latter
implements atomic HTTP batches and treats a same-state target as a conflict, while cancellation
needs a single-event inbox and the special `AVAILABLE` no-op. Both paths still obtain the same item
row lock and record the same real `RESERVED`-to-`AVAILABLE` transition.

### 4.5 Concurrency with other lifecycle operations

Different cancellation event IDs for the same serial serialize on the item row. The second
transaction evaluates the newly committed status: normally it records `ALREADY_AVAILABLE`. The
same event ID serializes first on the advisory lock and returns `DUPLICATE` from the inbox without
touching the item.

The synchronous `PATCH` path uses the same pessimistic item lookup, so it and cancellation process
in database lock order. If cancellation observes `RESERVED`, it releases the item; if it observes
that another committed workflow has moved the item to `RENTED` or another incompatible status, it
rejects to the DLT. If another operation made the item `AVAILABLE`, cancellation records the
successful no-op.

The existing full-replacement `PUT` and delete paths retain their current locking semantics. Their
out-of-band interaction with this event consumer remains subject to PostgreSQL row-update
serialization and the stale-first-delivery limitation in §2.5. No reservation-ownership column,
optimistic version, or cross-service lookup is added.

### 4.6 Database and offset failure windows

The Kafka listener container uses record acknowledgement and never advances the failed record's
offset before the transactional method returns and commits:

| Failure point | Database result | Kafka result on restart/redelivery |
| --- | --- | --- |
| Before or during the database transaction | Transaction rolls back | Source offset is not advanced; the event is attempted again. |
| After database commit but before offset commit | Receipt and optional state/history are durable | Source record may return; the inbox produces `DUPLICATE`. |
| After database and offset commits | Receipt and optional state/history are durable | Normal consumption continues after the record. |

This is at-least-once consumption with an effectively-once local effect. Spring Kafka's Kafka
transactional exactly-once mode is not used because it cannot make the external PostgreSQL commit
and Kafka offset one atomic resource transaction. The inbox is the explicit bridge across that
failure window.

## 5. Inbox persistence

### 5.1 Append-only V4 migration

V1-V3 remain unchanged. V4 creates only this Inventory-owned table:

```sql
CREATE TABLE inventory.inventory_reservation_cancellation_inbox (
    event_id uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL,
    serial_number varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    outcome varchar(32) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_inventory_reservation_cancellation_inbox_fingerprint
        CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_inventory_reservation_cancellation_inbox_serial
        CHECK (serial_number ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT chk_inventory_reservation_cancellation_inbox_outcome
        CHECK (outcome IN ('RELEASED', 'ALREADY_AVAILABLE'))
);
```

The table has no item foreign key: deleting an item must not delete its durable event receipt, and
a retained receipt must continue to suppress an old duplicate. There is no expiry column, cleanup
index, payload column, reservation identifier, public query API, or automatic deletion job. The
primary key is the only index because processing looks up receipts only by event ID.

`occurred_at` preserves the producer fact's timestamp. `processed_at` uses PostgreSQL
`clock_timestamp()` so it records insertion time rather than the transaction-start time. Neither
timestamp participates in lifecycle decisions, replay expiry, or freshness rejection.

### 5.2 Entity and repository

`InventoryReservationCancellationReceipt` maps the table as an immutable JPA entity:

| Java property | Database column | Mapping |
| --- | --- | --- |
| `eventId` | `event_id` | `UUID`, assigned primary key, not updatable |
| `fingerprint` | `fingerprint` | 64-character lowercase SHA-256 hex, not updatable |
| `serialNumber` | `serial_number` | exact case-sensitive serial, not updatable |
| `occurredAt` | `occurred_at` | event `Instant`, not updatable |
| `outcome` | `outcome` | string enum `RELEASED` or `ALREADY_AVAILABLE`, not updatable |
| `processedAt` | `processed_at` | PostgreSQL-generated `Instant`, not insertable or updatable |

It has a protected no-argument constructor, one production constructor for all assigned fields,
getters, and no mutation method. `@Immutable` prevents managed updates.

`ReservationCancellationInboxRepository` extends `JpaRepository` for primary-key lookup, insert,
and flush. Its one native query invokes `pg_advisory_xact_lock` for the lock ID from §4.3. No
repository method deletes or updates a receipt.

## 6. Retry and dead-letter design

### 6.1 Stable failure classification

`ReservationCancellationRejectedException` carries one of these bounded codes:

| Failure code | Cause | Retried? |
| --- | --- | --- |
| `INVALID_EVENT` | Null/oversize payload, invalid UTF-8 or JSON, duplicate members, invalid/missing known field, tombstone, or invalid/mismatched key | No |
| `UNSUPPORTED_EVENT` | A well-formed record has another event type or version | No |
| `EVENT_ID_REUSED` | A completed ID has another canonical fingerprint | No |
| `INVENTORY_ITEM_NOT_FOUND` | No item exists for the serial | No |
| `INVALID_INVENTORY_STATUS` | The item is neither `RESERVED` nor `AVAILABLE` | No |
| `PROCESSING_RETRIES_EXHAUSTED` | Other processing failure after retry exhaustion, or a framework-classified fatal exception | Four retries by default; framework-fatal failures go directly to DLT |

The explicit rejections are deterministic for that record and current committed business state;
retrying them immediately would only block the partition. Spring's standard fatal classifications
(including conversion failures and ClassCastException) also recover without retry. Other listener
exceptions are retryable by default. This gives unforeseen database and infrastructure failures a
bounded second chance without attempting an incomplete vendor-specific exception allowlist.

### 6.2 Blocking retry configuration

`InventoryKafkaConfig` creates one `DefaultErrorHandler` around the DLT recoverer.
Spring `ExponentialBackOff`, with `setMaxAttempts(maxAttempts - 1)`, uses default initial interval
1000 ms, multiplier 2, and maximum interval 8000 ms. With the default five attempts, the resulting
deliveries are the initial attempt plus retries after 1, 2, 4, and 8 seconds. The values bind from
§4.1 and preserve those defaults. A configured single processing attempt allows no retries;
zero delays allow immediate retries bounded by the configured count. Retry-state resets on
exception changes and DLT recovery failure retain Spring's existing behavior: each reset starts
a fresh retry cycle, so the configured count is not a lifetime delivery cap for a source record.

`ReservationCancellationRejectedException` is classified as not retryable. The error handler's
per-record observed backoff increments the bounded `retry` counter only when nextBackOff returns
a delay instead of STOP; failed-delivery notifications alone do not establish retry scheduling.
See [observability design §4](../observability/design.md#4-cancellation-processing-and-dlt).
Retry topics and
`@RetryableTopic` are not used: the short blocking sequence is simpler and preserves source
partition order during recovery. The total default delay is 15 seconds, comfortably below the
consumer's poll interval budget; configuration validation rejects fewer than one attempt,
more than five attempts, negative durations, a multiplier below one, or maximum backoff below the
initial value.

Every failed database attempt has already rolled back before the error handler waits. The handler
seeks/redelivers the same source record; it does not call the transactional method recursively or
reuse a failed persistence context.

### 6.3 Dead-letter publication

`DeadLetterPublishingRecoverer` resolves every failed source record to the same partition number in
`rentflow.inventory.reservation-cancellation.dlt.v1`. Its `KafkaTemplate<byte[], byte[]>` preserves
the original key and value bytes. The producer uses `acks=all` and idempotence.

The recoverer waits for the send result and throws if Kafka does not confirm it. After a confirmed
send, the `DefaultErrorHandler` handles the source record with `ackAfterHandle=true`; record
acknowledgement and synchronous commits then permit its offset to advance. If the recoverer throws,
the error handler leaves/reseeks the source offset and later attempts recovery again. No database
write participates in this DLT path.

The DLT contains these stable UTF-8 headers exactly once:

| Header | Value |
| --- | --- |
| `rentflow.dlt.source-topic` | Original topic name |
| `rentflow.dlt.source-partition` | Decimal original partition |
| `rentflow.dlt.source-offset` | Decimal original offset |
| `rentflow.dlt.consumer-group` | `rentflow.inventory.reservation-cancellation.v1` |
| `rentflow.dlt.failure-code` | One code from §6.1 |
| `rentflow.dlt.failed-at` | Current UTC ISO instant for the recovery attempt |
| `rentflow.dlt.event-id` | Normalized UUID only when it was parseable |

A custom header-name/header function replaces framework exception headers. No exception class,
message, serialized exception, or stack trace is written to Kafka. Valid W3C traceparent/tracestate are permitted for correlation (see ../observability/design.md §4). Other existing headers from the
source record are not copied, preventing unbounded growth during repeated recovery; the stable
fields above contain all supported recovery metadata.

The recoverer reads a parseable event ID from the typed rejection/retry wrapper described in
§4.2. If conversion failed before producing one, a bounded best-effort helper inspects only the
`eventId` JSON member for the header and log; failure to extract it yields no event-ID header and
never changes the record's failure classification.

The dead_letter result error log is emitted only after DLT publication succeeds. A distinct dead_letter_publish_failure diagnostic is emitted on failed publication. It contains the topic, partition,
offset, failure code, and event ID when available. A validated equipment serial may be added when
already available and useful; the undecoded key and complete value are not interpolated into logs.
Unexpected processing/publication diagnostics retain ordinary exception messages, cause chains
and stack traces under ../observability/design.md §3. SQL statements and equipment values in
driver errors are permitted there; complete Kafka payloads and deliberately logged credentials
remain excluded. The bounded DLT header contract above is unchanged by this internal-log policy.

DLT publication is deliberately non-transactional. A crash after the broker accepts the DLT
record but before the source offset commit can create a duplicate DLT record, but it cannot lose
the failure. The original event identity and Inventory inbox make subsequent valid replay safe.

### 6.4 Manual replay procedure

There is no Inventory listener on the DLT. Operators follow this guarded procedure:

1. Identify the failure code and inspect the source metadata without logging or broadly exporting
   the payload.
2. For `PROCESSING_RETRIES_EXHAUSTED`, correct the infrastructure or application cause before
   replay.
3. For missing-item or invalid-status failures, verify the present Inventory and Reservation
   state and account for the lack of reservation ownership before deciding that replay is safe.
4. Do not replay `INVALID_EVENT` or `UNSUPPORTED_EVENT` unchanged. The producer must issue a new
   valid logical event after correcting its contract.
5. Do not replay `EVENT_ID_REUSED` until the conflicting producer identity has been investigated;
   changing only the event ID would hide a producer defect.
6. For a safe valid replay, publish the original key and value to the source topic without changing
   `eventId`. Kafka assigns its partition from the preserved serial key.
7. Confirm a successful Inventory outcome through metrics, inbox/history state, and absence of a
   new DLT record before removing the operational incident.

The runbook is documented in the Inventory README. A CLI or DLT administration endpoint is not
added.

## 7. Topic provisioning and environments

### 7.1 Local and test topology

Automatic topic creation remains disabled. Reservation will own source-topic creation for local
cross-service development when its producer feature is implemented. Inventory owns a conditional
`@Profile("local")` `NewTopic` declaration for its DLT. Until the producer exists, Inventory is
allowed to wait for source-topic metadata rather than failing application startup.

Inventory integration tests explicitly create both three-partition topics before starting the
listener. They do not depend on the common Compose broker and use isolated topic/group names when
test isolation requires it.

`rent-flow-common/compose.yaml` adds the following to Inventory when implementation begins:

```yaml
environment:
  KAFKA_BOOTSTRAP_SERVERS: rentflow-kafka:19092
  SPRING_PROFILES_ACTIVE: local
depends_on:
  rentflow-kafka:
    condition: service_healthy
```

Reservation receives equivalent broker wiring only with its separate producer implementation.
Common retains ownership of the broker image, KRaft topology, listeners, volume, and health check;
feature topic names and consumer behavior remain service-owned.

### 7.2 Production provisioning

Application identities do not create or alter production topics. Deployment infrastructure
provisions the settings from §2.4 before services start and grants least-privilege ACLs:

| Identity | Source topic | DLT | Consumer group |
| --- | --- | --- | --- |
| Reservation | Write/describe | None | None |
| Inventory | Read/describe | Write/describe | Read for its exact group |

The local plaintext broker is not a production template. TLS, authentication, credential
distribution, ACL provisioning, broker/controller sizing, storage, capacity, monitoring, and
disaster recovery remain platform concerns.

## 8. Observability and documentation

### 8.1 Metrics

Inventory exposes one counter family:

```text
inventory.reservation.cancellation.records{outcome="..."}
```

The `outcome` label has only these values:

- `received` for every listener invocation, including a redelivery;
- `released` after a committed real transition;
- `already_available` after a committed successful no-op;
- `duplicate` after a matching inbox receipt suppresses evaluation;
- `retry` when the error handler schedules another attempt;
- `dead_letter` after Kafka confirms DLT publication; and
- `dead_letter_publish_failure` when DLT recovery itself fails.

No metric label contains an event ID, serial number, topic partition, offset, exception class, or
payload value. Standard Kafka client metrics remain available for consumer lag, rebalance,
request, and producer health; the feature does not copy high-cardinality client dimensions into
custom counters.

Production monitoring should alert on any increase in `dead_letter` or
`dead_letter_publish_failure` and on sustained consumer lag. The service supplies the bounded
signals; configuring a particular alerting vendor or notification destination is outside the
application. Consumer-lag alerting must leave enough response time before the seven-day source
retention boundary from §2.4.

### 8.2 Documentation and contract visibility

The Inventory README documents:

- source/DLT names, the consumer group, topic policies, and bootstrap configuration;
- the exact JSON record and serial-number key;
- event-versus-command semantics and the eventual-consistency window;
- producer outbox and consumer inbox responsibilities;
- `RELEASED`, `ALREADY_AVAILABLE`, and duplicate behavior;
- the five-attempt retry sequence and failure taxonomy;
- DLT headers, retention, alert signals, and the guarded replay procedure;
- the reservation-ownership limitation; and
- local topic startup and inspection commands without credentials or secrets.

OpenAPI is unchanged because Kafka consumption adds no HTTP operation. The existing history API
continues to expose real asynchronous `RESERVED`-to-`AVAILABLE` history rows with its current
schema and ordering.

## 9. Validation, failure, and edge-case matrix

| Case | Database effect | Retry/recovery | Offset behavior |
| --- | --- | --- | --- |
| New event, item `RESERVED` | Item `AVAILABLE`; one history and `RELEASED` receipt | None | Commit after DB commit |
| New event, item `AVAILABLE` | `ALREADY_AVAILABLE` receipt only | None | Commit after DB commit |
| Matching completed event ID | None | Successful `DUPLICATE` | Commit after lookup transaction |
| Completed ID, different fingerprint | None | Immediate `EVENT_ID_REUSED` DLT | Commit only after DLT acknowledgement |
| Missing item | None | Immediate `INVENTORY_ITEM_NOT_FOUND` DLT | Commit only after DLT acknowledgement |
| Item in another status | None | Immediate `INVALID_INVENTORY_STATUS` DLT | Commit only after DLT acknowledgement |
| Invalid key/value/contract | None | Immediate validation DLT | Commit only after DLT acknowledgement |
| Transient/unexpected exception, later success | Successful attempt only | Up to four blocking retries | Commit after successful DB commit |
| All configured attempts in a cycle fail | None | `PROCESSING_RETRIES_EXHAUSTED` DLT | Commit only after DLT acknowledgement |
| DLT send fails | None | Recovery is attempted again | Do not commit source offset |
| Crash after DB commit, before offset | Durable first effect | Inbox suppresses redelivery | Later duplicate advances offset |
| Crash after DLT send, before offset | Possible duplicate DLT | Recovery may publish again | No failure is lost |

An event's `occurredAt` may be old or later than Inventory's local processing time because delivery
can be delayed and service clocks can differ. Inventory validates its syntax but applies no age,
future-skew, or retention-window business rule.

## 10. Security and data handling

The event contains an event ID, occurrence time, and equipment serial number only. It carries no
customer, order, reservation, authentication, or payment information. Inventory does not log the
raw key or payload and does not attach either to metric labels.
Validated equipment serials may be used in purposeful internal logs/traces, alongside event and
source metadata. Apply ../observability/design.md §3 to ordinary exception and database diagnostics;
no blanket throwable-message or SQL-text redactor is required. Malformed-input rejection messages
do not retain the raw key/value or a parsing exception that echoes those bytes.

The local broker remains plaintext and host-loopback-bound as documented by `rent-flow-common`.
Production encryption, authentication, authorization, ACLs, and secret rotation are platform
requirements from §7.2, not application credentials embedded in this repository.

## 11. Verification strategy

### 11.1 Unit tests without Spring

- Converter tests cover valid JSON, the 4096-byte boundary, strict UTF-8, null/tombstone values,
  duplicate members, missing/null/wrong-type fields, UUID form/version/case, exact type/version,
  timestamp syntax, serial syntax, key equality, ignored unknown fields, and absence of Java type
  headers.
- Fingerprint tests prove JSON order/whitespace, accepted UUID case, and unknown fields are
  equivalent while every known semantic field, occurrence-time value, serial case, and key change
  is different; advisory lock IDs are deterministic and namespace-separated from HTTP keys.
- Mockito tests for `InventoryService.releaseAfterCancellation` prove advisory-lock-first order,
  duplicate short-circuiting, mismatch rejection, item locking, all six Inventory statuses, exact
  history creation, no-op behavior, flush, and propagation of persistence failures.
- Error-handler/configuration tests prove four retry delays, non-retryable classification, stable
  DLT destination/headers, send-result verification, and bounded metric labels without sleeping.

### 11.2 PostgreSQL integration tests

- `MigrationIT` proves V4 applies once, stays in the `inventory` schema, has the exact columns and
  constraints from §5.1, creates no foreign key or cleanup index, and preserves other-service
  sentinels.
- Direct inserts prove UUID, fingerprint, serial, outcome, and null constraints; PostgreSQL
  generates `processed_at`; valid receipts survive item deletion and a restarted context.
- Transaction tests prove item, history, and receipt commit together and roll back together when
  each write is deliberately failed.
- Concurrent tests prove same-event advisory serialization, differing-payload mismatch, and
  same-serial item serialization without duplicate history or releasing a later reservation on a
  matching replay.

### 11.3 Kafka and full-stack integration tests

Tests use `org.testcontainers.kafka.KafkaContainer` with the pinned `apache/kafka:4.3.1` image and
the existing PostgreSQL 18.4 container. They explicitly create isolated three-partition source and
DLT topics and bind both container addresses through dynamic properties.

The suite proves:

- a valid keyed record releases a reserved item, writes one history/receipt, and advances the
  group offset only after commit;
- an already-available event writes only its receipt;
- a redelivery after a later `AVAILABLE`-to-`RESERVED` change returns `DUPLICATE` and does not
  release the later reservation;
- malformed bytes/JSON, every invalid known field, unknown versions/types, missing/mismatched keys,
  missing items, incompatible states, and ID reuse reach the same DLT partition without database
  effects;
- unknown JSON fields remain consumable and do not affect duplicate fingerprints;
- a controlled transient failure succeeds on a later attempt with only its final database effect,
  while five failures produce one confirmed DLT result;
- unavailable DLT publication leaves the source offset uncommitted and succeeds after recovery;
- DLT records preserve key/value bytes and contain only the bounded stable diagnostic headers and valid W3C traceparent/tracestate; 
- application restarts with the same group, and the database neither skips an uncommitted event nor repeats a committed database effect.

The Kafka tests use bounded polling and deterministic short test overrides for backoff; they do not
increase production timeouts to hide failures. Existing HTTP, history, idempotency, migration,
architecture, and PostgreSQL suites remain green.

### 11.4 Acceptance gates

- `mvn -B -ntp clean verify` runs without skip flags and finishes with `BUILD SUCCESS`.
- If Inventory's Compose wiring changes in `rent-flow-common`, that repository's
  `./scripts/container-smoke-test.sh` also reports `Combined container smoke verification passed`.
- No source or DLT topic is produced through broker auto-creation, no application object is created
  outside its owning schema, and no production secret or credential is embedded in code, tests,
  committed configuration or documentation, or added to application-defined diagnostic fields.
  Internal exception logging follows the collection controls and limits in
  ../observability/design.md §3 rather than a universal arbitrary-text redaction guarantee.

## 12. Acceptance-criteria traceability

| Acceptance criteria | Design coverage |
| --- | --- |
| AC1.1-AC1.2 | §2.2-§2.3 accepted version-1 record and additive-field compatibility |
| AC1.3-AC1.5 | §4.2, §4.4, §5 real transition/no-op receipt and history behavior |
| AC1.6-AC1.7 | §4.4, §6.1, §6.3 deterministic business rejection and DLT behavior |
| AC1.8 | §1.1, §1.3 Kafka-only inbound adapter |
| AC1.9 | §5.1 Inventory-owned V4 objects |
| AC2.1 | §4.4, §5 receipt contents and atomic persistence |
| AC2.2-AC2.3 | §4.3-§4.4 event lock, fingerprint replay, and mismatch behavior |
| AC2.4 | §4.4, §5.1 one local transaction |
| AC2.5-AC2.6 | §4.1, §4.6 record acknowledgement and crash windows |
| AC2.7 | §5.1-§5.2 append-only receipt with no expiry/delete path |
| AC3.1 | §2.2-§2.3, §6.1 contract validation and immediate DLT |
| AC3.2-AC3.4 | §6.1-§6.2 five-attempt blocking recovery and rollback |
| AC3.5-AC3.7 | §6.3 byte preservation, bounded headers, confirmation, and offset behavior |
| AC3.8-AC3.9 | §6.4 guarded manual replay only |
| AC4.1-AC4.2 | §1.1, §4.4, §8.2 unchanged HTTP contract and visible real history |
| AC4.3-AC4.4 | §6.3, §8.1 bounded metrics and diagnostics |
| AC4.5 | §8.2 README contract |
| AC4.6 | §1.1, §3.2 no result event |
| AC4.7 | §1.1, §3.1, §5.1, §7 schema and integration ownership |
