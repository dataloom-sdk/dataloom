package io.dataloom.runtime.observation.operational

import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [ConflictResolutionOperationalEventBridge] maps a
 * [UnresolvedConflictRecord]/[ResolvedConflictDecisionRecord] to a sane
 * [io.dataloom.api.operational.OperationalEventEnvelope], that unresolved and
 * resolved outcomes never share an envelope id, and that field classification
 * never leaks a raw sensitive value.
 */
class ConflictResolutionOperationalEventBridgeTest {

    private val entity = EntityReference(EntityType("invoice-should-be-masked"), EntityId("entity-should-be-masked"))

    private fun localSummary(changeEventIdValue: String = "local-event-should-be-masked") =
        UnresolvedConflictChangeSummary(
            changeEventId = ChangeEventId(changeEventIdValue),
            operation = ChangeOperation.UPDATE,
            metadata = DataLoomMetadata.Empty,
        )

    private fun remoteSummary(changeEventIdValue: String = "remote-event-should-be-masked") =
        UnresolvedConflictChangeSummary(
            changeEventId = ChangeEventId(changeEventIdValue),
            operation = ChangeOperation.DELETE,
            metadata = DataLoomMetadata.Empty,
        )

    private fun unresolvedRecord(
        reason: UnresolvedConflictReason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
        committedAtEpochMs: Long = 5_000L,
    ): UnresolvedConflictRecord = UnresolvedConflictRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = entity,
        localChange = localSummary(),
        remoteChange = remoteSummary(),
        conflictMetadata = DataLoomMetadata.Empty,
        reason = reason,
        committedAt = DataLoomInstant(committedAtEpochMs),
    )

    private fun resolvedRecord(
        resolverIdValue: String = "resolver-should-be-masked",
        decisionKind: ResolvedConflictDecisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
        mergedChange: UnresolvedConflictChangeSummary? = null,
        failureErrorCode: String? = null,
        committedAtEpochMs: Long = 5_000L,
    ): ResolvedConflictDecisionRecord = ResolvedConflictDecisionRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = entity,
        localChange = localSummary(),
        remoteChange = remoteSummary(),
        conflictMetadata = DataLoomMetadata.Empty,
        resolverId = ConflictResolverId(resolverIdValue),
        decisionKind = decisionKind,
        decisionMetadata = DataLoomMetadata.Empty,
        mergedChange = mergedChange,
        failureErrorCode = failureErrorCode,
        committedAt = DataLoomInstant(committedAtEpochMs),
    )

    // -------------------------------------------------------------------------
    // Identity/routing -- unresolved
    // -------------------------------------------------------------------------

    @Test
    fun unresolvedToEnvelope_reusesConflictIdAsCorrelationId_neverInventsOne() {
        val conflictId = ConflictId("conflict-corr-001")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, unresolvedRecord())
        assertEquals(CorrelationId("conflict-corr-001"), envelope.correlationId)
    }

    @Test
    fun unresolvedToEnvelope_reusesCommittedAt_neverReadsAClock() {
        val conflictId = ConflictId("conflict-001")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            conflictId,
            unresolvedRecord(committedAtEpochMs = 42_000L),
        )
        assertEquals(DataLoomInstant(42_000L), envelope.occurredAt)
    }

    @Test
    fun unresolvedToEnvelope_category_isDiagnostic() {
        val conflictId = ConflictId("conflict-001")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, unresolvedRecord())
        assertEquals(OperationalEventCategory.DIAGNOSTIC, envelope.category)
    }

    @Test
    fun unresolvedToEnvelope_sanitizesDisallowedCharactersInConflictId() {
        val conflictId = ConflictId("conflict id 1!#weird")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, unresolvedRecord())
        assertTrue(envelope.id.value.none { it == ' ' || it == '!' || it == '#' })
    }

    @Test
    fun unresolvedToEnvelope_isPureAndDeterministic() {
        val conflictId = ConflictId("conflict-001")
        val record = unresolvedRecord()
        val first = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, record)
        val second = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, record)
        assertEquals(first, second)
    }

    @Test
    fun unresolvedToEnvelope_typeReflectsReason() {
        val conflictId = ConflictId("conflict-001")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            conflictId,
            unresolvedRecord(reason = UnresolvedConflictReason.RESOLVER_NOT_FOUND),
        )
        assertEquals("dataloom.conflict.resolution.resolver_not_found", envelope.type.value)
    }

    @Test
    fun unresolvedToEnvelope_tenantWorkflowTrace_areUnset() {
        val conflictId = ConflictId("conflict-001")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, unresolvedRecord())
        assertNull(envelope.tenantId)
        assertNull(envelope.workflowId)
        assertNull(envelope.traceId)
    }

    // -------------------------------------------------------------------------
    // Identity/routing -- resolved
    // -------------------------------------------------------------------------

    @Test
    fun resolvedToEnvelope_reusesConflictIdAsCorrelationId_neverInventsOne() {
        val conflictId = ConflictId("conflict-corr-002")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, resolvedRecord())
        assertEquals(CorrelationId("conflict-corr-002"), envelope.correlationId)
    }

    @Test
    fun resolvedToEnvelope_typeReflectsDecisionKind() {
        val conflictId = ConflictId("conflict-002")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            conflictId,
            resolvedRecord(decisionKind = ResolvedConflictDecisionKind.MERGE, mergedChange = localSummary()),
        )
        assertEquals("dataloom.conflict.resolution.resolved.merge", envelope.type.value)
    }

    @Test
    fun unresolvedAndResolvedEnvelopeIds_forTheSameConflictId_neverCollide() {
        val conflictId = ConflictId("shared-conflict-id")
        val unresolvedEnvelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, unresolvedRecord())
        val resolvedEnvelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, resolvedRecord())
        assertTrue(unresolvedEnvelope.id != resolvedEnvelope.id)
        // Both still reuse the same underlying conflict identity for correlation.
        assertEquals(unresolvedEnvelope.correlationId, resolvedEnvelope.correlationId)
    }

    // -------------------------------------------------------------------------
    // Classification
    // -------------------------------------------------------------------------

    @Test
    fun unresolvedToEnvelope_masksEntityAndChangeEventIdentifiers_neverKeepsRawValue() {
        val conflictId = ConflictId("conflict-001")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, unresolvedRecord())
        assertNotEqual("invoice-should-be-masked", envelope.attributes["conflict.entityType"])
        assertNotEqual("entity-should-be-masked", envelope.attributes["conflict.entityId"])
        assertNotEqual("local-event-should-be-masked", envelope.attributes["conflict.localChange.changeEventId"])
        assertNotEqual("remote-event-should-be-masked", envelope.attributes["conflict.remoteChange.changeEventId"])
    }

    @Test
    fun unresolvedToEnvelope_keepsEnumFields() {
        val conflictId = ConflictId("conflict-001")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, unresolvedRecord())
        assertEquals("CONCURRENT_CHANGE", envelope.attributes["conflict.type"])
        assertEquals("UPDATE", envelope.attributes["conflict.localChange.operation"])
        assertEquals("DELETE", envelope.attributes["conflict.remoteChange.operation"])
        assertEquals("RESOLVER_NOT_CONFIGURED", envelope.attributes["conflict.reason"])
    }

    @Test
    fun resolvedToEnvelope_masksResolverId_neverKeepsRawValue() {
        val conflictId = ConflictId("conflict-002")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            conflictId,
            resolvedRecord(resolverIdValue = "resolver-should-be-masked"),
        )
        assertNotEqual("resolver-should-be-masked", envelope.attributes["decision.resolverId"])
    }

    @Test
    fun resolvedToEnvelope_keepsDecisionKind() {
        val conflictId = ConflictId("conflict-002")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            conflictId,
            resolvedRecord(decisionKind = ResolvedConflictDecisionKind.USE_LOCAL),
        )
        assertEquals("USE_LOCAL", envelope.attributes["decision.kind"])
    }

    @Test
    fun resolvedToEnvelope_omitsMergedChangeAndFailureCode_whenAbsent() {
        val conflictId = ConflictId("conflict-002")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            conflictId,
            resolvedRecord(decisionKind = ResolvedConflictDecisionKind.USE_REMOTE),
        )
        assertNull(envelope.attributes["decision.mergedChange.changeEventId"])
        assertNull(envelope.attributes["decision.failureErrorCode"])
    }

    @Test
    fun resolvedToEnvelope_keepsFailureErrorCode_asPlainBoundedCode() {
        val conflictId = ConflictId("conflict-002")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            conflictId,
            resolvedRecord(decisionKind = ResolvedConflictDecisionKind.FAIL, failureErrorCode = "RESOLUTION_FAILED"),
        )
        assertEquals("RESOLUTION_FAILED", envelope.attributes["decision.failureErrorCode"])
    }

    @Test
    fun resolvedToEnvelope_masksMergedChangeEventId_whenPresent() {
        val conflictId = ConflictId("conflict-002")
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            conflictId,
            resolvedRecord(
                decisionKind = ResolvedConflictDecisionKind.MERGE,
                mergedChange = UnresolvedConflictChangeSummary(
                    changeEventId = ChangeEventId("merged-event-should-be-masked"),
                    operation = ChangeOperation.UPDATE,
                    metadata = DataLoomMetadata.Empty,
                ),
            ),
        )
        assertNotEqual("merged-event-should-be-masked", envelope.attributes["decision.mergedChange.changeEventId"])
        assertEquals("UPDATE", envelope.attributes["decision.mergedChange.operation"])
    }

    private fun assertNotEqual(rawValue: String, redactedValue: String?) {
        assertTrue(redactedValue != null && redactedValue != rawValue, "Expected redaction of '$rawValue'.")
    }
}
