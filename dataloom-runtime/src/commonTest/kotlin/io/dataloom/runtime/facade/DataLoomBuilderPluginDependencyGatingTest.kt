package io.dataloom.runtime.facade

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxEntry
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginDependency
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import io.dataloom.api.plugin.PluginVersionRange
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.plugin.PluginDependencyIssue
import io.dataloom.plugin.PluginDependencyIssueReason
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizationDecision
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizer
import io.dataloom.plugin.PluginLifecycleAdministrationCommandId
import io.dataloom.plugin.PluginLifecycleAdministrationPrincipalId
import io.dataloom.plugin.PluginLifecycleAdministrationReason
import io.dataloom.plugin.PluginLifecycleTransitionRequest
import io.dataloom.plugin.PluginLifecycleTransitionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * End-to-end proof that dependency-gated activation (D19 follow-on) is wired
 * all the way through the real [DataLoomBuilder]/[DataLoom] facade: an
 * out-of-range dependency refuses a real transition attempted through
 * [DataLoom.pluginEngine], using the actual production
 * [DataLoomRuntimeVersion.CURRENT] (no test-only SDK-version override), and --
 * when `pluginOperationalEventOutboxConfiguration` (PR #419) is also
 * configured -- the refusal is durably recorded exactly like every other
 * transition outcome, never silently dropped.
 *
 * Unit-level coverage of every dependency-gating case (missing, out-of-range,
 * disabled, transitive chain) lives in
 * `io.dataloom.plugin.PluginDependencyGatingTest`; this file only proves the
 * builder/outbox wiring on top of one representative case.
 */
class DataLoomBuilderPluginDependencyGatingTest {

    private val compatibleSdkRange = PluginCompatibilityRange(minimumSdkVersion = DataLoomRuntimeVersion.CURRENT)

    @Test
    fun anOutOfRangeDependencyRefusesActivationThroughTheRealBuilder() = runTest {
        val base = plugin(id = "base", version = "1.0.0")
        val app = plugin(id = "app", dependencies = setOf(dependency("base", minimum = "2.0.0")))
        val dataLoom = builder()
            .pluginConfiguration(DataLoomPluginSpec(listOf(base, app), RecordingAuthorizer()))
            .build()
        val engine = assertNotNull(dataLoom.pluginEngine)

        val result = engine.transition(request("cmd-1", "app", PluginLifecycleState.VALIDATED))

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(
            listOf(PluginDependencyIssue(PluginId("base"), PluginDependencyIssueReason.VERSION_BELOW_MINIMUM)),
            unsatisfied.issues,
        )
        assertEquals(PluginLifecycleState.LOADED, engine.stateOf(PluginId("app")))
    }

    @Test
    fun theDependencyRefusalReachesTheOutboxWhenThePluginOutboxSpecIsConfigured() = runTest {
        val store = InMemoryOutboxStore()
        val base = plugin(id = "base", version = "1.0.0")
        val app = plugin(id = "app", dependencies = setOf(dependency("base", minimum = "2.0.0")))
        val dataLoom = builder()
            .pluginConfiguration(DataLoomPluginSpec(listOf(base, app), RecordingAuthorizer()))
            .pluginOperationalEventOutboxConfiguration(DataLoomPluginOperationalEventOutboxSpec(store))
            .build()
        val engine = assertNotNull(dataLoom.pluginEngine)

        val result = engine.transition(request("cmd-1", "app", PluginLifecycleState.VALIDATED))

        assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        val entries = pending(store)
        assertEquals(1, entries.size)
        val envelope = entries.single().envelope
        assertEquals("dataloom.plugin.lifecycle.administration.dependency_unsatisfied", envelope.type.value)
        assertEquals("1", envelope.attributes["result.unsatisfiedCount"])
        assertEquals("VERSION_BELOW_MINIMUM", envelope.attributes["result.unsatisfiedReasons"])
        assertEquals("[REDACTED]", envelope.attributes["result.unsatisfiedDependencies"])
    }

    @Test
    fun aSatisfiedDependencyActivatesNormallyAndOnlyAllowedEventsAreRecorded() = runTest {
        val store = InMemoryOutboxStore()
        val base = plugin(id = "base", version = "1.0.0")
        val app = plugin(id = "app", dependencies = setOf(dependency("base", minimum = "1.0.0")))
        val dataLoom = builder()
            .pluginConfiguration(DataLoomPluginSpec(listOf(base, app), RecordingAuthorizer()))
            .pluginOperationalEventOutboxConfiguration(DataLoomPluginOperationalEventOutboxSpec(store))
            .build()
        val engine = assertNotNull(dataLoom.pluginEngine)

        for (id in listOf("base", "app")) {
            for (target in listOf(
                PluginLifecycleState.VALIDATED,
                PluginLifecycleState.INITIALIZING,
                PluginLifecycleState.ACTIVE,
            )) {
                assertIs<PluginLifecycleTransitionResult.Allowed>(
                    engine.transition(request("$id-$target", id, target)),
                )
            }
        }

        assertEquals(PluginLifecycleState.ACTIVE, engine.stateOf(PluginId("app")))
        assertEquals(6, pending(store).size)
        assertTrue(pending(store).all { it.envelope.type.value == "dataloom.plugin.lifecycle.administration.allowed" })
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun dependency(id: String, minimum: String, maximum: String? = null) = PluginDependency(
        pluginId = PluginId(id),
        supportedVersionRange = PluginVersionRange(PluginVersion(minimum), maximum?.let(::PluginVersion)),
    )

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun plugin(
        id: String,
        version: String = "1.0.0",
        dependencies: Set<PluginDependency> = emptySet(),
    ): DataLoomPlugin = FakePlugin(
        manifest = PluginManifest(
            id = PluginId(id),
            version = PluginVersion(version),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = compatibleSdkRange,
            dependencies = dependencies,
        ),
        executionBounds = PluginExecutionBounds(maximumExecutionMillis = 1_000L, maximumConcurrentInvocations = 1),
    )

    private fun request(
        commandId: String,
        pluginId: String,
        target: PluginLifecycleState,
    ): PluginLifecycleTransitionRequest = PluginLifecycleTransitionRequest(
        commandId = PluginLifecycleAdministrationCommandId(commandId),
        pluginId = PluginId(pluginId),
        target = target,
        principalId = PluginLifecycleAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(epochMilliseconds = 9_000L),
        reason = PluginLifecycleAdministrationReason("test"),
    )

    private fun builder(): DataLoomBuilder {
        val transport = StubTransportProvider()
        return DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(StrategyProviderBindings(transportProviderId = transport.descriptor.id))
    }

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("dep-gating-event") },
            queueEntryIds = generator { QueueEntryId("dep-gating-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("dep-gating-queue-lease") },
            conflictIds = generator { ConflictId("dep-gating-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class RecordingAuthorizer(
        private val decision: PluginLifecycleAdministrationAuthorizationDecision =
            PluginLifecycleAdministrationAuthorizationDecision.Authorized,
    ) : PluginLifecycleAdministrationAuthorizer {
        override suspend fun authorize(
            request: PluginLifecycleTransitionRequest,
        ): PluginLifecycleAdministrationAuthorizationDecision = decision
    }

    private suspend fun pending(
        store: InMemoryOutboxStore,
        scope: OperationalEventOutboxScope = DEFAULT_SCOPE,
    ): List<OperationalEventOutboxEntry> {
        val reader = DurableOperationalEventOutbox(store = store, clock = FixedDataLoomClock(DataLoomInstant(0L)))
        val loaded = reader.pendingEntries(scope)
        return (loaded as ProviderOperationResult.Success).value
    }

    private companion object {
        val DEFAULT_SCOPE = OperationalEventOutboxScope("plugin-events")
    }

    private class InMemoryOutboxStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private class StubTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("dep-gating-transport"),
            name = ProviderName("Dependency Gating Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> = ProviderOperationResult.Failure(unusedError())

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())

        private fun unusedError(): DataLoomError = object : DataLoomError {
            override val code = ErrorCode("UNUSED")
            override val category = ErrorCategory.NETWORK
            override val severity = ErrorSeverity.ERROR
            override val recoverability = Recoverability.RECOVERABLE
            override val message = "Not exercised in this test."
            override val cause: Throwable? = null
        }
    }
}
