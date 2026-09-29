package io.dataloom.runtime.state

import io.dataloom.api.conflict.ConflictQuarantineRecord
import io.dataloom.api.conflict.ConflictQuarantineRecordCodec
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.ResolvedConflictDecisionRecordCodec
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.conflict.UnresolvedConflictRecordCodec
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.policy.PolicyDecisionRecord
import io.dataloom.api.policy.PolicyDecisionRecordCodec
import io.dataloom.api.policy.PolicyDecisionScope
import io.dataloom.assets.AssetTransferSession
import io.dataloom.assets.AssetTransferSessionCodec
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.DurableAssetTransferSessionStore

/**
 * Apple production wiring of [AppleFileDurableStateStore] for the durable
 * domains that already ship a domain log or store, a state codec and a scope
 * key encoder, and that until now only had a Room-backed wiring.
 *
 * Each factory gives its domain one dedicated, explicitly named file inside
 * the caller's application-private [directoryPath] (the file-per-domain
 * convention `AppleFileDurableStateStore` documents in place of Room's
 * `namespace` column). The codec and key encoder are the domain's own, reused
 * unchanged, so the on-disk payload is exactly what the Room-backed store
 * persists and no new schema exists.
 *
 * The file names are distinct from each other, from
 * [AppleFileDurableStateStore.DEFAULT_FILE_NAME], and from the names the
 * Apple contention and termination proof modules use for their own throwaway
 * directories, so a real application can safely share one directory across
 * all of these domains.
 */
public object AppleFileDurableDomainStores {

    /** Snapshot file backing [policyDecisionStore]. */
    public const val POLICY_DECISION_FILE_NAME: String = "dataloom-policy-decision-state-v1.tsv"

    /** Snapshot file backing [unresolvedConflictStore]. */
    public const val UNRESOLVED_CONFLICT_FILE_NAME: String = "dataloom-unresolved-conflict-state-v1.tsv"

    /** Snapshot file backing [resolvedConflictDecisionStore]. */
    public const val RESOLVED_CONFLICT_DECISION_FILE_NAME: String =
        "dataloom-resolved-conflict-decision-state-v1.tsv"

    /** Snapshot file backing [conflictQuarantineStore]. */
    public const val CONFLICT_QUARANTINE_FILE_NAME: String = "dataloom-conflict-quarantine-state-v1.tsv"

    /** Snapshot file backing [assetTransferSessionStore]. */
    public const val ASSET_TRANSFER_SESSION_FILE_NAME: String = "dataloom-asset-transfer-session-state-v1.tsv"

    /** Backing store for [io.dataloom.api.policy.DurablePolicyDecisionLog]. */
    public fun policyDecisionStore(
        directoryPath: String,
        fileName: String = POLICY_DECISION_FILE_NAME,
    ): AppleFileDurableStateStore<PolicyDecisionScope, PolicyDecisionRecord> = AppleFileDurableStateStore(
        directoryPath = directoryPath,
        fileName = fileName,
        scopeKeyEncoder = PolicyDecisionScope.KeyEncoder,
        codec = PolicyDecisionRecordCodec(),
    )

    /** Backing store for [DurableUnresolvedConflictLog]. */
    public fun unresolvedConflictStore(
        directoryPath: String,
        fileName: String = UNRESOLVED_CONFLICT_FILE_NAME,
    ): AppleFileDurableStateStore<ConflictId, UnresolvedConflictRecord> = AppleFileDurableStateStore(
        directoryPath = directoryPath,
        fileName = fileName,
        scopeKeyEncoder = DurableUnresolvedConflictLog.KeyEncoder,
        codec = UnresolvedConflictRecordCodec(),
    )

    /** Backing store for [DurableResolvedConflictDecisionLog]. */
    public fun resolvedConflictDecisionStore(
        directoryPath: String,
        fileName: String = RESOLVED_CONFLICT_DECISION_FILE_NAME,
    ): AppleFileDurableStateStore<ConflictId, ResolvedConflictDecisionRecord> = AppleFileDurableStateStore(
        directoryPath = directoryPath,
        fileName = fileName,
        scopeKeyEncoder = DurableResolvedConflictDecisionLog.KeyEncoder,
        codec = ResolvedConflictDecisionRecordCodec(),
    )

    /**
     * Backing store for [io.dataloom.api.conflict.DurableConflictQuarantineLog]
     * and `DataLoomConflictQuarantineSpec.store`.
     */
    public fun conflictQuarantineStore(
        directoryPath: String,
        fileName: String = CONFLICT_QUARANTINE_FILE_NAME,
    ): AppleFileDurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> = AppleFileDurableStateStore(
        directoryPath = directoryPath,
        fileName = fileName,
        scopeKeyEncoder = ConflictQuarantineScope.KeyEncoder,
        codec = ConflictQuarantineRecordCodec(),
    )

    /** Backing store for [DurableAssetTransferSessionStore]. */
    public fun assetTransferSessionStore(
        directoryPath: String,
        fileName: String = ASSET_TRANSFER_SESSION_FILE_NAME,
    ): AppleFileDurableStateStore<AssetTransferSessionId, AssetTransferSession> = AppleFileDurableStateStore(
        directoryPath = directoryPath,
        fileName = fileName,
        scopeKeyEncoder = DurableAssetTransferSessionStore.KeyEncoder,
        codec = AssetTransferSessionCodec(),
    )
}
