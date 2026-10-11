# Common observability integration handoff

Common owns the shared Grafana/Prometheus/Loki/Tempo/Alloy stack. Inventory's telemetry
configuration and metric/log contracts are documented in [README.md](README.md#observability).

In Common, supply `TRACING_EXPORT_ENABLED=true`, a full endpoint such
as `http://rentflow-alloy:4318/v1/traces`. Optionally supply `INVENTORY_KAFKA_CLUSTER_ID` with the
actual target broker identity when bypassing discovery is useful; otherwise leave it unset.
Spring Kafka 4.1.0 caches successful discovery but can retry failed lookups during observed work.
Optional collectors do not belong in Inventory's Compose `depends_on`.

- Scrape `inventory:8085/actuator/prometheus`, initially every 15 seconds. Metrics carry
  `service="rent-flow-inventory"` and `environment`; Prometheus owns the scrape `instance` label.
- Collect container stdout with Alloy and parse ECS JSON. Use bounded service/environment Loki
  labels; keep trace IDs, event IDs, equipment serials, offsets and runtime instance as searchable
  fields. Logs/traces retain ordinary exception messages, SQL text, request paths and framework
  metadata. They are internal operational data; Common controls access and retention. Automatic
  body/header/query-string and SQL parameter capture remain disabled. Target a demonstrated
  sensitive field with collector controls rather than stripping diagnostic content wholesale.
- Receive OTLP traces at Alloy, forward to Tempo, and configure Grafana Loki derived fields from
  `traceId` to Tempo. Configure Prometheus exemplars and the same Tempo datasource.
- Provision HTTP/JVM/pool panels, cancellation outcomes/delay/retry/DLT panels, and cleanup
  backlog/freshness/worker panels. Consumer-group lag comes from Common's Kafka exporter.
- Alert on any increase over five minutes in DLT or DLT publication failures. Alert when
  `time() - inventory_idempotency_cleanup_backlog_last_success_timestamp > 180` (zero also alerts
  after startup grace). Alert on a last successful cleanup older than 26 hours, using application
  uptime to grant the first 26 hours when the timestamp is zero. Add absent-scrape detection.
  Select HTTP/SLO thresholds after collecting representative baselines.

To investigate a slow request, locate its route histogram and trace, then compare request time
with connection acquisition and query execution spans and their SQL text. To investigate cancellation,
search logs by event ID or equipment serial, follow `traceId`, and inspect exception causes,
retries or DLT failure codes; compare completion-delay
histograms with consumer-group lag. For cleanup, check measurement age before interpreting the
backlog, then compare run/success timestamps and failure counters.

Inventory tests prove instrumentation, privacy, persistence/offset behavior and collector failure
isolation. Full Grafana visibility and durable Reservation restart correlation require the separate
Common and Reservation implementations.
