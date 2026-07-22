package io.dataloom.api.scheduling

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.context.DataLoomMetadata

/**
 * Immutable execution constraints for a scheduled synchronization request.
 *
 * [ScheduleConstraints] describes the minimum conditions that should be
 * satisfied before the platform scheduler triggers the associated
 * synchronization. The constraints are a declaration of intent only.
 *
 * ## Construction behaviour
 *
 * Construction does not query connectivity state, inspect charging state, or
 * access platform APIs. Constraint evaluation is the responsibility of the
 * concrete [SchedulerProvider] and
 * [io.dataloom.api.connectivity.ConnectivityProvider] implementations.
 *
 * ## Unsupported constraints
 *
 * Platform providers may not support every constraint combination. Unsupported
 * constraints must be reported by the concrete provider using a canonical
 * [io.dataloom.api.error.DataLoomError].
 *
 * ## Sensitive-data restrictions
 *
 * [metadata] must not contain credentials, authentication tokens, encryption
 * keys, personal data, or full application payloads.
 *
 * ## Equality
 *
 * Equality compares [connectivity], [requiresCharging], and [metadata] by
 * value.
 *
 * @param connectivity minimum connectivity requirement. Defaults to
 *   [ConnectivityRequirement.NONE].
 * @param requiresCharging when `true`, the platform scheduler should prefer
 *   execution while the device is charging. Defaults to `false`. Charging
 *   constraint support is platform-defined.
 * @param metadata optional contextual attributes for this constraint set.
 *   Defaults to [DataLoomMetadata.Empty].
 */
public data class ScheduleConstraints(
    /**
     * Minimum connectivity requirement.
     *
     * Defaults to [ConnectivityRequirement.NONE], meaning no connectivity is
     * required before execution.
     */
    public val connectivity: ConnectivityRequirement = ConnectivityRequirement.NONE,

    /**
     * When `true`, the platform scheduler should prefer execution while the
     * device is charging.
     *
     * Defaults to `false`. Charging-constraint support is platform-defined;
     * concrete providers that cannot enforce this constraint must return a
     * canonical [io.dataloom.api.error.DataLoomError].
     */
    public val requiresCharging: Boolean = false,

    /**
     * Optional contextual attributes for this constraint set.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied. Must not
     * contain credentials, tokens, encryption keys, or personal data.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
