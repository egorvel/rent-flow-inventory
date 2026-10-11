# Inventory observability requirements

## Context

Inventory owns application telemetry; Common owns collection, storage, dashboards and alerts.
Existing health probes and bounded idempotency/cancellation meters provide a foundation, but
metrics are not exported, traces are absent and cleanup backlog is a daily snapshot.

## User stories with acceptance criteria

### US1 — Diagnose availability and performance

As an operator, I need internal metrics and correlated request/database traces.

- **AC1.1 (Ubiquitous):** The service shall expose only health and Prometheus on a separate configurable management port, default 8085, bound to loopback for host execution and unpublished on the host in Compose.
- **AC1.2 (Ubiquitous):** The service shall preserve application-port liveness/readiness probes, hidden health details, database-only readiness and business/OpenAPI contracts.
- **AC1.3 (Ubiquitous):** The service shall identify metrics, logs and traces by service, environment and instance without using business identifiers or raw URLs as metric labels.
- **AC1.4 (Event-driven):** When an HTTP request executes database work, the service shall produce correlated request, connection and query spans and HTTP latency histograms while preserving pool metrics and persistence semantics.
- **AC1.5 (Unwanted):** If a telemetry collector is absent, slow or unable to accept queued spans, then business operations and readiness shall remain independent of trace export.

### US2 — Investigate cancellations safely

As an operator, I need cancellation outcomes, delay and failures correlated with the producer.

- **AC2.1 (Event-driven):** When a cancellation delivery completes or fails, the service shall retain the existing outcome counter semantics and observe each attempt, retry and DLT publication with originating trace context when valid.
- **AC2.2 (Event-driven):** When cancellation processing commits a released, already-available or duplicate result, the service shall write one structured INFO result record containing event and source correlation fields.
- **AC2.3 (Event-driven):** When a retry, confirmed DLT publication or DLT publication failure occurs, the service shall write a structured diagnostic with a bounded failure category and source metadata, retaining ordinary exception diagnostics where attached.
- **AC2.4 (Event-driven):** When released or already-available processing commits, the service shall measure completion delay from occurredAt, excluding duplicate/failure samples and diagnosing future timestamps without rejecting events.
- **AC2.5 (Unwanted):** If incoming tracing context is absent or unusable by the configured tracing libraries, then cancellation processing shall continue without rejecting the event; DLT records shall preserve their existing key, value and diagnostic headers, with tracing propagation supplied by framework instrumentation.
- **AC2.6 (Ubiquitous):** The service shall emit stdout ECS JSON logs and framework-produced spans that retain ordinary exception messages, cause chains, SQL statement text and actual request paths, keep automatic body/header/query-string and bound SQL parameter capture disabled, and exclude credentials, tokens, complete Kafka payloads and undecoded keys from application-defined diagnostic fields.

### US3 — Detect stalled cleanup

As an operator, I need fresh backlog and worker-health signals.

- **AC3.1 (Event-driven):** When the independent 60-second backlog refresh succeeds, the service shall expose the count and measurement time using a database query bounded to five seconds, without querying during scrapes.
- **AC3.2 (Unwanted):** If backlog refresh fails, then the service shall count the failure and retain the previous value/time; before the first successful refresh the backlog shall be unknown.
- **AC3.3 (Event-driven):** When daily cleanup runs, the service shall expose run/success timestamps and a correctly successful or failed observation while preserving its deletion schedule, chunk transactions and budget.

### US4 — Integrate and verify

As an implementer, I need repeatable configuration and a concrete Common handoff.

- **AC4.1 (Ubiquitous):** The service shall document scrape/export/log contracts, bounded labels, investigation examples and alert signals while remaining independently runnable with trace export disabled by default.
- **AC4.2 (Ubiquitous):** The implementation shall pass Maven clean verify and Inventory container smoke verification, including privacy, management isolation, propagation, failure and persistence regression tests.

## Out of scope

Common infrastructure implementation, dashboards/alerts provisioning, production TLS/authentication,
SLO selection, Reservation outbox context persistence, custom Kafka tracing-header validation or
normalization, blanket telemetry allowlists or message scrubbing, custom SQL literal parsing,
database migrations and business API changes.

## Resolved questions

1. Full feature spec rather than baseline only. See design.md §1 and tasks.md §1.
2. Inventory implementation with a Common handoff. See design.md §6.
3. Private management port without Spring Security. See design.md §2.
4. Include database spans. See design.md §3.
5. One INFO log per committed cancellation result. See design.md §4.
6. Refresh backlog every minute. See design.md §5.
7. Retain ordinary exception messages, causes and frames in internal diagnostics. Use framework
   logging and configurable stack limits instead of custom throwable printers. See design.md §3.
8. Delegate Kafka context extraction and propagation to the tracing libraries; disable baggage
   through Spring configuration. See design.md §2, §4 and tasks.md T7.
9. Allow SQL statement text with bound-parameter capture disabled, and actual HTTP paths in
   traces with route templates in metric labels. These controls do not redact SQL literals or
   values appearing in exception messages. See design.md §3.
10. Permit validated equipment serials in useful internal diagnostics; keep complete payloads
    and undecoded Kafka keys out of application logs. Restore Hibernate diagnostics and use WARN
    for Kafka configuration loggers. See design.md §3–§4 and tasks.md T10.
11. Use normal Kafka cluster-ID discovery unless an actual ID is supplied by deployment;
    remove the literal unknown fallback. See design.md §4 and tasks.md T10.
