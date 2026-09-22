package io.dataloom.runtime.observation.operational

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.policy.PolicyCheckOutcome
import io.dataloom.api.policy.PolicyDecision
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.time.DataLoomInstant

/**
 * Pure mapping from one evaluated [PolicyDecision] to an
 * [OperationalEventEnvelope], for
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] -- the same
 * shape and conventions [StrategyDecisionOperationalEventBridge] and
 * [QueueLifecycleOperationalEventBridge] already established, applied to the
 * one real producer of policy decisions today: strategy-admission policy
 * evaluation in
 * `StrategySynchronizationExecutionCoordinator`.
 *
 * ## Why only policy decisions, and why only this producer
 *
 * `DurablePolicyDecisionLog` is the durable per-execution record of a
 * decision; this bridge additionally feeds the one ordered, cross-subsystem
 * outbox stream a caller need not already know a `PolicyDecisionScope` to
 * read. It bridges what really happens -- an admission evaluation returning a
 * decision -- and invents no event for policy *configuration* changes:
 * `DurableConfigurationHistory` has no runtime caller that records a version
 * today, so there is nothing to bridge (see `docs/api/operational-envelope-redaction.md`).
 *
 * ## Envelope shape
 * - `id`: `policy.decision.<policySetId>.<executionId>` with characters
 *   outside `[A-Za-z0-9._:/-]` replaced by `_` and the whole truncated to 128
 *   characters -- one envelope per decision, matching
 *   `PolicyDecisionScope`'s own identity, so re-reporting the same decision
 *   is an idempotent `AlreadyAppended`.
 * - `type`: `dataloom.policy.decision.allowed`, `.denied`,
 *   `.user_action_required` or `.deferred`.
 * - `category`: [OperationalEventCategory.AUDIT]; `source`:
 *   `dataloom.runtime.policy.decision`.
 * - `occurredAt`: the caller-supplied evaluation time (this bridge reads no
 *   clock); `correlationId`/`traceId`/`tenantId`: the execution context's;
 *   `workflowId`: the request's.
 *
 * ## Attributes
 * Every field goes through [ClassifiedData]/[StrictDataLoomRedactor]. Closed
 * vocabularies (outcome kind, evidence outcome kinds, counts, defer delay)
 * are `PUBLIC`; policy-set and check ids are `INTERNAL`. The free-text
 * `justification` -- caller-supplied by a `PolicyCheck` implementation, so
 * never trusted to be free of sensitive content -- is classified
 * `CONFIDENTIAL`, which the default policy removes outright. Check metadata
 * ([PolicyCheckOutcome.metadata]) is never read.
 */
public object PolicyDecisionOperationalEventBridge {

    private const val SOURCE_VALUE: String = "dataloom.runtime.policy.decision"
    private const val PAYLOAD_TYPE_VALUE: String = "dataloom.policy.decision"
    private const val PAYLOAD_ENCODING_VALUE: String = "none"
    private const val MAX_OPERATIONAL_TOKEN_LENGTH: Int = 128
    private const val MAX_JUSTIFICATION_LENGTH: Int = 4_096
    private const val MAX_EVIDENCE_ENTRIES: Int = 64
    private const val ID_SEPARATOR: String = "."
    private const val EVIDENCE_SEPARATOR: String = "|"

    private val SOURCE: OperationalEventSource = OperationalEventSource(SOURCE_VALUE)
    private val ENVELOPE_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_TYPE: OperationalPayloadType = OperationalPayloadType(PAYLOAD_TYPE_VALUE)
    private val PAYLOAD_ENCODING: OperationalPayloadEncoding = OperationalPayloadEncoding(PAYLOAD_ENCODING_VALUE)
    private val redactor: StrictDataLoomRedactor = StrictDataLoomRedactor()

    /**
     * Maps [decision], evaluated for the execution described by [context] (and
     * [workflowId], when the request carries one) at [evaluatedAt], to its
     * envelope. Pure: no clock, no I/O, no identifier generation.
     */
    public fun toEnvelope(
        context: ExecutionContext,
        workflowId: WorkflowId?,
        decision: PolicyDecision,
        evaluatedAt: DataLoomInstant,
    ): OperationalEventEnvelope {
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(decision))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId(decision, context),
            type = OperationalEventType(eventTypeValue(decision.outcome)),
            source = SOURCE,
            category = OperationalEventCategory.AUDIT,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = evaluatedAt,
            correlationId = context.correlationId,
            traceId = context.traceId,
            tenantId = context.tenantId,
            workflowId = workflowId,
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

    private fun operationalEventId(decision: PolicyDecision, context: ExecutionContext): OperationalEventId {
        val combined = "policy.decision$ID_SEPARATOR${sanitize(decision.policySetId.value)}" +
            "$ID_SEPARATOR${sanitize(context.executionId.value)}"
        return OperationalEventId(combined.take(MAX_OPERATIONAL_TOKEN_LENGTH))
    }

    private fun sanitize(value: String): String =
        value
            .map { character -> if (isAllowedOperationalTokenCharacter(character)) character else '_' }
            .joinToString(separator = "")

    private fun isAllowedOperationalTokenCharacter(character: Char): Boolean =
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '.' ||
            character == '_' ||
            character == '-' ||
            character == ':' ||
            character == '/'

    private fun eventTypeValue(outcome: PolicyCheckOutcome): String = when (outcome) {
        is PolicyCheckOutcome.Allow -> "dataloom.policy.decision.allowed"
        is PolicyCheckOutcome.Deny -> "dataloom.policy.decision.denied"
        is PolicyCheckOutcome.RequireUserAction -> "dataloom.policy.decision.user_action_required"
        is PolicyCheckOutcome.Defer -> "dataloom.policy.decision.deferred"
    }

    private fun outcomeKindName(outcome: PolicyCheckOutcome): String = when (outcome) {
        is PolicyCheckOutcome.Allow -> "ALLOW"
        is PolicyCheckOutcome.Deny -> "DENY"
        is PolicyCheckOutcome.RequireUserAction -> "REQUIRE_USER_ACTION"
        is PolicyCheckOutcome.Defer -> "DEFER"
    }

    private fun classifiedAttributesFor(decision: PolicyDecision): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["policy.setId"] = ClassifiedDataValue(decision.policySetId.value, DataClassification.INTERNAL)
        attributes["policy.outcome"] =
            ClassifiedDataValue(outcomeKindName(decision.outcome), DataClassification.PUBLIC)
        (decision.outcome as? PolicyCheckOutcome.Defer)?.let { defer ->
            attributes["policy.deferDelayMilliseconds"] =
                ClassifiedDataValue(defer.delay.milliseconds.toString(), DataClassification.PUBLIC)
        }
        decision.winningCheckId?.let { checkId ->
            attributes["policy.winningCheckId"] = ClassifiedDataValue(checkId.value, DataClassification.INTERNAL)
        }
        attributes["policy.justification"] = ClassifiedDataValue(
            decision.outcome.justification.take(MAX_JUSTIFICATION_LENGTH),
            DataClassification.CONFIDENTIAL,
        )
        attributes["policy.evidenceCount"] =
            ClassifiedDataValue(decision.evidence.size.toString(), DataClassification.PUBLIC)
        attributes["policy.evidence"] = ClassifiedDataValue(
            decision.evidence
                .take(MAX_EVIDENCE_ENTRIES)
                .joinToString(separator = EVIDENCE_SEPARATOR) { "${it.checkId.value}=${outcomeKindName(it.outcome)}" }
                .take(MAX_JUSTIFICATION_LENGTH),
            DataClassification.INTERNAL,
        )
        return attributes
    }
}
