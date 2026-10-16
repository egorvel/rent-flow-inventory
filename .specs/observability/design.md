# Inventory observability design

## 1. Boundaries and compatibility

Implements AC1.1–AC4.2 without changing business HTTP/OpenAPI or database contracts.
Java 25 and Boot 4.1.0 remain pinned. Existing cancellation and idempotency metrics retain names
and outcome meanings. No migration or shared infrastructure changes are required.
Prefer existing Spring Boot, Micrometer and OpenTelemetry configuration over custom telemetry
filters. Preserve the existing package boundaries; no new sanitization abstraction is required.
This specification supersedes older telemetry-only descriptions of DLT tracing headers,
blanket diagnostic sanitization and cleanup backlog measurement; business behavior remains intact.
The revised diagnostic policy is implemented by tasks.md T10 using native ECS exception formatting
and framework span export. Collection controls and accepted diagnostic content are defined in §3.

## 2. Baseline and management

AC1.1–AC1.3, AC1.5: add Boot-managed Prometheus registry and OpenTelemetry starter.
Management serves health/prometheus at configurable port 8085 and address 127.0.0.1 by default.
Compose overrides the address to 0.0.0.0 without publishing this port. Probes remain available
at /livez and /readyz on 8080 with database-only readiness and hidden details.
Service identity is rent-flow-inventory, environment defaults to local, instance defaults to
HOSTNAME (or standalone). Metrics have bounded service/environment tags; Prometheus supplies
target instance. ECS service node and OTel service.instance.id use the runtime instance.
Enable HTTP histograms with expected range 1ms–10s. Exclude management/probe observations.
Use framework W3C propagation and disable baggage with management.tracing.baggage.enabled=false.
Sampling defaults to 1, retaining the existing user-configured value; reduce it as traffic grows
through the standard property. Trace export is disabled by default through
management.tracing.export.otlp.enabled; configure a full /v1/traces URL (default localhost:4318).
Use HTTP/protobuf, two-second connect/call timeouts and bounded asynchronous batch export
(queue 2048, batch 512). Do not add telemetry dependencies to readiness or Compose depends_on.

## 3. Logging, diagnostic value and data handling

AC1.4, AC2.6: use built-in ECS stdout logging, banner disabled, SLF4J fluent structured fields
and trace/span MDC. Keep ordinary exception messages, cause chains, suppressed exceptions and
frames, subject to Spring Boot's configurable stack-trace length/depth limits. Use its default
printer rather than a custom throwable printer. Do not require a JSON-field allowlist, exporter
wrapper, hardcoded route list, stripped status descriptions, discarded span events or scrubbed
link attributes. Framework diagnostic metadata is retained unless a concrete sensitive-data
capture is identified. Targeted built-in JSON exclusions or collector attribute deletion can
address such a known field; neither is assumed to sanitize arbitrary text recursively.

Internal diagnostics are access-controlled operational data, not public API responses. Equipment
identifiers, database structure and infrastructure names have business value but are not
authentication secrets or customer contact/payment details in this service. Evaluate actual
content and diagnostic benefit rather than banning every identifier. Application code must not
deliberately log credentials, tokens, complete payloads or undecoded Kafka keys, or add customer
contact/payment data to observations. Keep automatic HTTP body/header/query-string capture
disabled. Do not add secrets to MDC or fluent log fields. Public unexpected-error responses
remain generic and never expose internal exception messages.

These collection controls do not promise that arbitrary third-party messages can never contain
data. SQL literals, driver error details and externally supplied paths can contain values.
Normal exception diagnostics and equipment values in database errors are accepted here; review
an actual sensitive-data leak at its source using a targeted framework or collector control.
Do not build a universal text redactor or custom SQL parser preemptively. Telemetry access and
retention remain Common/platform responsibilities; private storage alone does not justify
capturing authentication material or complete request bodies.

### 3.1 Per-data decisions

| Data | Sensitivity and diagnostic value in Inventory | Decision |
| --- | --- | --- |
| Passwords, access/session tokens, Authorization and Cookie values | High: can enable access; their values rarely help diagnose an Inventory failure. | Do not deliberately emit; leave header capture disabled and use library password masking. |
| Customer contact and payment details | High: directly personal/financial; absent from the cancellation contract and unnecessary for equipment lifecycle diagnostics. | Do not add automatic capture or application diagnostic fields for these values. |
| Equipment serial number | Business identifier, not an authentication or customer identifier here; identifies the affected item and its history. | Permit validated serials in useful logs/traces; never as metric or Loki stream labels. |
| Event, trace and span IDs | Correlation metadata with low direct sensitivity; necessary to connect attempts, producers and database work. | Retain searchable fields and span correlation; never use individual IDs as metric/stream labels. |
| Topic, group, partition, offset and Kafka cluster ID | Internal topology/location metadata, not credentials; identifies the record, consumer and target broker cluster. | Retain diagnostics; keep custom metric dimensions bounded. |
| Service/instance, database/table/constraint names and connection metadata | Internal implementation metadata; useful for locating a failing instance, pool or constraint. A credential-bearing connection string is a separate sensitive value. | Retain normal framework metadata; never deliberately log credential-bearing configuration strings. |
| Exception message, causes, suppressed exceptions and stack | May contain business values; essential for distinguishing timeout, lock, constraint and programming failures. | Retain normal diagnostics with built-in size/depth limits; no blanket message removal. |
| SQL statement text | Reveals schema/query shape, normally with placeholders; distinguishes slow or failing queries. Inline literals can contain values. | Retain statement text in query spans; no custom literal parser or guarantee that every literal is redacted. |
| Bound SQL parameter values | Can contain arbitrary input and future sensitive data; useful occasionally but broadly duplicating them has low default value. | Keep datasource parameter capture and Hibernate bind-value logging disabled. |
| Hibernate SQL/driver error detail | May include conflicting keys or a failing row; explains database failure much better than exception class alone. | Restore normal error diagnostics; accept equipment values in error messages. |
| Actual HTTP request path | Can contain equipment serials or arbitrary unmatched input; helps distinguish affected items and failed routes. | Retain in traces; framework route templates or bounded fallback values remain metric labels. Do not put secrets into application URL designs. |
| Query strings and HTTP request/response headers | Arbitrary input, credentials, cookies and search data; low default value for this service's trace correlation. | Leave automatic capture disabled; existing framework tracing needs no wholesale header capture. |
| HTTP bodies, complete Kafka values and undecoded Kafka keys | Arbitrary data, including unknown fields or malformed input; bulky and potentially sensitive. | Do not automatically capture or interpolate into application logs. Inspect quarantined Kafka records through the existing DLT workflow. A validated serial may equal a valid key but is logged as an intentional field, not a raw record dump. |
| Span events, links, error descriptions and additional framework fields | Sensitivity depends on content; carry timelines, relationships and explanations. | Retain defaults; no field-name allowlist or blanket deletion. |
| Kafka configuration dumps | Mix useful configuration with noise; password-typed Kafka settings are masked by the client, while arbitrary strings are not guaranteed masked. | Set only ConsumerConfig, ProducerConfig and AdminClientConfig loggers to WARN; keep operational Kafka warnings/errors. |
| Baggage | Arbitrary propagated metadata; this service has no baggage-dependent diagnostic requirement. | Keep management.tracing.baggage.enabled=false using Spring configuration; no custom Kafka header rewriting. |

### 3.2 Native configuration and JDBC observations

Use Spring Boot's ECS encoder and native structured stack-trace settings. Remove the blanket
SafeLogMembersCustomizer, SafeStackTracePrinter and SanitizingSpanExporter requirements rather
than replacing them with equivalent custom filtering. Restore normal Hibernate logger levels;
do not enable Hibernate bind-value TRACE logging or JDBC parameter capture. Metric/Loki cardinality
limits stay in force independently of privacy. Spring MVC's default convention supplies matched
route templates and bounded unmatched-route values for metrics; in the installed Spring Web
7.0.8, its high-cardinality http.url attribute is getRequestURI(), without the query string.

Pin datasource-micrometer-spring-boot 2.2.1 with Boot-managed Micrometer versions.
Observe CONNECTION and QUERY only; disable parameter inclusion, duplicate query/slow-query
logging, resultset instrumentation and Hikari-specific observation (retain normal pool metrics).
Retain SQL statement attributes and parent/request/connection/query relationships. Parameter
capture being disabled does not redact SQL literals or database error messages. The optional
datasource-micrometer-opentelemetry integration module is absent from the current runtime;
its SQL-sanitization properties must not be claimed effective without that module. Do not add
it or a custom parser solely to reinstate the removed blanket SQL-value guarantee.
No OpenTelemetry Java agent or independent SDK is added.

This policy uses the collection-minimization guidance in
[OpenTelemetry's sensitive-data guidance](https://opentelemetry.io/docs/security/handling-sensitive-data/)
and [OWASP's logging guidance](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html).
They support protecting sensitive inputs while preserving useful operational diagnostics;
they do not prescribe this project's former universal attribute allowlist. Native JSON exclusions
and stack limits are documented in
[Spring Boot logging](https://docs.spring.io/spring-boot/reference/features/logging.html).
The decisions in §3.1 are specific to Inventory's present data and diagnostic needs.

### 3.3 Status-transition method observations

AC1.6–AC1.7: retain the inventory.status.transition observation around
InventoryService.transitionStatus. Open its scope during repository calls, signal and rethrow
RuntimeException and Error, and stop in finally after assigning the bounded outcome tag:
accepted, rejected or error. Business rejections are normal returns and do not signal a technical
error. Keep batch.size and, for rejections, rejected.items as high-cardinality trace attributes;
rejected.items counts validation failures, not all unchanged items in an atomic rejected batch.
Closing the scope restores the caller's observation, including on exceptions.

Use the observation's built-in timer count and duration; add no duplicate invocation counter.
Enable management.metrics.distribution.percentiles-histogram.inventory.status.transition with
Micrometer's default bucket range; no new SLO or latency threshold is selected. Inject the existing
MeterRegistry and record inventory.status.transition.batch.size once per invocation before the
business work. Its count is attempted batches and total amount is submitted items, including
rejections and technical failures. Give this summary no custom labels or histogram. Equipment
identifiers and exact item counts never become metric labels.

These signals describe method execution. The surrounding IdempotentStatusTransitionService
transaction may flush or fail after the observation stops; accepted does not mean committed.
Requests rejected before this method and idempotency replays do not contribute. Preserve existing
locking order, all-or-nothing validation, writes and transaction boundaries. Update direct test
constructor calls with ObservationRegistry.NOOP and a meter registry; use real registries and
handlers to test accepted/rejected/exception lifecycles, final metric tags, scope restoration and
summary count/total. Integration tests verify correlated service/database spans, Prometheus
histogram buckets and summary samples, and exclusion of replay invocations.

## 4. Cancellation processing and DLT

AC2.1–AC2.5: enable listener/template observations and give the listener a stable identifier.
Observed attempts share valid producer context. Framework listener scope includes error handling;
DLT template sends run in that scope. Validate this relationship in Kafka integration tests.
Use the framework's normal Kafka cluster-ID discovery when inventory.kafka.cluster-id is absent
or blank. Apply KafkaAdmin.setClusterId only for an explicitly supplied actual broker cluster ID;
remove the literal unknown fallback. Deployment may supply INVENTORY_KAFKA_CLUSTER_ID on the
Inventory process/container using the identity of its target broker cluster. This value is an
optional telemetry identity override, not a credential, generated application ID, consumer group
or broker-selection setting. Common is not required to supply it for normal operation.

In installed Spring Kafka 4.1.0, topic initialization can populate the cached ID at startup when
NewTopic beans exist. Otherwise listener/template observation can trigger synchronous discovery;
successful discovery is cached, while failed discovery may be attempted again by later observed
operations. The default admin operation timeout is 30 seconds. Accept normal discovery as the
default and document the optional real-ID override for restricted admin access or observed
discovery delays. Do not add a custom discovery cache, retry throttle or dependency upgrade in
this policy revision. Newer-version retry behavior must be checked against the installed version.

Preserve received/released/already_available/duplicate/retry/dead_letter/
dead_letter_publish_failure counter names and meanings; received is listener invocations, including retry;
retry means scheduled retry; success counters are incremented after the transactional proxy returns.
Result INFO logs carry eventId, outcome, sourceTopic, partition and offset. Retry WARN logs carry
deliveryAttempt and bounded failureCode; recovery ERROR logs include failureCode and eventId when
parseable. A validated serialNumber may be included when already available and useful; do not
introduce extra parsing just to add it. Never interpolate complete record values or undecoded keys
into application logs. Attach DLT publication/processing exceptions to their fixed messages so
native exception formatting retains ordinary cause messages and frames under §3's policy.
The existing bounded diagnostic DLT header contract stays unchanged: richer exceptions belong
in correlated diagnostics rather than duplicating stack traces in repeatedly recovered records.

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

Delegate Kafka context extraction, parsing and propagation to Spring Kafka, Micrometer and
OpenTelemetry, using Boot's default consumer and listener-container factories. Add no custom
tracing-header validator, normalizer or consumer interceptor; header parsing and selection follow
the configured libraries. Incoming record headers remain untouched by application tracing code.
The observed template supplies the outgoing DLT traceparent/tracestate from the current listener
scope alongside the existing diagnostic DLT headers. Missing or unusable tracing context does not
reject a business event. DLT partition, bytes, default retry schedule, inbox transaction and offset
rules remain intact. Integration tests prove producer/consumer and retry/DLT correlation, processing
without usable context, and disabled baggage propagation. Serialize actual retry/recovery callback
events through the configured ECS encoder.

## 5. Cleanup monitoring

AC3.1–AC3.3: daily deletion and its separate chunk transactions remain unchanged. Keep the
existing backlog gauge but initialize it to NaN and refresh independently on a fixed 60-second
delay (first run immediately). Set scheduling pool size two so monitoring can run during cleanup.
The repository count query has a five-second JPA query timeout; scrapes only read memory.
Successful refresh stores count and epoch-seconds last-success timestamp. Failed refresh keeps
both values, increments inventory.idempotency.cleanup.backlog.refresh.failures and logs the failure
with ordinary exception diagnostics under §3.
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
troubleshooting. Common supplies export endpoint/enabled configuration and may supply the actual
Kafka cluster ID when bypassing discovery is useful.
Panels cover HTTP/JVM/pools, outcomes/delay/retries/DLT and cleanup backlog/freshness. Alert on
any DLT or publication failure, backlog measurement older than 180 seconds and cleanup success
overdue beyond 26 hours (including no first success after 26 hours of uptime). Kafka exporter
consumer-group lag belongs to Common; SLOs and HTTP alert thresholds follow collected baselines.

Unit tests use Mockito, injected Clock and real simple registries. Integration tests explicitly
enable tracing and inspect exported SpanData, plus HTTP collector failure behavior. Assert useful
diagnostics survive serialization: ordinary exception/cause messages, SQL statement text, request
paths, framework events and identifiers where emitted. Verify configured body/header/query-string,
baggage and bound-parameter capture exclusions using representative sensitive input; invalid
Kafka conversion logs must not echo raw key/payload. Do not assert that every arbitrary synthetic
string embedded in a throwable, SQL literal or path is redacted. Keep public 500 responses generic
and metric labels bounded. Use existing PostgreSQL/Kafka Testcontainers and verify commits,
offsets, duplicate behavior, correlation and exporter saturation. Run mvn -B -ntp clean verify
with BUILD SUCCESS and Inventory container smoke tests.
Grafana visibility and durable producer-restart correlation depend on later Common/Reservation
work and are not claimed by Inventory-only verification.
