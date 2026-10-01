package io.dataloom.runtime.observation.health

/**
 * Renders an already-built [DataLoomHealthSnapshot] as
 * [Prometheus text exposition format](https://github.com/prometheus/docs/blob/main/content/docs/instrumenting/exposition_formats.md).
 *
 * ## Scope -- what this is
 * A pure formatting layer over state that already exists: everything this
 * function reads was already computed by [dataLoomHealthSnapshot] from
 * collaborators the caller supplied itself (a provider's `health()` result,
 * an [OperationalEventOutboxHealthTracker] snapshot, a [QueueWorkerHealthTracker]
 * snapshot). This function performs no I/O, never suspends, reads no clock,
 * and adds no new instrumentation or collection pipeline -- it only maps
 * [DataLoomHealthSnapshot]'s fields to the Prometheus text format's line
 * syntax.
 *
 * `DataLoom` is a Kotlin Multiplatform library, not a service: it never runs
 * an HTTP server and never will as part of this slice. A host application
 * makes this "deployable" by calling [dataLoomPrometheusMetrics] from its own
 * `/metrics` HTTP handler (or whatever scrape/export transport it already
 * runs) on its own schedule. This is the same opt-in, host-wires-the-transport
 * shape this SDK already applies elsewhere:
 * [io.dataloom.runtime.observation.retry.RetryCircuitStructuredLogExporter]
 * and [io.dataloom.runtime.observation.retry.RetryCircuitTraceExporter] convert
 * telemetry events to a vendor-neutral record the host writes to its own
 * logger/tracer; every
 * `*OperationalEventOutboxSpec` (for example
 * [io.dataloom.runtime.facade.DataLoomAssetTransferOperationalEventOutboxSpec])
 * takes a host-supplied [io.dataloom.api.state.DurableStateStore] rather than
 * DataLoom choosing or running one. This function is the same pattern applied
 * to health: DataLoom produces correctly-formatted text, the host decides how
 * (or whether) to serve it.
 *
 * ## What is exported
 * - [DataLoomHealthSnapshot.severity] as `dataloom_health_severity` (no labels).
 * - A roll-up per [DataLoomHealthComponent] as
 *   `dataloom_health_component_severity{component="..."}` -- one line for
 *   every value of that closed, four-value enum, `0` (`HEALTHY`) when the
 *   component raised no finding in this snapshot.
 * - Per-scope outbox depth as `dataloom_outbox_pending_entries{scope="..."}`
 *   and `dataloom_outbox_acknowledged_retained_entries{scope="..."}` --
 *   emitted only for a scope [OperationalEventOutboxHealth] actually observed
 *   (the corresponding count is non-`null`); a scope this process has never
 *   observed is omitted entirely, never reported as `0`, matching
 *   [OperationalEventOutboxHealth]'s own "never observed" contract.
 * - Per-scope outbox severity as `dataloom_outbox_severity{scope="..."}`,
 *   whenever at least one scope is present (this field has no "never
 *   observed" state -- it defaults to `HEALTHY`).
 * - Queue worker gauges, only when [DataLoomHealthSnapshot.queueWorkerHealth]
 *   is non-`null`: `dataloom_queue_worker_runs_in_flight`,
 *   `dataloom_queue_worker_consecutive_failed_runs`,
 *   `dataloom_queue_worker_severity`.
 * - Per-provider health as `dataloom_provider_health_status{provider="..."}`,
 *   one line per entry already present in
 *   [DataLoomHealthSnapshot.providerHealth], sorted by provider id for
 *   deterministic output.
 *
 * Every severity/status value is the relevant enum's `ordinal` in its
 * current, documented declaration order (stated per metric below). Pre-V1,
 * reordering one of these enums is an accepted breaking change like any
 * other public API change in this codebase (see the repository's ADR on
 * pre-V1 compatibility posture) -- it is not silently absorbed here.
 *
 * ## What is deliberately out of scope for this slice
 * - No HTTP server, no `/metrics` route, no scrape scheduling, no caching --
 *   the host owns all of that.
 * - No OTLP, no push export, no Grafana dashboard JSON, no bespoke protocol.
 *   Prometheus text exposition was chosen as the most bounded, dependency-free,
 *   well-precedented option for a pull-based exporter; nothing here blocks a
 *   separately scoped push exporter from being added alongside it later.
 * - No cross-process, cross-instance, or historical aggregation -- exactly
 *   one [DataLoomHealthSnapshot] renders as exactly one text payload, the
 *   same single-instant scope [DataLoomHealthSnapshot] itself documents.
 * - No new metrics-collection pipeline of any kind: everything rendered here
 *   already existed in [DataLoomHealthSnapshot] before this function was added.
 */
public fun dataLoomPrometheusMetrics(snapshot: DataLoomHealthSnapshot): String {
    val out = StringBuilder()

    writeMetric(
        out, "dataloom_health_severity",
        "Overall DataLoomHealthSnapshot severity (0=HEALTHY,1=DEGRADED,2=UNHEALTHY).",
    ) {
        sample(out, "dataloom_health_severity", emptyList(), snapshot.severity.ordinal)
    }

    writeMetric(
        out, "dataloom_health_component_severity",
        "Maximum finding severity per DataLoomHealthComponent " +
            "(0=HEALTHY,1=DEGRADED,2=UNHEALTHY); 0 when the component raised no finding " +
            "(component is one of PROVIDER, TELEMETRY_EXPORTER, OPERATIONAL_EVENT_OUTBOX, QUEUE_WORKER).",
    ) {
        DataLoomHealthComponent.entries.forEach { component ->
            val severity = snapshot.findings
                .filter { it.component == component }
                .maxOfOrNull { it.severity }
                ?: DataLoomHealthSeverity.HEALTHY
            sample(out, "dataloom_health_component_severity", listOf("component" to component.name), severity.ordinal)
        }
    }

    val pendingObserved = snapshot.outboxHealth.filter { it.pendingCount != null }
    if (pendingObserved.isNotEmpty()) {
        writeMetric(
            out, "dataloom_outbox_pending_entries",
            "Pending entries in a durable operational-event outbox scope, as of the last " +
                "observation this process made. A scope never observed by this process is omitted.",
        ) {
            pendingObserved.forEach { outbox ->
                sample(out, "dataloom_outbox_pending_entries", listOf("scope" to outbox.scope.value), checkNotNull(outbox.pendingCount))
            }
        }
    }

    val acknowledgedObserved = snapshot.outboxHealth.filter { it.acknowledgedRetainedCount != null }
    if (acknowledgedObserved.isNotEmpty()) {
        writeMetric(
            out, "dataloom_outbox_acknowledged_retained_entries",
            "Retained acknowledged (tombstoned) entries in a durable operational-event outbox " +
                "scope, as of the last observation this process made. A scope never observed by " +
                "this process is omitted.",
        ) {
            acknowledgedObserved.forEach { outbox ->
                sample(
                    out, "dataloom_outbox_acknowledged_retained_entries",
                    listOf("scope" to outbox.scope.value),
                    checkNotNull(outbox.acknowledgedRetainedCount),
                )
            }
        }
    }

    if (snapshot.outboxHealth.isNotEmpty()) {
        writeMetric(
            out, "dataloom_outbox_severity",
            "Per-scope outbox health severity (0=HEALTHY,1=DEGRADED,2=UNHEALTHY).",
        ) {
            snapshot.outboxHealth.forEach { outbox ->
                sample(out, "dataloom_outbox_severity", listOf("scope" to outbox.scope.value), outbox.severity.ordinal)
            }
        }
    }

    snapshot.queueWorkerHealth?.let { worker ->
        writeMetric(out, "dataloom_queue_worker_runs_in_flight", "Queue worker runs currently in flight.") {
            sample(out, "dataloom_queue_worker_runs_in_flight", emptyList(), worker.runsInFlight)
        }
        writeMetric(
            out, "dataloom_queue_worker_consecutive_failed_runs",
            "Queue worker's current consecutive failed run streak.",
        ) {
            sample(out, "dataloom_queue_worker_consecutive_failed_runs", emptyList(), worker.consecutiveFailedRuns)
        }
        writeMetric(
            out, "dataloom_queue_worker_severity",
            "Queue worker health severity (0=HEALTHY,1=DEGRADED,2=UNHEALTHY).",
        ) {
            sample(out, "dataloom_queue_worker_severity", emptyList(), worker.severity.ordinal)
        }
    }

    if (snapshot.providerHealth.isNotEmpty()) {
        writeMetric(
            out, "dataloom_provider_health_status",
            "Redacted provider health status (0=UNKNOWN,1=HEALTHY,2=DEGRADED,3=UNHEALTHY).",
        ) {
            snapshot.providerHealth.entries.sortedBy { it.key.value }.forEach { (providerId, health) ->
                sample(out, "dataloom_provider_health_status", listOf("provider" to providerId.value), health.status.ordinal)
            }
        }
    }

    return out.toString()
}

private inline fun writeMetric(out: StringBuilder, name: String, help: String, samples: () -> Unit) {
    out.append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n')
    out.append("# TYPE ").append(name).append(" gauge\n")
    samples()
}

private fun sample(out: StringBuilder, name: String, labels: List<Pair<String, String>>, value: Int) {
    out.append(name)
    if (labels.isNotEmpty()) {
        out.append('{')
        labels.forEachIndexed { index, (labelName, labelValue) ->
            if (index > 0) out.append(',')
            out.append(labelName).append("=\"").append(escapeLabelValue(labelValue)).append('"')
        }
        out.append('}')
    }
    out.append(' ').append(value).append('\n')
}

/**
 * Prometheus text exposition format escaping for a label value: backslash
 * and double-quote are escaped, and a literal line feed is escaped as `\n`
 * (label values are single-line).
 */
private fun escapeLabelValue(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

/** Prometheus text exposition format escaping for a HELP line: backslash and line feed only. */
private fun escapeHelp(value: String): String =
    value.replace("\\", "\\\\").replace("\n", "\\n")
