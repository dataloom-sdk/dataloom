package io.dataloom.runtime.observation.health

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.DataLoomRedactor
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetrySnapshot

/**
 * Redacted, diagnostics-safe view of one provider's [ProviderHealth].
 *
 * [errorAttributes] is produced by the same code/category/severity/
 * recoverability-are-`PUBLIC`, message-is-`CONFIDENTIAL`,
 * cause-is-never-included convention [SynchronizationOperationalEventBridge]
 * already applies to every other [DataLoomError] this SDK exports, so under
 * the default [DataLoomRedactor] only the closed vocabularies survive and
 * [DataLoomError.message] is removed outright. [DataLoomError.cause] (a raw
 * [Throwable]) is never read.
 *
 * [detailFieldCount] discloses how many entries [ProviderHealth.details]
 * carried without disclosing their names or values -- the same bound
 * [io.dataloom.api.context.DataLoomMetadata]'s own redacted `toString()`
 * already exposes. Field-level redaction of arbitrary provider-supplied
 * metadata keys is deliberately out of scope: nothing in this codebase
 * today converts free-form [io.dataloom.api.context.DataLoomMetadata] keys
 * (which are only required to be non-blank) into the bounded ASCII tokens
 * [ClassifiedData] requires, and inventing that conversion is a separate,
 * larger concern than this bounded first slice.
 */
public data class RedactedProviderHealth(
    public val status: ProviderHealthStatus,
    public val errorAttributes: RedactedAttributes,
    public val detailFieldCount: Int,
)

/**
 * Point-in-time, redacted snapshot of already-queryable in-process
 * subsystem state.
 *
 * ## Scope -- what this is
 * A bounded aggregation of whichever already-synchronously-queryable
 * subsystem state a caller supplies at the moment of the call:
 * - [providerLifecycleState] -- the current
 *   `ProviderLifecycleCoordinator.state`.
 * - [retryCircuitTelemetry] -- the current
 *   `BoundedRetryCircuitTelemetry.snapshot()`.
 * - [providerHealth] -- already-redacted results of `DataLoomProvider
 *   .health()` calls the caller performed itself, keyed by [ProviderId].
 *
 * ## Scope -- what this deliberately is not
 * - Not a live or continuous monitoring feed -- callers decide when to
 *   call [dataLoomHealthSnapshot] and get back exactly one instant.
 * - Not backed by any new durable storage -- nothing here is persisted.
 * - Not cross-process or cross-node aggregation -- one snapshot describes
 *   exactly one process's in-memory state.
 * - Not a historical or trend view -- only the instant of the call.
 * - Not a deployable service or dashboard -- that remains a separate,
 *   larger, still fully open gap.
 *
 * A section a caller has nothing to report for is `null` (or empty for
 * [providerHealth]), never a failure.
 */
public data class DataLoomHealthSnapshot(
    public val providerLifecycleState: ProviderLifecycleCoordinatorState?,
    public val retryCircuitTelemetry: RetryCircuitTelemetrySnapshot?,
    public val providerHealth: Map<ProviderId, RedactedProviderHealth>,
)

/**
 * Builds a [DataLoomHealthSnapshot] purely from already-available,
 * caller-supplied state.
 *
 * This function performs no I/O, never suspends, and calls no provider or
 * collaborator directly -- every argument reflects state the caller already
 * obtained on its own (e.g. reading `lifecycleCoordinator.state`, calling
 * `telemetry.snapshot()`, or awaiting `provider.health()` itself). Omitting
 * a collaborator entirely (leaving a parameter at its default) is
 * well-defined: the corresponding section is `null`/empty in the result,
 * never a crash.
 */
public fun dataLoomHealthSnapshot(
    providerLifecycleState: ProviderLifecycleCoordinatorState? = null,
    retryCircuitTelemetry: RetryCircuitTelemetrySnapshot? = null,
    providerHealth: Map<ProviderId, ProviderHealth> = emptyMap(),
    redactor: DataLoomRedactor = StrictDataLoomRedactor(),
): DataLoomHealthSnapshot = DataLoomHealthSnapshot(
    providerLifecycleState = providerLifecycleState,
    retryCircuitTelemetry = retryCircuitTelemetry,
    providerHealth = providerHealth.mapValues { (_, health) -> redactProviderHealth(health, redactor) },
)

private fun redactProviderHealth(
    health: ProviderHealth,
    redactor: DataLoomRedactor,
): RedactedProviderHealth {
    val error = health.error
    val errorAttributes = if (error == null) {
        RedactedAttributes.Empty
    } else {
        redactor.redact(ClassifiedData.of(errorClassifiedAttributes(error))).attributes
    }
    return RedactedProviderHealth(
        status = health.status,
        errorAttributes = errorAttributes,
        detailFieldCount = health.details.entries.size,
    )
}

private fun errorClassifiedAttributes(error: DataLoomError): Map<String, ClassifiedDataValue> = mapOf(
    "code" to ClassifiedDataValue(error.code.value, DataClassification.PUBLIC),
    "category" to ClassifiedDataValue(error.category.name, DataClassification.PUBLIC),
    "severity" to ClassifiedDataValue(error.severity.name, DataClassification.PUBLIC),
    "recoverability" to ClassifiedDataValue(error.recoverability.name, DataClassification.PUBLIC),
    "message" to ClassifiedDataValue(
        error.message.take(MAX_ERROR_MESSAGE_LENGTH),
        DataClassification.CONFIDENTIAL,
    ),
)

private const val MAX_ERROR_MESSAGE_LENGTH: Int = 4_096
