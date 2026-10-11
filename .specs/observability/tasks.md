# Inventory observability tasks

## 1. Delivery rules

Each task is one safe commit. T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10.
All are required for this feature; T7–T9 cover framework propagation and the Kafka review findings
after the baseline. T10 applies the revised diagnostic policy using native configuration.
T3/T9 below describe the final policy rather than preserving
the superseded blanket-sanitization requirements.
Keep existing project layers, explicit Java types, persistence contracts and mandatory clean verify.
The resolved decisions are recorded in requirements.md; no additional product decisions remain.

### T1 — Record the agreed specification

Refs.: AC1.1–AC4.2; design.md §1–§6.

Write these specs and reconcile superseded telemetry sections in existing feature specs.
DoD.: Every AC maps to a design section and implementation task; run spec-self-eval and retain
dated evidence-led reports for affected specs, using its fallback checklist when the project
checklist is missing. Record remaining weaknesses honestly.

### T2 — Isolate management and export baseline telemetry

Refs.: AC1.1–AC1.3, AC1.5; design.md §2.

Add registry/tracing dependencies and runtime configuration, identity and private management port.
DoD.: Integration tests assert Prometheus only on management, probes on application port, hidden
health details, unchanged OpenAPI, histogram buckets, resource identity, no high-cardinality labels,
and readiness/business responses unaffected by absent/slow collector or saturated export queue.

### T3 — Preserve useful diagnostics and instrument JDBC

Refs.: AC1.4, AC2.6; design.md §3.

Add native ECS logging, ordinary throwable formatting and JDBC observations.
DoD.: Tests assert JSON stdout identity/correlation and retained ordinary exception/cause messages,
frames, SQL statement text and actual request paths in logs/spans where emitted. Body/header/query
and bound-parameter capture stay disabled; invalid Kafka conversion does not echo raw key/payload,
and public unexpected-error responses remain generic. HTTP parent/request/connection/query
relationships, Flyway/JPA transactions and Hikari meters pass.

### T4 — Correlate cancellation delivery and recovery

Refs.: AC2.1–AC2.5; design.md §4.

Add result/retry/recovery logs, completion delay and framework W3C propagation.
DoD.: Tests assert existing outcome counters, one INFO per success, structured retries/DLT failures,
delay eligibility and clock skew, trace parents on retries/DLT sends, missing/malformed context
tolerance, DLT header allowlist and unchanged record bytes/partition/offset/inbox behavior.

### T5 — Refresh backlog independently and observe cleanup

Refs.: AC3.1–AC3.3; design.md §5.

Add scheduled bounded count refresh, freshness/failure signals and cleanup observations.
DoD.: Tests assert unknown startup backlog, successful count/time refresh, failure retention and
recovery, no DB work on scrape, five-second query timeout, parallel scheduling capacity, safe
cleanup error observations with ordinary exception diagnostics, timestamps and unchanged daily
schedule/chunk transaction/budget.

### T6 — Document the handoff and verify the complete service

Refs.: AC4.1–AC4.2; design.md §6.

Update README and smoke checks with configuration, contracts, investigation examples and Common handoff.
DoD.: Documentation gives executable host/container instructions, full endpoint/identity/log/metric
contracts and alert expressions/signals; mvn -B -ntp clean verify reports BUILD SUCCESS and
Inventory smoke test succeeds. Clearly distinguish Inventory checks from Common/Reservation
end-to-end acceptance. Re-evaluate modified specs and preserve dated reports.

### T7 — Use framework Kafka context propagation

Refs.: AC2.1, AC2.5, AC2.6; design.md §2, §4.

Remove custom tracing-header normalization and its consumer-factory customization. Use the default
framework extraction/propagation and disable baggage through Spring configuration.
DoD.: Real-consumer tests prove valid producer context parents the Inventory span, cancellation
processing succeeds with absent or unusable context, and incoming baggage is not propagated.
The service has no custom tracing-header parser, size bound, duplicate policy or B3 removal code.
Existing retry/DLT parent, header-allowlist, partition, offset and inbox assertions still pass.

### T8 — Observe scheduled retries and enforce their count

Refs.: AC2.1, AC2.3; design.md §4.

Use count-bounded ExponentialBackOff and observe its per-record nextBackOff decisions, retaining
Spring's classification and retry-state resets. Emit RETRYABLE_PROCESSING_FAILURE for retry logs.
DoD.: Tests cover processing-attempt limits 1–5, zero delays, multiplier one and exhausted recovery;
scheduled counters/logs match actual backoff decisions and their failed-attempt numbers exactly.
Framework-fatal and business-rejection failures produce no retry WARN or retry increment. Exception
changes and recovery failures reset retry state as before; default delays remain 1, 2, 4, 8 seconds.

### T9 — Preserve DLT causes and verify serialized callbacks

Refs.: AC2.3, AC2.6, AC4.1, AC4.2; design.md §3, §4, §6.

Attach publication failures to their fixed ERROR message and use native throwable formatting.
Reconcile affected cancellation documentation and run the complete service checks.
DoD.: Actual callback logs serialized by the configured ECS encoder retain severity, source metadata,
event/trace/span correlation and ordinary cause messages/types/frames without deliberately
interpolating complete Kafka payloads or undecoded keys. Failed publication still retains the
source offset until confirmed recovery. Affected specs have dated
evaluation reports; Maven clean verify reports BUILD SUCCESS and Inventory container smoke passes.

### T10 — Replace blanket filtering with framework diagnostics

Refs.: AC1.3–AC1.5, AC2.2–AC2.3, AC2.6, AC4.1–AC4.2; design.md §3–§4, §6.

Remove the blanket JSON customizer, custom stack printer, span exporter wrapper and obsolete
message-scrubbing tests. Use Spring Boot's native ECS/stack limits, restore Hibernate error
diagnostics and set the three Kafka configuration loggers to WARN. Retain disabled body/header/
query-string, baggage and SQL-binding capture, and existing public error contracts. Permit useful
validated serial fields without adding recovery-time parsing. Remove the unknown cluster-ID
fallback and apply the existing setter only to an explicitly supplied nonblank actual ID; retain
framework discovery otherwise. Update README, the Common handoff and relevant smoke assertions
to describe actual runtime behavior.

DoD.: Serialized callback and HTTP/JDBC integration tests retain ordinary exception/cause messages,
SQL statement text, actual paths and framework diagnostic metadata where emitted. Tests prove
automatic sensitive-input and bound-parameter capture remain disabled, invalid conversion does
not echo raw key/payload, public 500 responses remain generic, and metrics use route templates
without business-ID labels. Configuration tests prove unset/blank cluster ID leaves discovery
enabled and an actual configured ID is applied without a fabricated fallback. Kafka propagation,
retry counts, DLT headers/bytes/offsets, transactions, Hikari meters and collector failure behavior
remain green. Maven clean verify reports BUILD SUCCESS and Inventory container smoke passes;
documentation matches emitted telemetry and affected specs have dated self-evaluation reports.
