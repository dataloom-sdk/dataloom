package io.dataloom.core.plugin

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.security.StrictDataLoomRedactor

/**
 * Stateless bridge from [PluginLifecycleTransitionRequest]/
 * [PluginLifecycleTransitionResult] -- the exact request and outcome
 * [PluginLifecycleStateTracker]'s authorizer-aware `transition(request,
 * authorizer)` overload already produces -- to [OperationalEventEnvelope],
 * the canonical DL-042 envelope
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] persists.
 *
 * ## Why this lives in `dataloom-core`, not `dataloom-runtime`
 *
 * The directly analogous precedents this bridge follows --
 * [io.dataloom.runtime.observation.operational.RetryCircuitAdministrationOperationalEventBridge]
 * and
 * [io.dataloom.runtime.observation.operational.ConflictResolutionOperationalEventBridge]
 * -- live in `dataloom-runtime` because the domain types they bridge from
 * (`RetryAdministrationResult`, `CircuitAdministrationResult`,
 * `UnresolvedConflictRecord`) live there. [PluginLifecycleTransitionRequest]
 * and [PluginLifecycleTransitionResult] live in this module
 * (`io.dataloom.core.plugin`), and [OperationalEventEnvelope] itself lives in
 * `dataloom-api`, which `dataloom-core` already depends on directly (see
 * `docs/architecture/modules.md`) -- so, unlike execution-bounds enforcement's
 * new `kotlinx-coroutines-core` dependency, this bridge needs no new module
 * dependency and no new module at all. It is colocated with the rest of
 * `#98`'s own engine, the same way [PluginPermission.asCapability] is
 * colocated here rather than pushed into a separate module.
 *
 * ## Every transition outcome is bridged, not only denials
 *
 * Every [PluginLifecycleTransitionResult] variant this authorizer-aware
 * overload can return --
 * [PluginLifecycleTransitionResult.Allowed], [PluginLifecycleTransitionResult.Rejected],
 * and [PluginLifecycleTransitionResult.AuthorizationDenied] -- is bridged
 * here, following [RetryCircuitAdministrationOperationalEventBridge]'s own
 * precedent of bridging every terminal outcome, not only failure ones: an
 * audit trail that only records denials cannot answer "was this plugin ever
 * legitimately disabled," only "was it ever refused." [PluginLifecycleTransitionResult.PermissionDenied]
 * is also bridged even though it is returned by a different
 * [PluginLifecycleStateTracker] overload (the capability-aware one, not the
 * authorizer-aware one this class's own request type is paired with) --
 * [toEnvelope] accepts it too so one bridge covers every denial reason this
 * engine can produce for a lifecycle transition, rather than forcing a
 * caller that wants complete audit coverage to maintain two bridges.
 *
 * ## No clock read, no identifier generation
 *
 * [toEnvelope] never reads a clock and never generates an identifier.
 * [OperationalEventEnvelope.occurredAt] is always
 * [PluginLifecycleTransitionRequest.requestedAt] -- an already-caller-supplied
 * timestamp -- never a fresh read, mirroring every existing operational-event
 * bridge in this codebase. Unlike
 * [RetryCircuitAdministrationOperationalEventBridge], there is only ever one
 * timestamp available here: [PluginLifecycleStateTracker] is deliberately
 * non-durable (see its own "Thread-safety boundary" documentation), so there
 * is no separately-persisted `updatedAt` a terminal durable record could
 * supply instead. [OperationalEventEnvelope.id] is derived from the
 * already-unique [PluginLifecycleTransitionRequest.commandId] (see
 * [operationalEventId]), and [OperationalEventEnvelope.correlationId] reuses
 * that same identifier unchanged -- the natural correlation key a caller
 * already uses to identify one logical transition request.
 *
 * ## Classification
 *
 * Every field placed into [OperationalEventEnvelope.attributes] first enters
 * [ClassifiedData] with an explicit [DataClassification] and is redacted by
 * [StrictDataLoomRedactor], following
 * [RetryCircuitAdministrationOperationalEventBridge]'s own documented rules:
 * enum names (lifecycle state labels) are `PUBLIC`. Operational identifiers
 * not obviously safe to disclose ([PluginLifecycleTransitionRequest.pluginId],
 * [PluginLifecycleTransitionRequest.principalId]) are `INTERNAL`. Host- or
 * plugin-manifest-supplied strings that are not a codebase-closed enum --
 * [PluginLifecycleTransitionResult.Rejected.reason] (composed from enum names
 * by [PluginLifecycleTransitions] but typed as a plain `String`, not a closed
 * vocabulary), [PluginLifecycleTransitionResult.AuthorizationDenied.reasonCode]
 * (host-authorizer supplied), and
 * [PluginLifecycleTransitionResult.PermissionDenied.missingPermissions]
 * (plugin-manifest-declared permission labels) -- are all `INTERNAL`, the
 * more conservative reading consistent with the precedent's own treatment of
 * `rejectionReasonCode`.
 * [PluginLifecycleTransitionRequest.reason] (caller-supplied free-text
 * justification) is never included at all, the same treatment the precedent
 * gives caller-supplied metadata such as `RetryAdministrationReason`/
 * `CircuitAdministrationReason`.
 *
 * ## Payload descriptor
 *
 * Content-free, following [RetryCircuitAdministrationOperationalEventBridge]'s
 * own convention: every field this bridge has to offer already goes through
 * [OperationalEventEnvelope.attributes] instead.
 *
 * ## No wiring yet
 *
 * Nothing in this codebase calls [toEnvelope] today. Exactly like
 * [PluginExecutionBoundsEnforcer] before it, this is available infrastructure
 * for a future facade (mirroring
 * `io.dataloom.runtime.facade.DefaultDataLoomRetryAdministration`'s "swallow
 * append failures" posture) once one is wired into `DataLoomBuilder` for the
 * plugin engine -- see `docs/api/plugin-registry.md`'s "No wiring into
 * `DataLoomBuilder` yet".
 */
public object PluginLifecycleAdministrationOperationalEventBridge {

    private const val SOURCE_VALUE: String = "dataloom.core.plugin.lifecycle.administration"
    private const val PAYLOAD_TYPE_VALUE: String = "dataloom.plugin.lifecycle.administration.event"
    private const val PAYLOAD_ENCODING_VALUE: String = "none"
    private const val MAX_OPERATIONAL_TOKEN_LENGTH: Int = 128

    private val SOURCE: OperationalEventSource = OperationalEventSource(SOURCE_VALUE)
    private val ENVELOPE_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_TYPE: OperationalPayloadType = OperationalPayloadType(PAYLOAD_TYPE_VALUE)
    private val PAYLOAD_ENCODING: OperationalPayloadEncoding = OperationalPayloadEncoding(PAYLOAD_ENCODING_VALUE)

    private val redactor: StrictDataLoomRedactor = StrictDataLoomRedactor()

    /**
     * Maps one plugin lifecycle-transition [request] and its [result] to an
     * [OperationalEventEnvelope].
     *
     * May throw [IllegalArgumentException] if a derived envelope field fails
     * its own validation. Any real caller of this bridge should wrap this
     * call so such a failure is swallowed rather than allowed to affect the
     * transition result it is describing -- see this class's own "No wiring
     * yet" documentation.
     */
    public fun toEnvelope(
        request: PluginLifecycleTransitionRequest,
        result: PluginLifecycleTransitionResult,
    ): OperationalEventEnvelope {
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(request, result))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId(request.commandId.value),
            type = OperationalEventType(eventTypeValue(result)),
            source = SOURCE,
            category = OperationalEventCategory.AUDIT,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = request.requestedAt,
            correlationId = CorrelationId(request.commandId.value),
            payload = OperationalPayloadDescriptor(
                type = PAYLOAD_TYPE,
                schemaVersion = PAYLOAD_SCHEMA_VERSION,
                encoding = PAYLOAD_ENCODING,
                classification = DataClassification.INTERNAL,
                encodedSizeBytes = null,
            ),
            attributes = attributes,
        )
    }

    private fun operationalEventId(rawCommandId: String): OperationalEventId {
        val sanitized = rawCommandId
            .map { character -> if (isAllowedOperationalTokenCharacter(character)) character else '_' }
            .joinToString(separator = "")
        val combined = "plugin.lifecycle.$sanitized".take(MAX_OPERATIONAL_TOKEN_LENGTH)
        return OperationalEventId(combined.ifEmpty { "unknown" })
    }

    private fun isAllowedOperationalTokenCharacter(character: Char): Boolean =
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '.' ||
            character == '_' ||
            character == '-' ||
            character == ':' ||
            character == '/'

    private fun eventTypeValue(result: PluginLifecycleTransitionResult): String = when (result) {
        is PluginLifecycleTransitionResult.Allowed -> "dataloom.plugin.lifecycle.administration.allowed"
        is PluginLifecycleTransitionResult.Rejected -> "dataloom.plugin.lifecycle.administration.rejected"
        is PluginLifecycleTransitionResult.PermissionDenied ->
            "dataloom.plugin.lifecycle.administration.permission_denied"
        is PluginLifecycleTransitionResult.AuthorizationDenied ->
            "dataloom.plugin.lifecycle.administration.authorization_denied"
    }

    private fun classifiedAttributesFor(
        request: PluginLifecycleTransitionRequest,
        result: PluginLifecycleTransitionResult,
    ): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["request.pluginId"] = ClassifiedDataValue(request.pluginId.value, DataClassification.INTERNAL)
        attributes["request.target"] = ClassifiedDataValue(request.target.name, DataClassification.PUBLIC)
        attributes["request.principalId"] =
            ClassifiedDataValue(request.principalId.value, DataClassification.INTERNAL)

        when (result) {
            is PluginLifecycleTransitionResult.Allowed -> {
                attributes["result.from"] = ClassifiedDataValue(result.from.name, DataClassification.PUBLIC)
                attributes["result.to"] = ClassifiedDataValue(result.to.name, DataClassification.PUBLIC)
            }
            is PluginLifecycleTransitionResult.Rejected -> {
                attributes["result.from"] = ClassifiedDataValue(result.from.name, DataClassification.PUBLIC)
                attributes["result.to"] = ClassifiedDataValue(result.to.name, DataClassification.PUBLIC)
                attributes["result.reason"] = ClassifiedDataValue(result.reason, DataClassification.INTERNAL)
            }
            is PluginLifecycleTransitionResult.PermissionDenied -> {
                attributes["result.from"] = ClassifiedDataValue(result.from.name, DataClassification.PUBLIC)
                attributes["result.to"] = ClassifiedDataValue(result.to.name, DataClassification.PUBLIC)
                attributes["result.missingPermissions"] = ClassifiedDataValue(
                    result.missingPermissions.map { it.value }.sorted().joinToString(separator = ","),
                    DataClassification.INTERNAL,
                )
            }
            is PluginLifecycleTransitionResult.AuthorizationDenied -> {
                attributes["result.from"] = ClassifiedDataValue(result.from.name, DataClassification.PUBLIC)
                attributes["result.to"] = ClassifiedDataValue(result.to.name, DataClassification.PUBLIC)
                attributes["result.reasonCode"] = ClassifiedDataValue(result.reasonCode, DataClassification.INTERNAL)
            }
        }
        return attributes
    }
}
