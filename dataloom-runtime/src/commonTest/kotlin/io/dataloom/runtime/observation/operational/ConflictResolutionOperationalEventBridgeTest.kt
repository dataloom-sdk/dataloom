package io.dataloom.runtime.observation.operational

import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationId
import io.dataloom.api.conflict.ConflictAdministrationCommandId
import io.dataloom.api.conflict.ConflictAdministrationCommandState
import io.dataloom.api.conflict.ConflictAdministrationCommandStatus
import io.dataloom.api.conflict.ConflictAdministrationFailureSnapshot
import io.dataloom.api.conflict.ConflictAdministrationPrincipalId
import io.dataloom.api.conflict.ConflictAdministrationReason
import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.api.conflict.ConflictAdministrationStateRecord
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.conflict.ConflictAdministrationResult
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

    // -------------------------------------------------------------------------
    // Administration commands
    // -------------------------------------------------------------------------

    private fun administrationRequest(
        commandIdValue: String = "admin-cmd-001",
        conflictIdValue: String = "conflict-admin-001",
        principalIdValue: String = "principal-should-be-masked",
        decision: ConflictResolutionDecision = ConflictResolutionDecision.UseLocal(),
        requestedAtEpochMs: Long = 700L,
    ): ConflictAdministrationRequest = ConflictAdministrationRequest(
        commandId = ConflictAdministrationCommandId(commandIdValue),
        conflictId = ConflictId(conflictIdValue),
        principalId = ConflictAdministrationPrincipalId(principalIdValue),
        requestedAt = DataLoomInstant(requestedAtEpochMs),
        decision = decision,
        reason = ConflictAdministrationReason("investigated and decided-should-not-appear"),
    )

    private fun administrationState(
        request: ConflictAdministrationRequest,
        status: ConflictAdministrationCommandStatus,
        authorizationId: ConflictAdministrationAuthorizationId? = ConflictAdministrationAuthorizationId("auth-001"),
        updatedAtEpochMs: Long = 1_200L,
        rejectionReasonCode: String? = null,
        executionFailure: ConflictAdministrationFailureSnapshot? = null,
    ): ConflictAdministrationCommandState = ConflictAdministrationCommandState(
        request = request,
        status = status,
        authorizationId = authorizationId,
        updatedAt = DataLoomInstant(updatedAtEpochMs),
        rejectionReasonCode = rejectionReasonCode,
        executionFailure = executionFailure,
    )

    private fun administrationRecord(state: ConflictAdministrationCommandState, version: Long = 0L) =
        ConflictAdministrationStateRecord(state = state, version = version)

    private data class FakeAdministrationError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-CONFLICT-ADMIN"),
        override val category: ErrorCategory = ErrorCategory.STATE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-sensitive-message-should-be-removed",
        override val cause: Throwable? = null,
    ) : DataLoomError

    @Test
    fun administrationToEnvelope_reusesCommandIdAsCorrelationId_neverInventsOne() {
        val request = administrationRequest(commandIdValue = "admin-corr-001")
        val state = administrationState(request, ConflictAdministrationCommandStatus.SUCCEEDED)
        val result = ConflictAdministrationResult.Succeeded(administrationRecord(state))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals(CorrelationId("admin-corr-001"), envelope.correlationId)
    }

    @Test
    fun administrationToEnvelope_category_isAudit() {
        val request = administrationRequest()
        val state = administrationState(request, ConflictAdministrationCommandStatus.SUCCEEDED)
        val result = ConflictAdministrationResult.Succeeded(administrationRecord(state))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals(OperationalEventCategory.AUDIT, envelope.category)
    }

    @Test
    fun administrationToEnvelope_succeeded_reusesRecordUpdatedAt_neverReadsAClock() {
        val request = administrationRequest()
        val state = administrationState(
            request,
            ConflictAdministrationCommandStatus.SUCCEEDED,
            updatedAtEpochMs = 55_000L,
        )
        val result = ConflictAdministrationResult.Succeeded(administrationRecord(state))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals(DataLoomInstant(55_000L), envelope.occurredAt)
        assertEquals("dataloom.conflict.administration.succeeded", envelope.type.value)
    }

    @Test
    fun administrationToEnvelope_persistenceFailure_reusesRequestedAt_hasNoRecord() {
        val request = administrationRequest(requestedAtEpochMs = 321L)
        val result = ConflictAdministrationResult.PersistenceFailure(FakeAdministrationError())
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals(DataLoomInstant(321L), envelope.occurredAt)
        assertEquals("dataloom.conflict.administration.persistence_failure", envelope.type.value)
    }

    @Test
    fun administrationToEnvelope_clockRegression_reusesObservedAt() {
        val request = administrationRequest()
        val result = ConflictAdministrationResult.ClockRegression(
            observedAt = DataLoomInstant(10L),
            persistedAt = DataLoomInstant(20L),
        )
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals(DataLoomInstant(10L), envelope.occurredAt)
        assertEquals("20", envelope.attributes["result.persistedAtEpochMillis"])
    }

    @Test
    fun administrationToEnvelope_sanitizesDisallowedCharactersInCommandId() {
        val request = administrationRequest(commandIdValue = "admin cmd 1!#weird")
        val state = administrationState(request, ConflictAdministrationCommandStatus.SUCCEEDED)
        val result = ConflictAdministrationResult.Succeeded(administrationRecord(state))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertTrue(envelope.id.value.none { it == ' ' || it == '!' || it == '#' })
    }

    @Test
    fun administrationToEnvelope_isPureAndDeterministic() {
        val request = administrationRequest()
        val state = administrationState(request, ConflictAdministrationCommandStatus.SUCCEEDED)
        val result = ConflictAdministrationResult.Succeeded(administrationRecord(state))
        val first = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        val second = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals(first, second)
    }

    @Test
    fun administrationToEnvelope_neverCollidesWithUnresolvedOrResolvedEnvelopeIds() {
        // A command whose commandId happens to equal an unrelated conflictId's raw value.
        val sharedRawValue = "shared-raw-identifier"
        val request = administrationRequest(commandIdValue = sharedRawValue)
        val state = administrationState(request, ConflictAdministrationCommandStatus.SUCCEEDED)
        val adminEnvelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            request,
            ConflictAdministrationResult.Succeeded(administrationRecord(state)),
        )
        val conflictId = ConflictId(sharedRawValue)
        val unresolvedEnvelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, unresolvedRecord())
        val resolvedEnvelope = ConflictResolutionOperationalEventBridge.toEnvelope(conflictId, resolvedRecord())
        assertTrue(adminEnvelope.id != unresolvedEnvelope.id)
        assertTrue(adminEnvelope.id != resolvedEnvelope.id)
    }

    @Test
    fun administrationToEnvelope_excludesCallerSuppliedReason() {
        val request = administrationRequest()
        val state = administrationState(request, ConflictAdministrationCommandStatus.SUCCEEDED)
        val result = ConflictAdministrationResult.Succeeded(administrationRecord(state))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertTrue(envelope.attributes.entries.values.none { it == "investigated and decided-should-not-appear" })
    }

    @Test
    fun administrationToEnvelope_masksPrincipalIdAndConflictId_neverKeepsRawValue() {
        val request = administrationRequest(
            principalIdValue = "principal-should-be-masked",
            conflictIdValue = "conflict-should-be-masked",
        )
        val state = administrationState(request, ConflictAdministrationCommandStatus.SUCCEEDED)
        val result = ConflictAdministrationResult.Succeeded(administrationRecord(state))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertNotEqual("principal-should-be-masked", envelope.attributes["request.principalId"])
        assertNotEqual("conflict-should-be-masked", envelope.attributes["request.conflictId"])
    }

    @Test
    fun administrationToEnvelope_keepsDecisionKind() {
        val request = administrationRequest(decision = ConflictResolutionDecision.UseRemote())
        val state = administrationState(request, ConflictAdministrationCommandStatus.SUCCEEDED)
        val result = ConflictAdministrationResult.Succeeded(administrationRecord(state))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals("USE_REMOTE", envelope.attributes["request.decisionKind"])
    }

    @Test
    fun administrationToEnvelope_authorizationDenied_typeAndMaskedReasonCode() {
        val request = administrationRequest()
        val state = administrationState(
            request,
            ConflictAdministrationCommandStatus.AUTHORIZATION_DENIED,
            authorizationId = null,
            rejectionReasonCode = "NOT_AUTHORIZED",
        )
        val result = ConflictAdministrationResult.AuthorizationDenied(administrationRecord(state))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals("dataloom.conflict.administration.authorization_denied", envelope.type.value)
        assertNotEqual("NOT_AUTHORIZED", envelope.attributes["result.rejectionReasonCode"])
    }

    @Test
    fun administrationToEnvelope_executionFailed_keepsClosedFailureVocabulary() {
        val request = administrationRequest()
        val state = administrationState(
            request,
            ConflictAdministrationCommandStatus.EXECUTION_FAILED,
            executionFailure = ConflictAdministrationFailureSnapshot(
                code = ErrorCode("DL-CONFLICT-ADMINISTRATION-NON-CONVERGENT"),
                category = ErrorCategory.CONFLICT,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.NON_RECOVERABLE,
            ),
        )
        val result = ConflictAdministrationResult.ExecutionFailed(administrationRecord(state))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals("dataloom.conflict.administration.execution_failed", envelope.type.value)
        assertEquals("DL-CONFLICT-ADMINISTRATION-NON-CONVERGENT", envelope.attributes["result.executionFailure.code"])
        assertEquals("CONFLICT", envelope.attributes["result.executionFailure.category"])
    }

    @Test
    fun administrationToEnvelope_persistenceFailure_removesRawErrorMessage() {
        val request = administrationRequest()
        val result = ConflictAdministrationResult.PersistenceFailure(FakeAdministrationError())
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        // CONFIDENTIAL classification is removed outright by the default redaction
        // policy, not masked to a different string -- see putErrorAttributes' own doc.
        assertNull(envelope.attributes["result.error.message"])
        assertEquals("DL-FAKE-CONFLICT-ADMIN", envelope.attributes["result.error.code"])
    }

    @Test
    fun administrationToEnvelope_commandConflict_keepsExistingStatus() {
        val request = administrationRequest()
        val existingState = administrationState(request, ConflictAdministrationCommandStatus.SUCCEEDED)
        val result = ConflictAdministrationResult.CommandConflict(administrationRecord(existingState))
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
        assertEquals("dataloom.conflict.administration.command_conflict", envelope.type.value)
        assertEquals("SUCCEEDED", envelope.attributes["result.existingStatus"])
    }

    @Test
    fun administrationToEnvelope_contentionLimitReached_typeAndRequestedAt() {
        val request = administrationRequest(requestedAtEpochMs = 42L)
        val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(
            request,
            ConflictAdministrationResult.ContentionLimitReached,
        )
        assertEquals("dataloom.conflict.administration.contention_limit_reached", envelope.type.value)
        assertEquals(DataLoomInstant(42L), envelope.occurredAt)
    }
}
