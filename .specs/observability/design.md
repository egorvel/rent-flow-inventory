# Inventory observability design

## 1. Boundaries and compatibility

Implements AC1.1–AC4.2 without changing business HTTP/OpenAPI or database contracts.
Java 25 and Boot 4.1.0 remain pinned. Existing cancellation and idempotency metrics retain names
and outcome meanings. No migration or shared infrastructure changes are required.
Stateless telemetry sanitizers live in util. Permit Config and Controller to depend on Util
in the existing ArchUnit layer rule; Util remains independent of application layers and no
controller/repository or reverse-layer dependency is introduced.
This specification supersedes older telemetry-only descriptions of DLT tracing headers,
unsanitized exception logging and cleanup backlog measurement; business behavior remains intact.

## 2. Baseline and management

AC1.1–AC1.3, AC1.5: add Boot-managed Prometheus registry and OpenTelemetry starter.
Management serves health/prometheus at configurable port 8085 and address 127.0.0.1 by default.
Compose overrides the address to 0.0.0.0 without publishing this port. Probes remain available
at /livez and /readyz on 8080 with database-only readiness and hidden details.
Service identity is rent-flow-inventory, environment defaults to local, instance defaults to
HOSTNAME (or standalone). Metrics have bounded service/environment tags; Prometheus supplies
target instance. ECS service node and OTel service.instance.id use the runtime instance.
Enable HTTP histograms with expected range 1ms–10s. Exclude management/probe observations.
Use W3C propagation with no remote baggage. Sampling defaults to 1 with the local profile and 0.1 otherwise,
overridable through the standard property. Trace export is disabled by default through
management.tracing.export.otlp.enabled; configure a full /v1/traces URL (default localhost:4318).
Use HTTP/protobuf, two-second connect/call timeouts and bounded asynchronous batch export
(queue 2048, batch 512). Do not add telemetry dependencies to readiness or Compose depends_on.

## 3. Logging, privacy and database spans

AC1.4, AC2.6: use built-in ECS stdout logging, banner disabled; attach structured safe fields
via SLF4J fluent logging and trace/span MDC. A custom stack printer retains exception classes
and frames, strips all throwable messages and bounds length/depth. Sanitization at the JSON
boundary excludes unrestricted exception messages and known sensitive framework SQL diagnostics.
Application failure messages use fixed templates, exception types and route templates; omit
unmatched raw paths. Raw request URLs and query strings must not enter exported span attributes.

Pin datasource-micrometer-spring-boot 2.2.1 with Boot-managed Micrometer versions.
Observe CONNECTION and QUERY only; disable parameter inclusion, query/slow-query logging,
resultset instrumentation and Hikari-specific observation (retain normal pool metrics).
An exporting wrapper sanitizes all spans before delegating: remove SQL, parameter, raw URL,
unrestricted header and exception-message attributes; scrub exception events and descriptions,
retaining safe exception types/frames. Remove business identifier attributes except event IDs
explicitly used for cancellation correlation. Default metrics remain bounded by datasource.
No OpenTelemetry Java agent or independent SDK is added.

## 4. Cancellation processing and DLT

AC2.1–AC2.5: enable listener/template observations and give the listener a stable identifier.
Observed attempts share valid producer context. Framework listener scope includes error handling;
DLT template sends run in that scope. Validate this relationship in Kafka integration tests.
Configure KafkaAdmin with inventory.kafka.cluster-id (default unknown; Common supplies its actual
cluster ID), avoiding instrumentation-triggered synchronous cluster discovery.

Preserve received/released/already_available/duplicate/retry/dead_letter/
dead_letter_publish_failure counter names and meanings; received is listener invocations, including retry;
retry means scheduled retry; success counters are incremented after the transactional proxy returns.
Result INFO logs carry eventId, outcome, sourceTopic, partition and offset. Retry WARN logs carry
deliveryAttempt and bounded failureCode; recovery ERROR logs include failureCode and eventId when
parseable. Never log record key/value or serialNumber. Attach the DLT publication exception to its
fixed ERROR message so the existing safe stack printer retains cause types and bounded frames.

Use Spring ExponentialBackOff with an explicit maxAttempts of configured processing attempts minus
one, retaining the initial interval, multiplier and maximum interval. One processing attempt means
no retries; zero delays mean immediate retries bounded by that count. Supply a per-record observed
BackOffExecution through DefaultErrorHandler.setBackOffFunction. Increment retry and emit its WARN
only when nextBackOff returns a delay rather than STOP, after Spring's fatal classification has
selected the applicable backoff. Do not infer retry scheduling from RetryListener.failedDelivery.
deliveryAttempt identifies the failed attempt preceding the scheduled retry within the current
retry cycle; use the log-only failureCode RETRYABLE_PROCESSING_FAILURE. Retain Spring's retry-state
resets on exception changes and recovery failure and the existing DLT failure-code contract.

Timer inventory.reservation.cancellation.completion.delay records Duration between occurredAt
and the application clock immediately after RELEASED or ALREADY_AVAILABLE commit. Duplicates and
failures do not contribute. Future occurredAt yields a clock_skew WARN and increments
inventory.reservation.cancellation.clock.skew; it contributes no negative/zero delay sample.
Use an injectable Clock for deterministic boundary tests. Histograms cover 1ms–7d.

Keep existing diagnostic DLT headers and add valid traceparent/tracestate only. A stateless Kafka
ConsumerInterceptor normalizes every polled record before Spring starts listener observations.
Register it through Boot's DefaultKafkaConsumerFactoryCustomizer, retaining configured interceptors
and appending the normalizer last exactly once; keep Boot's default listener-container factory.
Before tracing extraction, normalize W3C headers to one valid context with each header limited to
512 bytes, using the last header value; remove baggage and unsupported
propagation headers; invalid context is ignored, never an event rejection. The observed producer
injects its current span context into the outgoing record. Existing context-free events remain
processable. DLT partition, bytes, default retry schedule, inbox transaction and offset rules remain
intact. Regression tests inspect headers at observation start through a real Kafka consumer and
serialize actual retry/recovery callback events through the configured ECS encoder.

## 5. Cleanup monitoring

AC3.1–AC3.3: daily deletion and its separate chunk transactions remain unchanged. Keep the
existing backlog gauge but initialize it to NaN and refresh independently on a fixed 60-second
delay (first run immediately). Set scheduling pool size two so monitoring can run during cleanup.
The repository count query has a five-second JPA query timeout; scrapes only read memory.
Successful refresh stores count and epoch-seconds last-success timestamp. Failed refresh keeps
both values, increments inventory.idempotency.cleanup.backlog.refresh.failures and logs safely.
Gauges: inventory.idempotency.cleanup.backlog.last.success.timestamp,
inventory.idempotency.cleanup.last.run.timestamp and
inventory.idempotency.cleanup.last.success.timestamp. Timestamp zero means no run/success yet.
Cleanup observation inventory.idempotency.cleanup.run marks caught exceptions as errors and
retains existing duration, deleted and failure meters. A failed measurement does not mark a
successfully completed deletion as failed; monitor errors have their own signal.

## 6. Common handoff, validation and rollout

AC4.1–AC4.2: document Prometheus target inventory:8085/actuator/prometheus, Alloy OTLP endpoint,
ECS parsing and service/environment Loki labels; keep event IDs, trace IDs, offsets and instance
identity in searchable fields rather than Loki stream labels. Document Prometheus instance
identity, ECS traceId/spanId correlation, Tempo datasource navigation and backend-independent
troubleshooting. Common supplies cluster ID and export endpoint/enabled configuration.
Panels cover HTTP/JVM/pools, outcomes/delay/retries/DLT and cleanup backlog/freshness. Alert on
any DLT or publication failure, backlog measurement older than 180 seconds and cleanup success
overdue beyond 26 hours (including no first success after 26 hours of uptime). Kafka exporter
consumer-group lag belongs to Common; SLOs and HTTP alert thresholds follow collected baselines.

Unit tests use Mockito, injected Clock, real simple registries and synthetic sensitive values.
Integration tests explicitly enable tracing and inspect exported sanitized SpanData, plus HTTP
collector failure behavior. Use existing PostgreSQL/Kafka Testcontainers and verify commits,
offsets, duplicate behavior and correlation. Include exporter saturation and privacy at serialized
boundaries. Run mvn -B -ntp clean verify with BUILD SUCCESS and Inventory container smoke tests.
Grafana visibility and durable producer-restart correlation depend on later Common/Reservation
work and are not claimed by Inventory-only verification.
