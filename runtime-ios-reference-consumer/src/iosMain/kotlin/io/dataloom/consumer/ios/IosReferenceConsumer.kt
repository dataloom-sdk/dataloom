package io.dataloom.consumer.ios

import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.random.DataLoomSecureRandom
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.random.AppleDataLoomSecureRandom
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.platform.ios.AppleDataLoomProviders
import io.dataloom.platform.ios.appleDataLoomProviders
import io.dataloom.platform.ios.installAppleProviders
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder

/**
 * iOS reference wiring for `#101` (DL-039A) — proves that
 * `dataloom-platform-ios`'s public [appleDataLoomProviders] and
 * [installAppleProviders] helpers (which wire [AppleDataLoomProviders]'
 * four core iOS providers) actually compose into one buildable [DataLoom]
 * instance through [DataLoomBuilder]'s public API.
 *
 * Mirrors `runtime-android-reference-consumer`'s
 * `AndroidReferenceConsumer.kt` role for the Android path: dogfooding
 * `dataloom-platform-ios`'s own real, production helper instead of
 * hand-wiring the four providers directly.
 *
 * ## Scope
 *
 * This is a **compile-only fixture**, the same documented boundary
 * `runtime-android-reference-consumer` already established for the Android
 * path. [buildReferenceDataLoom] is real, correct wiring code — it is not a
 * fake or a stub — but nothing in this module calls [DataLoom.initialize]
 * or [DataLoom.synchronize] against a real iOS `Network.framework` path,
 * `BGTaskScheduler`, or SQLite database. Proving that requires either a
 * real device/simulator instrumented test or an XCTest-backed integration
 * test; neither exists in this repository yet. That remains a separate,
 * larger follow-up — not silently claimed as covered here.
 *
 * ## Transport
 *
 * DataLoom does not ship a default transport — endpoint selection,
 * authentication, and payload serialization are always application-owned.
 * A real application would use `dataloom-transport-ktor` (this repository's
 * only transport module that cross-compiles for Kotlin/Native today) or its
 * own [TransportProvider]. This fixture uses [ReferenceTransportProvider],
 * a minimal illustrative stub, to keep this module scoped to proving iOS
 * *provider* composition rather than re-proving an already-covered
 * transport module's own HTTP integration.
 *
 * @param preRegisteredIdentifiers passed straight through to
 *   [appleDataLoomProviders] — the exact `BGTaskScheduler` identifiers the
 *   host application has already registered at launch. This fixture passes
 *   an empty set since it never actually schedules a background task.
 * @param directoryPath an absolute, application-private directory the host
 *   application has already resolved, passed straight through to
 *   [appleDataLoomProviders]. DataLoom does not resolve platform paths
 *   itself.
 */
public fun buildReferenceDataLoom(
    preRegisteredIdentifiers: Set<String> = emptySet(),
    directoryPath: String,
): DataLoom {
    val providers = appleDataLoomProviders(
        preRegisteredIdentifiers = preRegisteredIdentifiers,
        directoryPath = directoryPath,
    )
    val transportProvider = ReferenceTransportProvider()

    return DataLoomBuilder()
        .runtimeDependencies(referenceRuntimeDependencies())
        .installAppleProviders(providers, transportProvider)
        .build()
}

/**
 * Reference [RuntimeDependencies] using [AppleDataLoomClock] (real wall
 * clock) and [AppleDataLoomSecureRandom]-backed identifier generators.
 *
 * A production application should replace the identifier generators with
 * whatever scheme fits its own durability/observability requirements —
 * random hex tokens are a reasonable, dependency-free default, not a
 * DataLoom requirement.
 */
private fun referenceRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
    clock = AppleDataLoomClock(),
    identifiers = RuntimeIdentifierGenerators(
        synchronizationEventIds = randomHexIdGenerator(::SynchronizationEventId),
        queueEntryIds = randomHexIdGenerator(::QueueEntryId),
        queueLeaseIds = randomHexIdGenerator(::QueueLeaseId),
        conflictIds = randomHexIdGenerator(::ConflictId),
    ),
)

private val secureRandom: DataLoomSecureRandom = AppleDataLoomSecureRandom()

private fun <T> randomHexIdGenerator(construct: (String) -> T): IdentifierGenerator<T> =
    object : IdentifierGenerator<T> {
        override fun generate(): T = construct(secureRandom.nextBytes(16).toHexString())
    }

/**
 * Minimal illustrative [TransportProvider] — always reports no remote
 * changes and fails any push attempt. Real applications must supply their
 * own transport; see this file's top-level KDoc "Transport" section.
 */
private class ReferenceTransportProvider : TransportProvider {
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.ios.reference-transport"),
        name = ProviderName("Reference Transport (illustrative only)"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("1.0.0"),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> =
        ProviderOperationResult.Failure(ReferenceTransportError())

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> =
        ProviderOperationResult.Success(PullChangesResult.NoChanges())
}

private class ReferenceTransportError(
    override val code: io.dataloom.api.error.ErrorCode =
        io.dataloom.api.error.ErrorCode("REFERENCE_TRANSPORT_NOT_IMPLEMENTED"),
    override val category: io.dataloom.api.error.ErrorCategory = io.dataloom.api.error.ErrorCategory.NETWORK,
    override val severity: io.dataloom.api.error.ErrorSeverity = io.dataloom.api.error.ErrorSeverity.ERROR,
    override val recoverability: io.dataloom.api.error.Recoverability =
        io.dataloom.api.error.Recoverability.NON_RECOVERABLE,
    override val message: String =
        "ReferenceTransportProvider is illustrative only and never pushes changes for real.",
    override val cause: Throwable? = null,
) : io.dataloom.api.error.DataLoomError
