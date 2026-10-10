# Async Reservation Cancellation Inventory Consumer Requirements

Status: Requirements resolved; design and task decomposition documented.

## Context

Reservation creation currently claims equipment synchronously through Inventory's idempotent
`PATCH /api/v1/inventory/status` operation. Cancellation needs the inverse lifecycle effect without
holding the caller's request open while Inventory performs it. When Reservation commits a
cancellation, it therefore publishes a `ReservationCancelled` integration event and Inventory
eventually releases the corresponding item from `RESERVED` to `AVAILABLE`.

`ReservationCancelled` is a past-tense event rather than an Inventory command. Reservation owns
the fact that its reservation was cancelled; Inventory decides how that fact affects the lifecycle
state it owns. Cancellation and release are consequently eventually consistent: a cancellation
may already be committed while Inventory still temporarily reports the item as `RESERVED`.
Inventory does not publish a success or failure event in this first increment.

The end-to-end delivery path is reliable but at least once. Reservation persists a transition
from `HELD` or `CONFIRMED` to `CANCELLED` and an outbox record in one local PostgreSQL transaction.
An outbox relay publishes one JSON event
per cancelled item and reuses the same event identifier, payload, and Kafka record key on every
publication attempt. Inventory consumes the event in a use-case-specific consumer group. The
record key is the case-sensitive Inventory serial number so records for one item are assigned to
the same topic partition and retain their Kafka order.

The version-1 event identifies the integration fact with a canonical UUID v4 `eventId`, identifies
its type and version, carries its occurrence time and the case-sensitive Inventory `serialNumber`,
and deliberately carries no `reservationId`. The exact JSON field names beyond these concepts and
the compatibility treatment of future fields are resolved in design. Because Inventory does not
know which reservation owns a `RESERVED` state, this feature does not protect against the first
delivery of an old cancellation after an out-of-band release and a later reservation. That known
limitation is accepted for this increment.

Kafka may redeliver a record after Inventory has committed its database work but before the
consumer offset is committed. Merely treating an already `AVAILABLE` item as success is not enough:
the item could become `RESERVED` for another reservation before an old duplicate is redelivered.
Inventory therefore keeps a durable inbox receipt keyed by `eventId`. The receipt includes a
fingerprint of the validated event and records whether the event released the item or found it
already available. Inbox receipts are retained indefinitely in this increment.

For a new valid event, Inventory locks the item and converges on `AVAILABLE`. A `RESERVED` item is
transitioned to `AVAILABLE` and receives the same immutable status-history record as a successful
synchronous lifecycle transition. An item already in `AVAILABLE` produces a successful
`ALREADY_AVAILABLE` no-op and no false `AVAILABLE`-to-`AVAILABLE` history. The inbox receipt, item
change, and any history row commit in one Inventory-owned database transaction. The consumer
offset becomes eligible for commit only after that transaction succeeds.

Malformed or unsupported events, event-identifier reuse with different content, missing items,
and items in any state other than `RESERVED` or `AVAILABLE` are deterministic failures. Inventory
does not retry them and does not change inventory or history; it publishes the original record to
a dead-letter topic for investigation. Transient processing failures receive five total attempts per retry cycle,
using configurable delays whose defaults are 1, 2, 4, and 8 seconds, before the record is sent to
the dead-letter topic. Inventory advances the source offset only after the broker confirms the
dead-letter publication; failure to publish there leaves the source record eligible for delivery
again.

The dead-letter topic is a durable quarantine, not an automatic retry loop. Operators are alerted,
diagnose the failure, and deliberately replay an event with its original identifier and record key
when replay is safe. Metrics expose processing, duplicate, retry, and dead-letter outcomes without
using event identifiers or serial numbers as metric labels.

The source topic retains records for seven days. The eventual-release guarantee therefore assumes
Inventory consumes the record, or Reservation republishes it from retained outbox state, within
that recovery window. Unlimited outage recovery and automatic reconciliation after source-topic
retention are not part of this increment.

Kafka's local broker topology, listeners, and lifecycle tooling remain owned by
`rent-flow-common`. Reservation owns its cancellation event contract and outbox behavior;
Inventory owns its consumer group, inbox, processing rules, retry policy, and dead-letter topic.
Each service confines its database objects to its own schema and communicates only through its
public HTTP or Kafka contracts.

### External producer assumptions

This Inventory feature assumes a future Reservation implementation will publish a compatible
source event. These assumptions define the integration boundary and explain the end-to-end model;
they are not acceptance criteria or implementation tasks for `rent-flow-inventory`:

- Reservation will persist a `HELD`-to-`CANCELLED` or `CONFIRMED`-to-`CANCELLED` transition and one
  outbox event per cancelled item in the same Reservation-owned transaction.
- A rolled-back cancellation or a write that does not make one of those transitions will create no
  outbox event.
- Each logical event will retain one UUID v4 `eventId`, its exact JSON value, and its exact
  case-sensitive serial-number key across publication attempts.
- The relay will retain unacknowledged work, publish with `acks=all` and producer idempotence, and
  allow the Reservation operation to complete without waiting for Inventory consumption.
- The Reservation repository's own feature spec and tests will define and verify its endpoint,
  outbox schema, relay coordination, retry schedule, and cleanup behavior.

## User stories with acceptance criteria

### US1 - Release cancelled inventory asynchronously

As a rental workflow operator, I want Inventory to react to cancellation events so that cancelled
equipment becomes available again without a synchronous release call.

- **AC1.1 (Event-driven):** When Inventory receives a cancellation record whose key and five
  required fields conform to the version-1 contract, the Inventory consumer shall accept the
  record for lifecycle processing.
- **AC1.2 (Event-driven):** When an otherwise valid version-1 cancellation record contains unknown
  JSON properties, the Inventory consumer shall ignore those properties, process the known fields,
  and exclude the unknown properties from its idempotency fingerprint.
- **AC1.3 (Event-driven):** When Inventory receives a new valid cancellation event for an existing
  item in `RESERVED`, the Inventory service shall change only that item's status to `AVAILABLE` and
  record the event as `RELEASED`.
- **AC1.4 (Event-driven):** When Inventory releases an item for a cancellation event, the Inventory
  service shall create exactly one immutable status-history record whose source is `RESERVED` and
  whose target is `AVAILABLE`.
- **AC1.5 (Event-driven):** When Inventory receives a new valid cancellation event for an existing
  item already in `AVAILABLE`, the Inventory service shall leave the item unchanged, create no
  status-history record, and record the event as `ALREADY_AVAILABLE`.
- **AC1.6 (Unwanted):** If a new valid cancellation event names an item that does not exist, then
  the Inventory service shall leave inventory and status history unchanged and route the event to
  the dead-letter topic without a processing retry.
- **AC1.7 (Unwanted):** If a new valid cancellation event names an item in `RENTED`,
  `INSPECTION_REQUIRED`, `UNDER_MAINTENANCE`, or `RETIRED`, then the Inventory service shall leave
  the item and status history unchanged and route the event to the dead-letter topic without a
  processing retry.
- **AC1.8 (Ubiquitous):** The Inventory service shall process cancellation events through a Kafka
  consumer and shall not add an HTTP endpoint for asynchronous cancellation.
- **AC1.9 (Ubiquitous):** The Inventory service shall confine cancellation inbox records and all
  migrations for them to the Inventory-owned `inventory` schema.

### US2 - Apply each cancellation event idempotently

As an Inventory operator, I want redelivered cancellation events to have no additional lifecycle
effect so that at-least-once delivery cannot release a later reservation.

- **AC2.1 (Event-driven):** When Inventory completes a new valid cancellation event as `RELEASED`
  or `ALREADY_AVAILABLE`, the Inventory service shall atomically persist an inbox receipt keyed by
  `eventId`, the validated-event fingerprint, the processing outcome, and the processing time.
- **AC2.2 (Event-driven):** When an event with a previously completed `eventId` and the same
  validated-event fingerprint is redelivered, the Inventory service shall treat it as a successful
  duplicate without reevaluating or changing the current item, adding history, or replacing the
  inbox receipt.
- **AC2.3 (Unwanted):** If an event reuses a completed `eventId` with a different validated-event
  fingerprint, then the Inventory service shall leave the existing receipt, inventory, and status
  history unchanged and route the conflicting event to the dead-letter topic without a processing
  retry.
- **AC2.4 (Ubiquitous):** The Inventory service shall persist the inbox receipt, the optional item
  status change, and the optional status-history record in one local database transaction.
- **AC2.5 (Event-driven):** When the Inventory database transaction commits successfully, the
  Inventory consumer shall acknowledge the source record and shall advance its committed offset
  only after that database commit.
- **AC2.6 (Unwanted):** If the Inventory database transaction rolls back or fails to commit, then
  the Inventory consumer shall not acknowledge the source record as successfully processed.
- **AC2.7 (Ubiquitous):** The Inventory service shall retain completed cancellation inbox receipts
  indefinitely and shall provide no expiry or automatic inbox-cleanup behavior in this increment.

### US3 - Quarantine records that cannot be processed safely

As an Inventory operator, I want bounded retries and durable dead-letter handling so that transient
failures receive another chance while invalid records do not block a partition forever or vanish.

- **AC3.1 (Unwanted):** If a cancellation record has malformed JSON, an unsupported event type or
  version, an invalid event identifier, a malformed occurrence time, an invalid serial number, a
  missing required field, a missing record key, or a record key different from its serial number,
  then the Inventory consumer shall make no inventory, history, or inbox change and shall route
  the original record directly to the dead-letter topic without a processing retry.
- **AC3.2 (Unwanted):** If cancellation processing fails because of a classified transient
  infrastructure or database condition, then the Inventory consumer shall make at most five total
  processing attempts for the source record within each retry cycle. A change of exception type
  or failed dead-letter publication starts a new cycle as specified in `design.md` §6.2.
- **AC3.3 (Event-driven):** When another transient processing attempt remains, the Inventory
  consumer shall delay it according to configurable backoff whose default successive delays are
  1, 2, 4, and 8 seconds.
- **AC3.4 (Unwanted):** If all configured transient processing attempts in a retry cycle fail, then
  the Inventory consumer shall route the original record to the dead-letter topic without committing a partial inventory,
  history, or inbox change.
- **AC3.5 (Event-driven):** When Inventory publishes a failed source record to the dead-letter
  topic, the Inventory service shall preserve the original event payload and Kafka record key,
  preserve any parseable original event identifier, and attach bounded diagnostic metadata
  identifying its source location and failure category.
- **AC3.6 (Event-driven):** When Kafka confirms the dead-letter publication, the Inventory consumer
  shall acknowledge the failed source record and advance its committed offset only after that
  confirmation.
- **AC3.7 (Unwanted):** If Kafka does not confirm the dead-letter publication, then the Inventory
  consumer shall not commit the failed source record's offset.
- **AC3.8 (Ubiquitous):** The Inventory service shall not automatically replay dead-letter records.
- **AC3.9 (Event-driven):** When an operator deliberately replays a valid dead-letter record, the
  replay procedure shall preserve its original event identifier and case-sensitive serial-number
  key.

### US4 - Preserve existing contracts and expose bounded observability

As a service operator, I want asynchronous cancellation to coexist with existing Inventory
behavior and expose actionable signals so that it can be operated without changing current API
clients.

- **AC4.1 (Ubiquitous):** The Inventory service shall leave the request, response, validation,
  idempotency, transition-matrix, and history behavior of `PATCH /api/v1/inventory/status`
  unchanged.
- **AC4.2 (Event-driven):** When asynchronous cancellation creates a `RESERVED`-to-`AVAILABLE`
  history record, the Inventory service shall expose that record through the existing inventory
  history API using the existing representation and retention behavior.
- **AC4.3 (Ubiquitous):** The Inventory service shall expose bounded-label counters for received,
  released, already-available, duplicate, retry, and dead-letter outcomes without event identifiers
  or serial numbers in metric labels.
- **AC4.4 (Event-driven):** When a cancellation record is routed to the dead-letter topic, the
  Inventory service shall emit an error-level diagnostic containing any parseable event
  identifier, source topic, partition, offset, and bounded failure category without logging the
  original payload.
- **AC4.5 (Ubiquitous):** The service documentation shall describe the version-1 event contract,
  eventual-consistency behavior, at-least-once delivery, inbox guarantee, retry defaults,
  dead-letter categories, alerting expectation, and manual replay procedure.
- **AC4.6 (Ubiquitous):** The Inventory service shall publish no cancellation-result event in this
  increment.
- **AC4.7 (Ubiquitous):** The Inventory service shall access only its own `inventory` database
  schema and shall consume cancellation information through Kafka without reading the Reservation
  schema or calling a private Reservation implementation API.

## Out of scope

- Carrying, persisting, or validating a `reservationId` or another reservation-ownership token in
  Inventory.
- Preventing the first delivery of a stale cancellation event from releasing an item that was
  reassigned through an out-of-band or manually forced lifecycle change.
- Converting reservation creation, confirmation, fulfillment, rental, inspection, maintenance, or
  retirement workflows to Kafka.
- Adding another asynchronous Inventory status transition beyond cancellation release from
  `RESERVED` to `AVAILABLE`.
- Adding an Inventory HTTP endpoint for asynchronous cancellation or changing Inventory's existing
  synchronous status-transition endpoint.
- Designing or implementing the Reservation cancellation endpoint, outbox table, publisher relay,
  publisher retry schedule, or outbox cleanup; only the external producer assumptions are recorded
  here.
- Publishing a success, failure, acknowledgement, or cancellation-result event from Inventory.
- Automatically replaying dead-letter records or building a dead-letter administration API or UI.
- Expiring or cleaning up cancellation inbox receipts.
- Claiming exactly-once Kafka delivery or attempting to place PostgreSQL and Kafka work in one
  distributed transaction.
- Providing unlimited outage recovery or automatic reconciliation after a cancellation record is
  no longer available from the source topic or its producer outbox.
- Introducing a schema registry, Avro, Protobuf, or a cross-service shared event-class library in
  this increment.
- Defining production Kafka topology, TLS, authentication, authorization, ACLs, secret handling,
  backup, or disaster-recovery policy.
- Changing the common repository's ownership of the local Kafka broker topology.

## Resolved questions

1. **Kafka names:** The source topic is `rentflow.reservation.cancelled.v1`, the Inventory consumer
   group is `rentflow.inventory.reservation-cancellation.v1`, and the dead-letter topic is
   `rentflow.inventory.reservation-cancellation.dlt.v1`. See `design.md` §2.1.
2. **Wire contract:** Version 1 is a bounded UTF-8 JSON object with `eventId`, `eventType`,
   `eventVersion`, `occurredAt`, and `serialNumber`; the serial number is also the record key. The
   consumer rejects invalid known fields but ignores unknown properties, and its fingerprint is
   independent of JSON formatting, property order, and unknown fields. See `design.md` §2.2-§2.3.
3. **Topic policy:** The source and dead-letter topics each have three partitions and `delete`
   cleanup; source retention is seven days and dead-letter retention is thirty days. Local
   replication is one, while production targets replication factor three and minimum ISR two.
   See `design.md` §2.4 and §7.2.
4. **Producer boundary:** The concrete Reservation cancellation operation, outbox schema, relay,
   retry schedule, and cleanup are designed and implemented later in the Reservation repository.
   This feature records external producer assumptions while accepting only Inventory's consumer
   behavior. See `design.md` §3.
5. **Retry classification:** Explicit contract and deterministic business rejections go directly
   to dead-letter handling, as do Spring's standard fatal exceptions. Other processing exceptions
   receive up to four blocking retries per cycle with default delays of 1, 2, 4, and 8 seconds.
   Exception-type changes and failed DLT publication reset the cycle; five attempts is not a
   lifetime limit for a source record. See `design.md` §6.1-§6.2.
6. **Inbox design:** An append-only Inventory-owned receipt stores the event identity, canonical
   fingerprint, serial number, occurrence time, outcome, and PostgreSQL processing time. An
   event-scoped advisory lock and item row lock protect processing; receipts never expire. See
   `design.md` §4.3-§4.6 and §5.
7. **Dead-letter handling:** Inventory preserves the original key and value, adds bounded source
   and failure headers without exception text or stack traces, waits for broker acknowledgement,
   emits bounded observability, and provides only guarded manual replay. See `design.md`
   §6.3-§6.4 and §8.
