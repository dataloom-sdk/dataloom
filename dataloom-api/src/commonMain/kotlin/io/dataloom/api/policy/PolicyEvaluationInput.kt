package io.dataloom.api.policy

import io.dataloom.api.configuration.ConfigurationSnapshot
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderId

/**
 * Immutable input to one [PolicyCheck] or [PolicyEvaluator.evaluate] call.
 *
 * This is the generalized "immutable input" ADR-0002's `### Policy` section
 * describes: it captures execution context, runtime state evidence, provider
 * health, a configuration snapshot reference, and tenant/trace identity —
 * everything a [PolicyCheck] is allowed to consult. A [PolicyCheck] must not
 * consult anything not reachable from this input.
 *
 * ## Composed, not duplicated
 *
 * [executionContext] already carries [ExecutionContext.tenantId],
 * [ExecutionContext.traceId], [ExecutionContext.correlationId], and
 * [ExecutionContext.executionId] — exactly the "tenant and trace" capture
 * `#93`'s required scope and ADR-0002 call for. This type does not repeat
 * those fields. Likewise [providerHealth] reuses
 * [io.dataloom.api.provider.ProviderHealth] — the same generic provider
 * health snapshot the provider SPI already defines — rather than inventing a
 * second one, and [configurationSnapshot] references the already-shipped,
 * checksummed, versioned [ConfigurationSnapshot] rather than re-encoding
 * configuration values inline.
 *
 * ## Why [configurationSnapshot] is required, not nullable
 *
 * The precedence override described on [PolicyEvaluator.evaluate] (`required
 * user action dominates delay unless an approved configuration says
 * otherwise`) is resolved entirely from [configurationSnapshot]. Making it
 * nullable would require a second, special-cased default-precedence path for
 * the "no snapshot" case; requiring it keeps precedence resolution
 * single-pathed. A caller with no real configuration to route through policy
 * can supply an empty, schema-validated snapshot.
 *
 * ## Why [stateEvidence] is [DataLoomMetadata], not a new typed evidence class
 *
 * [io.dataloom.api.strategy.StrategyRuntimeEvidence] is the closest existing
 * analog in this codebase, but its enums are shaped around synchronization
 * strategy selection specifically — they do not generalize to plugin
 * permissions, residency, or administrative overrides, and reusing them here
 * would relocate the "subsystem invents its own state model" problem `#93`
 * exists to prevent, just in the opposite direction. [DataLoomMetadata] is
 * already this codebase's established bounded, non-sensitive key/value
 * evidence primitive; reusing it means each eventual consumer contributes
 * evidence under its own key convention without this foundation having to
 * predict or enumerate every subsystem's vocabulary up front.
 *
 * ## Determinism
 *
 * Two [PolicyEvaluationInput] instances that are `equal` (structural,
 * value-based equality) must always evaluate to the same result from the
 * same [PolicyCheck]/[PolicyEvaluator]. Construction performs no I/O, clock
 * reads, or identifier generation.
 *
 * ## Sensitive-data restrictions
 *
 * [stateEvidence] must not contain credentials, authentication tokens,
 * encryption keys, personal data, or payload bytes — the same restriction
 * [DataLoomMetadata] itself documents.
 *
 * @param executionContext the caller-supplied execution context. Supplies
 *   tenant, trace, correlation, request, user, and locale identity — this
 *   input does not duplicate any of those fields.
 * @param configurationSnapshot the resolved, checksummed configuration
 *   snapshot in effect for this evaluation. See "Why required, not nullable"
 *   above.
 * @param providerHealth non-sensitive provider health evidence, keyed by
 *   [ProviderId]. Defaults to empty when no provider health evidence is
 *   available. A missing entry means "not supplied," not "healthy."
 * @param stateEvidence optional bounded, non-sensitive evidence about
 *   runtime/domain state relevant to the checks being evaluated (for example
 *   cache freshness, pending-change counts, plugin installation state).
 *   Defaults to [DataLoomMetadata.Empty].
 */
public data class PolicyEvaluationInput(
    public val executionContext: ExecutionContext,
    public val configurationSnapshot: ConfigurationSnapshot,
    public val providerHealth: Map<ProviderId, ProviderHealth> = emptyMap(),
    public val stateEvidence: DataLoomMetadata = DataLoomMetadata.Empty,
)
