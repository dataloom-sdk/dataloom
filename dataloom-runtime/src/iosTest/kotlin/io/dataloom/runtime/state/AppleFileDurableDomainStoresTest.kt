package io.dataloom.runtime.state

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationId
import io.dataloom.api.conflict.ConflictAdministrationCommandId
import io.dataloom.api.conflict.ConflictAdministrationPrincipalId
import io.dataloom.api.conflict.ConflictAdministrationReason
import io.dataloom.api.conflict.ConflictQuarantineObservation
import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineRelease
import io.dataloom.api.conflict.ConflictQuarantineRecord
import io.dataloom.api.conflict.ConflictQuarantineReleaseOutcome
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.ConflictQuarantineStatus
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableConflictQuarantineLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.policy.DurablePolicyDecisionCommitOutcome
import io.dataloom.api.policy.DurablePolicyDecisionLog
import io.dataloom.api.policy.PolicyCheckEvidence
import io.dataloom.api.policy.PolicyCheckOutcome
import io.dataloom.api.policy.PolicyDecision
import io.dataloom.api.policy.PolicyDecisionRecord
import io.dataloom.api.policy.PolicyDecisionScope
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetChunkPlan
import io.dataloom.assets.AssetTransferDirection
import io.dataloom.assets.AssetTransferEvent
import io.dataloom.assets.AssetTransferSession
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetTransferTransition
import io.dataloom.assets.DurableAssetTransferSessionLoadOutcome
import io.dataloom.assets.DurableAssetTransferSessionSaveOutcome
import io.dataloom.assets.DurableAssetTransferSessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

/**
 * iOS proofs that the domains wired through [AppleFileDurableDomainStores]
 * behave on a real [AppleFileDurableStateStore] file the way their
 * Room-backed counterparts' domain tests assert (record, restart recovery
 * through a fresh store instance, idempotent duplicate, and a concurrent
 * duplicate race converging on one write plus one absorbed duplicate).
 *
 * These run only in the macOS `apple-validation` CI job; they were
 * cross-compiled, not executed, on the Windows development host.
 */
class AppleFileDurableDomainStoresTest {

    @Test
    fun domainFileNamesAreDistinctExplicitAndNotTheSharedDefault() {
        val names = listOf(
            AppleFileDurableDomainStores.POLICY_DECISION_FILE_NAME,
            AppleFileDurableDomainStores.UNRESOLVED_CONFLICT_FILE_NAME,
            AppleFileDurableDomainStores.RESOLVED_CONFLICT_DECISION_FILE_NAME,
            AppleFileDurableDomainStores.CONFLICT_QUARANTINE_FILE_NAME,
            AppleFileDurableDomainStores.ASSET_TRANSFER_SESSION_FILE_NAME,
        )
        assertEquals(names.size, names.toSet().size)
        assertEquals(false, AppleFileDurableStateStore.DEFAULT_FILE_NAME in names)
        names.forEach { assertEquals(true, it.startsWith("dataloom-") && it.endsWith("-state-v1.tsv"), it) }
    }

    // -------------------------------------------------------------------------
    // DurablePolicyDecisionLog
    // -------------------------------------------------------------------------

    private val policyScope = PolicyDecisionScope(PolicySetId("retry-policy"), ExecutionId("execution-1"))
    private val policyDecision = PolicyDecision(
        policySetId = policyScope.policySetId,
        outcome = PolicyCheckOutcome.Deny("quota exceeded"),
        winningCheckId = PolicyCheckId("quota-check"),
        evidence = listOf(
            PolicyCheckEvidence(PolicyCheckId("auth-check"), PolicyCheckOutcome.Allow("authorized")),
            PolicyCheckEvidence(PolicyCheckId("quota-check"), PolicyCheckOutcome.Deny("quota exceeded")),
        ),
    )

    private val policyProof = AppleFileDurableDomainProof<PolicyDecisionScope, PolicyDecisionRecord>(
        fileName = AppleFileDurableDomainStores.POLICY_DECISION_FILE_NAME,
        scope = policyScope,
        openStore = AppleFileDurableDomainStores::policyDecisionStore,
        attempt = { store ->
            when (DurablePolicyDecisionLog(store).commit(policyScope, policyDecision, DataLoomInstant(10_000L))) {
                is DurablePolicyDecisionCommitOutcome.Committed -> ProofAttempt.WROTE
                is DurablePolicyDecisionCommitOutcome.AlreadyCommitted -> ProofAttempt.DUPLICATE
                else -> ProofAttempt.UNEXPECTED
            }
        },
        assertPersisted = { record ->
            assertEquals(policyDecision, record.decision)
            assertEquals(DataLoomInstant(10_000L), record.committedAt)
        },
    )

    @Test
    fun policyDecisionRecordsAndRecoversFromAFreshStoreInstance() =
        policyProof.recordThenRecoverFromAFreshStoreInstance()

    @Test
    fun policyDecisionIdenticalDuplicateIsAbsorbed() =
        policyProof.identicalDuplicateIsAbsorbedWithoutChangingTheRecord()

    @Test
    fun policyDecisionConcurrentDuplicateConverges() =
        policyProof.concurrentDuplicateConvergesOnOneWriteAndOneAbsorbedDuplicate()

    // -------------------------------------------------------------------------
    // DurableUnresolvedConflictLog
    // -------------------------------------------------------------------------

    private val unresolvedConflictId = ConflictId("apple-domain-unresolved-1")
    private val unresolvedRecord = UnresolvedConflictRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("note"), EntityId("note-apple-domain-1")),
        localChange = UnresolvedConflictChangeSummary(
            ChangeEventId("local-apple-domain-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        remoteChange = UnresolvedConflictChangeSummary(
            ChangeEventId("remote-apple-domain-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        conflictMetadata = DataLoomMetadata.Empty,
        reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
        committedAt = DataLoomInstant(10_000L),
    )

    private val unresolvedProof = AppleFileDurableDomainProof<ConflictId, UnresolvedConflictRecord>(
        fileName = AppleFileDurableDomainStores.UNRESOLVED_CONFLICT_FILE_NAME,
        scope = unresolvedConflictId,
        openStore = AppleFileDurableDomainStores::unresolvedConflictStore,
        attempt = { store ->
            when (DurableUnresolvedConflictLog(store).record(unresolvedConflictId, unresolvedRecord)) {
                is DurableUnresolvedConflictRecordOutcome.Recorded -> ProofAttempt.WROTE
                is DurableUnresolvedConflictRecordOutcome.AlreadyRecorded -> ProofAttempt.DUPLICATE
                else -> ProofAttempt.UNEXPECTED
            }
        },
        assertPersisted = { assertEquals(unresolvedRecord, it) },
    )

    @Test
    fun unresolvedConflictRecordsAndRecoversFromAFreshStoreInstance() =
        unresolvedProof.recordThenRecoverFromAFreshStoreInstance()

    @Test
    fun unresolvedConflictIdenticalDuplicateIsAbsorbed() =
        unresolvedProof.identicalDuplicateIsAbsorbedWithoutChangingTheRecord()

    @Test
    fun unresolvedConflictConcurrentDuplicateConverges() =
        unresolvedProof.concurrentDuplicateConvergesOnOneWriteAndOneAbsorbedDuplicate()

    // -------------------------------------------------------------------------
    // DurableResolvedConflictDecisionLog
    // -------------------------------------------------------------------------

    private val resolvedConflictId = ConflictId("apple-domain-resolved-1")
    private val resolvedRecord = ResolvedConflictDecisionRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("note"), EntityId("note-apple-domain-resolved-1")),
        localChange = UnresolvedConflictChangeSummary(
            ChangeEventId("local-apple-domain-resolved-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        remoteChange = UnresolvedConflictChangeSummary(
            ChangeEventId("remote-apple-domain-resolved-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        conflictMetadata = DataLoomMetadata.Empty,
        resolverId = ConflictResolverId("dataloom.builtin.server-wins"),
        decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
        decisionMetadata = DataLoomMetadata.Empty,
        committedAt = DataLoomInstant(10_000L),
    )

    private val resolvedProof = AppleFileDurableDomainProof<ConflictId, ResolvedConflictDecisionRecord>(
        fileName = AppleFileDurableDomainStores.RESOLVED_CONFLICT_DECISION_FILE_NAME,
        scope = resolvedConflictId,
        openStore = AppleFileDurableDomainStores::resolvedConflictDecisionStore,
        attempt = { store ->
            when (DurableResolvedConflictDecisionLog(store).record(resolvedConflictId, resolvedRecord)) {
                is DurableResolvedConflictDecisionRecordOutcome.Recorded -> ProofAttempt.WROTE
                is DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded -> ProofAttempt.DUPLICATE
                else -> ProofAttempt.UNEXPECTED
            }
        },
        assertPersisted = { assertEquals(resolvedRecord, it) },
    )

    @Test
    fun resolvedConflictDecisionRecordsAndRecoversFromAFreshStoreInstance() =
        resolvedProof.recordThenRecoverFromAFreshStoreInstance()

    @Test
    fun resolvedConflictDecisionIdenticalDuplicateIsAbsorbed() =
        resolvedProof.identicalDuplicateIsAbsorbedWithoutChangingTheRecord()

    @Test
    fun resolvedConflictDecisionConcurrentDuplicateConverges() =
        resolvedProof.concurrentDuplicateConvergesOnOneWriteAndOneAbsorbedDuplicate()

    // -------------------------------------------------------------------------
    // DurableConflictQuarantineLog
    //
    // recordOccurrence counts rather than deduplicates, so the idempotent
    // write of this domain is the command-id-idempotent release: the entity is
    // first brought to the quarantined state (threshold 2), then the same
    // release command is attempted once, again, and from two racing writers.
    // -------------------------------------------------------------------------

    private val quarantineScope = ConflictQuarantineScope(EntityType("invoice"), EntityId("inv-apple-1"))
    private val quarantinePolicy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
    private val quarantineRelease = ConflictQuarantineRelease(
        commandId = ConflictAdministrationCommandId("cmd-apple-1"),
        principalId = ConflictAdministrationPrincipalId("operator"),
        authorizationId = ConflictAdministrationAuthorizationId("auth-cmd-apple-1"),
        reason = ConflictAdministrationReason("verified upstream fix"),
        releasedAt = DataLoomInstant(9_000L),
    )

    private val quarantineProof = AppleFileDurableDomainProof<ConflictQuarantineScope, ConflictQuarantineRecord>(
        fileName = AppleFileDurableDomainStores.CONFLICT_QUARANTINE_FILE_NAME,
        scope = quarantineScope,
        openStore = AppleFileDurableDomainStores::conflictQuarantineStore,
        prepare = { store ->
            val log = DurableConflictQuarantineLog(store)
            val resolver = ConflictResolverId("dataloom.builtin.server-wins")
            log.recordOccurrence(quarantineScope, ConflictId("c-1"), resolver, DataLoomInstant(1_000L), quarantinePolicy)
            log.recordOccurrence(quarantineScope, ConflictId("c-2"), resolver, DataLoomInstant(2_000L), quarantinePolicy)
        },
        attempt = { store ->
            when (DurableConflictQuarantineLog(store).release(quarantineScope, quarantineRelease)) {
                is ConflictQuarantineReleaseOutcome.Released -> ProofAttempt.WROTE
                is ConflictQuarantineReleaseOutcome.AlreadyReleased -> ProofAttempt.DUPLICATE
                else -> ProofAttempt.UNEXPECTED
            }
        },
        // Two counted occurrences (versions 0 and 1), then the release (version 2).
        versionAfterWrite = 2L,
        assertPersisted = { record ->
            assertEquals(ConflictQuarantineStatus.COUNTING, record.status)
            assertEquals(0, record.occurrenceCount)
            assertEquals(1, record.releaseCount)
            assertEquals(quarantineRelease, record.lastRelease)
        },
    )

    @Test
    fun conflictQuarantineReleaseRecordsAndRecoversFromAFreshStoreInstance() =
        quarantineProof.recordThenRecoverFromAFreshStoreInstance()

    @Test
    fun conflictQuarantineIdenticalReleaseCommandIsAbsorbed() =
        quarantineProof.identicalDuplicateIsAbsorbedWithoutChangingTheRecord()

    @Test
    fun conflictQuarantineConcurrentReleaseConverges() =
        quarantineProof.concurrentDuplicateConvergesOnOneWriteAndOneAbsorbedDuplicate()

    @Test
    fun conflictQuarantineOccurrencesSurviveARestartAndQuarantineAtTheThreshold(): Unit = runBlocking {
        val directory = AppleFileDurableDomainProof.uniqueDirectory()
        val resolver = ConflictResolverId("dataloom.builtin.server-wins")
        DurableConflictQuarantineLog(AppleFileDurableDomainStores.conflictQuarantineStore(directory))
            .recordOccurrence(quarantineScope, ConflictId("c-1"), resolver, DataLoomInstant(1_000L), quarantinePolicy)

        // A brand-new store and log instance continues the same count and quarantines at the threshold.
        val restarted = DurableConflictQuarantineLog(AppleFileDurableDomainStores.conflictQuarantineStore(directory))
        val current = restarted.current(quarantineScope)
        val counted = assertIs<ProviderOperationResult.Success<ConflictQuarantineRecord?>>(current)
        assertEquals(1, counted.value?.occurrenceCount)
        val second = restarted.recordOccurrence(
            quarantineScope,
            ConflictId("c-2"),
            resolver,
            DataLoomInstant(2_000L),
            quarantinePolicy,
        )
        assertIs<ConflictQuarantineObservation.Quarantined>(second)
    }

    // -------------------------------------------------------------------------
    // DurableAssetTransferSessionStore
    //
    // The domain has no "already recorded" outcome: creating a session that
    // already exists (expectedRevision = null) is rejected as StaleRevision,
    // which is this domain's duplicate absorption.
    // -------------------------------------------------------------------------

    private fun digest(seed: Int) = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { (it + seed).toByte() })

    private val sessionId = AssetTransferSessionId("apple-domain-transfer-001")

    private val manifest = AssetManifest(
        assetId = AssetId("asset-apple-001"),
        version = 1L,
        sizeBytes = 25L,
        mediaType = AssetMediaType("application/octet-stream"),
        checksum = digest(0),
        chunkLayout = AssetChunkPlan(25L, 10).toLayout(listOf(digest(1), digest(2), digest(3))),
    )

    private val createdSession = AssetTransferSession(sessionId, AssetTransferDirection.UPLOAD, manifest)

    private val sessionProof = AppleFileDurableDomainProof<AssetTransferSessionId, AssetTransferSession>(
        fileName = AppleFileDurableDomainStores.ASSET_TRANSFER_SESSION_FILE_NAME,
        scope = sessionId,
        openStore = AppleFileDurableDomainStores::assetTransferSessionStore,
        attempt = { store ->
            when (DurableAssetTransferSessionStore(store).trySave(createdSession, expectedRevision = null)) {
                is DurableAssetTransferSessionSaveOutcome.Saved -> ProofAttempt.WROTE
                is DurableAssetTransferSessionSaveOutcome.StaleRevision -> ProofAttempt.DUPLICATE
                else -> ProofAttempt.UNEXPECTED
            }
        },
        assertPersisted = { assertEquals(createdSession, it) },
    )

    @Test
    fun assetTransferSessionRecordsAndRecoversFromAFreshStoreInstance() =
        sessionProof.recordThenRecoverFromAFreshStoreInstance()

    @Test
    fun assetTransferSessionDuplicateCreateIsRejectedWithoutChangingTheRecord() =
        sessionProof.identicalDuplicateIsAbsorbedWithoutChangingTheRecord()

    @Test
    fun assetTransferSessionConcurrentDuplicateCreateConverges() =
        sessionProof.concurrentDuplicateConvergesOnOneWriteAndOneAbsorbedDuplicate()

    /**
     * Restart proof mirroring the Room-backed session integration test:
     * a freshly constructed store recovers the committed chunk indices and
     * the manifest digests, then accepts exactly the next revision and rejects
     * a stale one.
     */
    @Test
    fun assetTransferSessionRecoversCommittedChunksAndAcceptsOnlyTheNextRevision(): Unit = runBlocking {
        val directory = AppleFileDurableDomainProof.uniqueDirectory()
        val started = (createdSession.reduce(AssetTransferEvent.Start) as AssetTransferTransition.Applied).session
        val progressed = (started.reduce(AssetTransferEvent.ChunkCommitted(1)) as AssetTransferTransition.Applied).session

        val writer = DurableAssetTransferSessionStore(AppleFileDurableDomainStores.assetTransferSessionStore(directory))
        assertIs<DurableAssetTransferSessionSaveOutcome.Saved>(writer.trySave(createdSession, expectedRevision = null))
        assertIs<DurableAssetTransferSessionSaveOutcome.Saved>(
            writer.trySave(started, expectedRevision = createdSession.revision),
        )
        assertIs<DurableAssetTransferSessionSaveOutcome.Saved>(
            writer.trySave(progressed, expectedRevision = started.revision),
        )

        val reopened = DurableAssetTransferSessionStore(AppleFileDurableDomainStores.assetTransferSessionStore(directory))
        val loaded = assertIs<DurableAssetTransferSessionLoadOutcome.Found>(reopened.tryLoad(sessionId)).session
        assertEquals(progressed, loaded)
        assertEquals(setOf(1), loaded.committedChunks)
        assertEquals(manifest.checksum, loaded.manifest.checksum)

        val next = (progressed.reduce(AssetTransferEvent.ChunkCommitted(0)) as AssetTransferTransition.Applied).session
        assertNotEquals(progressed.revision, next.revision)
        assertIs<DurableAssetTransferSessionSaveOutcome.StaleRevision>(
            reopened.trySave(next, expectedRevision = createdSession.revision),
        )
        assertIs<DurableAssetTransferSessionSaveOutcome.Saved>(
            reopened.trySave(next, expectedRevision = progressed.revision),
        )
    }
}
