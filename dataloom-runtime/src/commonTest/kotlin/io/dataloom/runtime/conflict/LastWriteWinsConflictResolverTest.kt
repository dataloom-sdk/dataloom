package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies [LastWriteWinsConflictResolver]'s decision correctness across
 * every [ConflictType] shape, its declared identity, and its deterministic,
 * side-effect-free behavior.
 *
 * ## Why every assertion expects [ConflictResolutionDecision.UseRemote]
 *
 * As documented on [LastWriteWinsConflictResolver] itself, no reliable
 * recency evidence exists on [SynchronizationConflict] today, so this
 * resolver applies a deterministic remote-wins tiebreak regardless of
 * conflict shape -- this test proves that placeholder behavior is genuinely
 * unconditional, not proof of true timestamp-based ordering.
 */
class LastWriteWinsConflictResolverTest {

    private val resolver = LastWriteWinsConflictResolver()

    private val syncRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(executionId = ExecutionId("execution-1"), correlationId = CorrelationId("corr-1")),
    )

    @Test
    fun exposesTheDocumentedDefaultId() {
        assertEquals(ConflictResolverId("dataloom.builtin.last-write-wins"), resolver.id)
        assertEquals(LastWriteWinsConflictResolver.DEFAULT_ID, resolver.id)
    }

    @Test
    fun aCustomIdCanBeSupplied() {
        val customId = ConflictResolverId("custom-lww")
        val custom = LastWriteWinsConflictResolver(id = customId)
        assertEquals(customId, custom.id)
    }

    @Test
    fun everyConflictTypeResolvesToUseRemote() {
        for (type in ConflictType.entries) {
            val decision = resolver.resolve(request(conflict(type)))
            assertIs<ConflictResolutionDecision.UseRemote>(decision)
        }
    }

    @Test
    fun theDecisionCarriesEmptyMetadataAndDoesNotInspectTheRequest() {
        val metadata = DataLoomMetadata.of(mapOf("hint" to "irrelevant"))
        val conflictWithMetadata = conflict(ConflictType.CONCURRENT_CHANGE, metadata = metadata)

        val decision = assertIs<ConflictResolutionDecision.UseRemote>(resolver.resolve(request(conflictWithMetadata)))

        assertEquals(DataLoomMetadata.Empty, decision.metadata)
    }

    @Test
    fun resolutionIsDeterministicAcrossRepeatedCalls() {
        val theConflict = conflict(ConflictType.UPDATE_DELETE)
        val first = resolver.resolve(request(theConflict))
        val second = resolver.resolve(request(theConflict))
        assertEquals(first, second)
    }

    private fun request(conflict: SynchronizationConflict): ConflictResolutionRequest =
        ConflictResolutionRequest(synchronizationRequest = syncRequest, conflict = conflict)

    private fun conflict(
        type: ConflictType,
        metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ): SynchronizationConflict {
        val entity = EntityReference(EntityType("note"), EntityId("note-1"))
        val localChange = ChangeEvent(ChangeEventId("local-1"), entity, ChangeOperation.UPDATE)
        val remoteChange = ChangeEvent(ChangeEventId("remote-1"), entity, ChangeOperation.UPDATE)
        return SynchronizationConflict(
            id = ConflictId("conflict-${type.name}"),
            type = type,
            entity = entity,
            localChange = localChange,
            remoteChange = remoteChange,
            metadata = metadata,
        )
    }
}
