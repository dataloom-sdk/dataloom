package io.dataloom.api.strategy

/**
 * Immutable, versioned strategy profile selected by an application.
 *
 * Every concrete profile validates its own structural invariants. Runtime
 * evidence is evaluated separately and never mutates a profile.
 */
public sealed interface SynchronizationStrategyProfile {
    public val id: StrategyProfileId
    public val configurationVersion: StrategyConfigurationVersion
    public val strategy: BuiltInSynchronizationStrategy
}

/** Offline-first profile with durable local admission and later reconciliation. */
public data class OfflineFirstStrategyProfile(
    override val id: StrategyProfileId,
    override val configurationVersion: StrategyConfigurationVersion,
    public val requireDurableQueue: Boolean = true,
    public val reconcileWhenOnline: Boolean = true,
) : SynchronizationStrategyProfile {
    override val strategy: BuiltInSynchronizationStrategy =
        BuiltInSynchronizationStrategy.OFFLINE_FIRST
}

/** Remote-first profile with a finite, typed local fallback allowlist. */
public class RemoteFirstStrategyProfile(
    override val id: StrategyProfileId,
    override val configurationVersion: StrategyConfigurationVersion,
    fallbackOn: Set<StrategyRemoteOutcome> = emptySet(),
    public val persistRemoteResult: Boolean = true,
    public val unknownConnectivityPolicy: UnknownConnectivityPolicy =
        UnknownConnectivityPolicy.ATTEMPT_REMOTE,
) : SynchronizationStrategyProfile {
    override val strategy: BuiltInSynchronizationStrategy =
        BuiltInSynchronizationStrategy.REMOTE_FIRST

    private val fallbackOutcomes: Set<StrategyRemoteOutcome> = fallbackOn.toSet()

    init {
        require(StrategyRemoteOutcome.CANCELLED !in fallbackOutcomes) {
            "Remote-first fallback must not convert cancellation."
        }
        require(StrategyRemoteOutcome.AUTHENTICATION_FAILURE !in fallbackOutcomes) {
            "Remote-first fallback must not hide authentication failure."
        }
        require(StrategyRemoteOutcome.AUTHORIZATION_FAILURE !in fallbackOutcomes) {
            "Remote-first fallback must not hide authorization failure."
        }
        require(StrategyRemoteOutcome.VALIDATION_FAILURE !in fallbackOutcomes) {
            "Remote-first fallback must not hide validation failure."
        }
        require(StrategyRemoteOutcome.INTEGRITY_FAILURE !in fallbackOutcomes) {
            "Remote-first fallback must not hide integrity failure."
        }
        require(StrategyRemoteOutcome.CONFLICT !in fallbackOutcomes) {
            "Remote-first fallback must not hide conflict."
        }
    }

    public val fallbackOn: Set<StrategyRemoteOutcome>
        get() = fallbackOutcomes

    override fun equals(other: Any?): Boolean =
        other is RemoteFirstStrategyProfile &&
            id == other.id &&
            configurationVersion == other.configurationVersion &&
            fallbackOutcomes == other.fallbackOutcomes &&
            persistRemoteResult == other.persistRemoteResult &&
            unknownConnectivityPolicy == other.unknownConnectivityPolicy

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + configurationVersion.hashCode()
        result = 31 * result + fallbackOutcomes.hashCode()
        result = 31 * result + persistRemoteResult.hashCode()
        result = 31 * result + unknownConnectivityPolicy.hashCode()
        return result
    }

    override fun toString(): String =
        "RemoteFirstStrategyProfile(id=$id, configurationVersion=$configurationVersion, " +
            "fallbackOn=$fallbackOutcomes, persistRemoteResult=$persistRemoteResult, " +
            "unknownConnectivityPolicy=$unknownConnectivityPolicy)"
}

/** Cache-first profile with explicit stale and refresh behavior. */
public data class CacheFirstStrategyProfile(
    override val id: StrategyProfileId,
    override val configurationVersion: StrategyConfigurationVersion,
    public val staleCachePolicy: StaleCachePolicy = StaleCachePolicy.SERVE_STALE_AND_REFRESH,
    public val refreshOnFreshHit: Boolean = false,
    public val requireDurableRefresh: Boolean = true,
) : SynchronizationStrategyProfile {
    override val strategy: BuiltInSynchronizationStrategy =
        BuiltInSynchronizationStrategy.CACHE_FIRST
}

/** Strict transport-only profile; storage and queue are prohibited. */
public data class NetworkOnlyStrategyProfile(
    override val id: StrategyProfileId,
    override val configurationVersion: StrategyConfigurationVersion,
    public val unknownConnectivityPolicy: UnknownConnectivityPolicy =
        UnknownConnectivityPolicy.ATTEMPT_REMOTE,
) : SynchronizationStrategyProfile {
    init {
        require(unknownConnectivityPolicy != UnknownConnectivityPolicy.DEFER) {
            "Network-only cannot promise durable deferral because it prohibits queue access."
        }
    }

    override val strategy: BuiltInSynchronizationStrategy =
        BuiltInSynchronizationStrategy.NETWORK_ONLY
}

/** Source choice used by a finite hybrid strategy profile. */
public enum class HybridSource {
    LOCAL,
    REMOTE,
}

/** Hybrid profile with explicit primary, fallback, persistence, and coherence. */
public data class HybridStrategyProfile(
    override val id: StrategyProfileId,
    override val configurationVersion: StrategyConfigurationVersion,
    public val primarySource: HybridSource,
    public val fallbackSource: HybridSource,
    public val persistRemoteResult: Boolean = true,
    public val reconcileAfterFallback: Boolean = true,
    public val unknownConnectivityPolicy: UnknownConnectivityPolicy =
        UnknownConnectivityPolicy.ATTEMPT_REMOTE,
) : SynchronizationStrategyProfile {
    init {
        require(primarySource != fallbackSource) {
            "Hybrid primarySource and fallbackSource must be different."
        }
    }

    override val strategy: BuiltInSynchronizationStrategy =
        BuiltInSynchronizationStrategy.HYBRID
}

/**
 * Adaptive profile that selects only from a finite set of concrete profiles.
 *
 * Nested adaptive profiles are rejected to keep evaluation bounded and
 * deterministic.
 */
public class AdaptiveStrategyProfile(
    override val id: StrategyProfileId,
    override val configurationVersion: StrategyConfigurationVersion,
    candidates: List<SynchronizationStrategyProfile>,
    public val safeDefaultProfileId: StrategyProfileId? = null,
) : SynchronizationStrategyProfile {
    override val strategy: BuiltInSynchronizationStrategy =
        BuiltInSynchronizationStrategy.ADAPTIVE

    private val candidateProfiles: List<SynchronizationStrategyProfile> = candidates.toList()

    init {
        require(candidateProfiles.isNotEmpty()) {
            "Adaptive strategy requires at least one concrete candidate."
        }
        require(candidateProfiles.none { it.strategy == BuiltInSynchronizationStrategy.ADAPTIVE }) {
            "Adaptive strategy candidates must be concrete profiles."
        }
        require(candidateProfiles.map { it.id }.distinct().size == candidateProfiles.size) {
            "Adaptive strategy candidate IDs must be unique."
        }
        require(
            safeDefaultProfileId == null ||
                candidateProfiles.any { it.id == safeDefaultProfileId },
        ) {
            "Adaptive safeDefaultProfileId must identify one of its candidates."
        }
    }

    public val candidates: List<SynchronizationStrategyProfile>
        get() = candidateProfiles

    override fun equals(other: Any?): Boolean =
        other is AdaptiveStrategyProfile &&
            id == other.id &&
            configurationVersion == other.configurationVersion &&
            candidateProfiles == other.candidateProfiles &&
            safeDefaultProfileId == other.safeDefaultProfileId

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + configurationVersion.hashCode()
        result = 31 * result + candidateProfiles.hashCode()
        result = 31 * result + (safeDefaultProfileId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AdaptiveStrategyProfile(id=$id, configurationVersion=$configurationVersion, " +
            "candidateIds=${candidateProfiles.map { it.id }}, " +
            "safeDefaultProfileId=$safeDefaultProfileId)"
}
