# Inventory observability tasks

## 1. Delivery rules

Each task is one safe commit. T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9.
All are required for this feature; T7–T9 close the Kafka review findings after the baseline.
Keep existing project layers, explicit Java types, persistence contracts and mandatory clean verify.
The seven resolved decisions are recorded in requirements.md; no additional product decisions remain.

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

### T3 — Sanitize diagnostics and instrument JDBC

Refs.: AC1.4, AC2.6; design.md §3.

Add ECS logging, safe throwable formatting, span export sanitization and JDBC observations.
DoD.: Tests assert JSON stdout identity/correlation, retained exception types/frames and absent
synthetic secrets/raw URLs/SQL/parameters/messages in serialized logs and exported spans; HTTP
parent/request/connection/query relationships, Flyway/JPA transactions and Hikari meters pass.

### T4 — Correlate cancellation delivery and recovery

Refs.: AC2.1–AC2.5; design.md §4.

Add result/retry/recovery logs, completion delay and validated W3C header handling.
DoD.: Tests assert existing outcome counters, one INFO per success, structured retries/DLT failures,
delay eligibility and clock skew, trace parents on retries/DLT sends, missing/malformed context
tolerance, DLT header allowlist and unchanged record bytes/partition/offset/inbox behavior.

### T5 — Refresh backlog independently and observe cleanup

Refs.: AC3.1–AC3.3; design.md §5.

Add scheduled bounded count refresh, freshness/failure signals and cleanup observations.
DoD.: Tests assert unknown startup backlog, successful count/time refresh, failure retention and
recovery, no DB work on scrape, five-second query timeout, parallel scheduling capacity, safe
cleanup error observations, timestamps and unchanged daily schedule/chunk transaction/budget.

### T6 — Document the handoff and verify the complete service

Refs.: AC4.1–AC4.2; design.md §6.

Update README and smoke checks with configuration, contracts, investigation examples and Common handoff.
DoD.: Documentation gives executable host/container instructions, full endpoint/identity/log/metric
contracts and alert expressions/signals; mvn -B -ntp clean verify reports BUILD SUCCESS and
Inventory smoke test succeeds. Clearly distinguish Inventory checks from Common/Reservation
end-to-end acceptance. Re-evaluate modified specs and preserve dated reports.

### T7 — Normalize Kafka context before observation extraction

Refs.: AC2.1, AC2.5; design.md §4.

Move normalization to a Kafka consumer interceptor, register it last through Boot's consumer-factory
customizer without duplicating it or dropping other interceptors, and restore Boot's listener factory.
DoD.: Unit tests prove registration preserves configured interceptors and the original record bytes.
Real-consumer tests inspect normalized headers at observation start for valid, duplicate, missing,
malformed and oversized context; valid parents are retained and rejected context cannot parent a span.
Existing retry/DLT parent, header-allowlist, partition, offset and inbox assertions still pass.

### T8 — Observe scheduled retries and enforce their count

Refs.: AC2.1, AC2.3; design.md §4.

Use count-bounded ExponentialBackOff and observe its per-record nextBackOff decisions, retaining
Spring's classification and retry-state resets. Emit RETRYABLE_PROCESSING_FAILURE for retry logs.
DoD.: Tests cover processing-attempt limits 1–5, zero delays, multiplier one and exhausted recovery;
scheduled counters/logs match actual backoff decisions and their failed-attempt numbers exactly.
Framework-fatal and business-rejection failures produce no retry WARN or retry increment. Exception
changes and recovery failures reset retry state as before; default delays remain 1, 2, 4, 8 seconds.

### T9 — Preserve safe DLT causes and verify serialized callbacks

Refs.: AC2.3, AC2.6, AC4.1, AC4.2; design.md §3, §4, §6.

Attach publication failures to their fixed ERROR message and reuse the existing safe stack printer.
Reconcile affected cancellation documentation and run the complete service checks.
DoD.: Actual callback logs serialized by the configured ECS encoder retain severity, source metadata,
event/trace/span correlation and safe cause types/frames without synthetic private values. Failed
publication still retains the source offset until confirmed recovery. Affected specs have dated
evaluation reports; Maven clean verify reports BUILD SUCCESS and Inventory container smoke passes.
