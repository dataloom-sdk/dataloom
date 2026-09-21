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
import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxEntry
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
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
import io.dataloom.plugin.PluginExecutionBoundsResult
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizationDecision
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizer
import io.dataloom.plugin.PluginLifecycleAdministrationCommandId
import io.dataloom.plugin.PluginLifecycleAdministrationPrincipalId
import io.dataloom.plugin.PluginLifecycleAdministrationReason
import io.dataloom.plugin.PluginLifecycleTransitionRequest
import io.dataloom.plugin.PluginLifecycleTransitionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * Proves `pluginOperationalEventOutboxConfiguration` durably appends the
 * plugin engine's transition and bounded-execution results, in order, and that
 * omitting it (or the plugin engine) is inert.
 */
class DataLoomBuilderPluginOperationalEventOutboxTest {

    private val pluginId = PluginId("plugin-a")

    // -------------------------------------------------------------------------
    // Absence is inert
    // -------------------------------------------------------------------------

    @Test
    fun nothingIsAppendedAndNoClockIsReadWhenTheOutboxIsNotConfigured() = runTest {
        val clock = CountingClock()
        val engine = engine(clock = clock, outboxStore = null)

        val transition = engine.transition(request("cmd-1", PluginLifecycleState.VALIDATED))
        val execution = engine.execute(pluginId) { "x" }

        assertIs<PluginLifecycleTransitionResult.Allowed>(transition)
        assertIs<PluginExecutionBoundsResult.NotActive>(execution)
        assertEquals(0, clock.reads)
    }

    @Test
    fun theOutboxAloneDoesNotCreateAPluginEngineAndAppendsNothing() = runTest {
        val store = InMemoryOutboxStore()
        val dataLoom = builder(CountingClock())
            .pluginOperationalEventOutboxConfiguration(DataLoomPluginOperationalEventOutboxSpec(store))
            .build()

        assertNull(dataLoom.pluginEngine)
        assertEquals(0, store.appendedCount(DEFAULT_SCOPE))
    }

    @Test
    fun buildAppendsNothingAndReadsNoClock() {
        val clock = CountingClock()
        val store = InMemoryOutboxStore()

        engine(clock = clock, outboxStore = store)

        assertEquals(0, clock.reads)
        assertEquals(0, store.records.size)
    }

    // -------------------------------------------------------------------------
    // Ordered end-to-end audit trail
    // -------------------------------------------------------------------------

    @Test
    fun transitionsAndBoundedOutcomesAreAppendedInOrder() = runTest {
        val store = InMemoryOutboxStore()
        val engine = engine(outboxStore = store, plugin = plugin(maximumExecutionMillis = 500L))

        engine.transition(request("cmd-1", PluginLifecycleState.VALIDATED))
        engine.transition(request("cmd-2", PluginLifecycleState.INITIALIZING))
        engine.transition(request("cmd-3", PluginLifecycleState.ACTIVE))
        engine.execute(pluginId) { "ok" }
        engine.execute(pluginId) {
            delay(10_000L)
            "late"
        }
        engine.transition(request("cmd-4", PluginLifecycleState.DISABLED))
        engine.execute(pluginId) { "refused" }

        val entries = pending(store)
        assertEquals((1L..7L).toList(), entries.map { it.sequence })
        assertEquals(
            listOf(
                "dataloom.plugin.lifecycle.administration.allowed",
                "dataloom.plugin.lifecycle.administration.allowed",
                "dataloom.plugin.lifecycle.administration.allowed",
                "dataloom.plugin.execution.bounds.completed",
                "dataloom.plugin.execution.bounds.timed_out",
                "dataloom.plugin.lifecycle.administration.allowed",
                "dataloom.plugin.execution.bounds.not_active",
            ),
            entries.map { it.envelope.type.value },
        )
        assertEquals("DISABLED", entries.last().envelope.attributes["result.state"])
        assertEquals("500", entries[4].envelope.attributes["result.maximumExecutionMillis"])
        assertEquals(9_000L, entries[3].envelope.occurredAt.epochMilliseconds)
        assertEquals(entries[3].envelope.correlationId.value, "plugin-a.9000.1")
        assertEquals(entries[4].envelope.correlationId.value, "plugin-a.9000.2")
    }

    @Test
    fun aConcurrencyLimitOutcomeIsAppendedBeforeTheInFlightInvocationCompletes() = runTest {
        val store = InMemoryOutboxStore()
        val engine = engine(outboxStore = store, plugin = plugin(maximumConcurrentInvocations = 1))
        activate(engine)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val inFlight = backgroundScope.async {
            engine.execute(pluginId) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()

        val refused = engine.execute(pluginId) { "second" }
        release.complete("first")
        inFlight.await()

        assertIs<PluginExecutionBoundsResult.ConcurrencyLimitExceeded>(refused)
        assertEquals(
            listOf(
                "dataloom.plugin.lifecycle.administration.allowed",
                "dataloom.plugin.lifecycle.administration.allowed",
                "dataloom.plugin.lifecycle.administration.allowed",
                "dataloom.plugin.execution.bounds.concurrency_limit_exceeded",
                "dataloom.plugin.execution.bounds.completed",
            ),
            pending(store).map { it.envelope.type.value },
        )
        assertEquals("1", pending(store)[3].envelope.attributes["result.maximumConcurrentInvocations"])
    }

    @Test
    fun everyKindOfRefusedTransitionIsRecordedToo() = runTest {
        val store = InMemoryOutboxStore()
        val denyingAuthorizer = RecordingAuthorizer(PluginLifecycleAdministrationAuthorizationDecision.Denied("NO"))
        val incompatible = plugin(id = PluginId("old"), minimumSdk = "9.0.0")
        val engine = engine(
            outboxStore = store,
            authorizer = denyingAuthorizer,
            plugins = listOf(plugin(), incompatible),
        )

        engine.transition(request("cmd-1", PluginLifecycleState.ACTIVE))
        engine.transition(request("cmd-2", PluginLifecycleState.VALIDATED))
        engine.transition(request("cmd-3", PluginLifecycleState.VALIDATED, PluginId("old")))

        assertEquals(
            listOf(
                "dataloom.plugin.lifecycle.administration.rejected",
                "dataloom.plugin.lifecycle.administration.authorization_denied",
                "dataloom.plugin.lifecycle.administration.incompatible_runtime",
            ),
            pending(store).map { it.envelope.type.value },
        )
    }

    @Test
    fun reusingACommandIdIsIdempotentAndAppendsOnlyOnce() = runTest {
        val store = InMemoryOutboxStore()
        val engine = engine(outboxStore = store)

        engine.transition(request("cmd-1", PluginLifecycleState.VALIDATED))
        engine.transition(request("cmd-1", PluginLifecycleState.VALIDATED))

        assertEquals(1, pending(store).size)
    }

    @Test
    fun aCustomScopeReceivesTheEventsAndTheDefaultScopeStaysEmpty() = runTest {
        val store = InMemoryOutboxStore()
        val custom = OperationalEventOutboxScope("my-plugin-events")
        val engine = engine(outboxStore = store, scope = custom)

        engine.transition(request("cmd-1", PluginLifecycleState.VALIDATED))

        assertEquals(1, pending(store, custom).size)
        assertEquals(0, pending(store, DEFAULT_SCOPE).size)
    }

    // -------------------------------------------------------------------------
    // Recording never changes or breaks the result
    // -------------------------------------------------------------------------

    @Test
    fun aFailingStoreNeverChangesOrBreaksTheReturnedResults() = runTest {
        val engine = engine(outboxStore = FailingOutboxStore())

        val transition = engine.transition(request("cmd-1", PluginLifecycleState.VALIDATED))
        val execution = engine.execute(pluginId) { "x" }

        assertIs<PluginLifecycleTransitionResult.Allowed>(transition)
        assertEquals(PluginLifecycleState.VALIDATED, engine.stateOf(pluginId))
        assertIs<PluginExecutionBoundsResult.NotActive>(execution)
    }

    @Test
    fun anOperationThatThrowsProducesNoResultAndNoEvent() = runTest {
        val store = InMemoryOutboxStore()
        val engine = engine(outboxStore = store)
        activate(engine)
        val before = pending(store).size

        assertFailsWith<IllegalStateException> { engine.execute(pluginId) { error("boom-secret") } }
        assertFailsWith<IllegalArgumentException> { engine.execute(PluginId("missing")) { "x" } }

        assertEquals(before, pending(store).size)
    }

    @Test
    fun pluginOutputNeverReachesTheStoredEnvelope() = runTest {
        val store = InMemoryOutboxStore()
        val engine = engine(outboxStore = store)
        activate(engine)

        engine.execute(pluginId) { "secret-plugin-output-77" }

        val recorded = pending(store).last()
        assertEquals("dataloom.plugin.execution.bounds.completed", recorded.envelope.type.value)
        assertFalse(recorded.envelope.toString().contains("secret-plugin-output-77"))
        assertFalse(recorded.envelope.attributes.entries.values.any { it.contains("secret-plugin-output-77") })
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private companion object {
        val DEFAULT_SCOPE = OperationalEventOutboxScope("plugin-events")
        val SDK_VERSION = RuntimeVersion("1.5.0")
    }

    private suspend fun activate(engine: DataLoomPluginEngine) {
        engine.transition(request("act-1", PluginLifecycleState.VALIDATED))
        engine.transition(request("act-2", PluginLifecycleState.INITIALIZING))
        engine.transition(request("act-3", PluginLifecycleState.ACTIVE))
    }

    private suspend fun pending(
        store: InMemoryOutboxStore,
        scope: OperationalEventOutboxScope = DEFAULT_SCOPE,
    ): List<OperationalEventOutboxEntry> {
        val reader = DurableOperationalEventOutbox(store = store, clock = CountingClock())
        val loaded = reader.pendingEntries(scope)
        return (loaded as ProviderOperationResult.Success).value
    }

    private fun engine(
        clock: DataLoomClock = CountingClock(),
        outboxStore: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>?,
        scope: OperationalEventOutboxScope? = null,
        authorizer: PluginLifecycleAdministrationAuthorizer = RecordingAuthorizer(),
        plugin: DataLoomPlugin = plugin(),
        plugins: List<DataLoomPlugin> = listOf(plugin),
    ): DataLoomPluginEngine {
        val builder = builder(clock)
            .pluginConfiguration(DataLoomPluginSpec(plugins, authorizer))
        if (outboxStore != null) {
            builder.pluginOperationalEventOutboxConfiguration(
                if (scope == null) {
                    DataLoomPluginOperationalEventOutboxSpec(outboxStore)
                } else {
                    DataLoomPluginOperationalEventOutboxSpec(outboxStore, scope)
                },
            )
        }
        return assertNotNull(builder.build().pluginEngine)
    }

    private fun builder(clock: DataLoomClock): DataLoomBuilder {
        val transport = StubTransportProvider()
        return DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies(clock))
            .provider(transport)
            .defaultStrategyProviderBindings(StrategyProviderBindings(transportProviderId = transport.descriptor.id))
            .apply { pluginSdkVersion = SDK_VERSION }
    }

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun plugin(
        id: PluginId = pluginId,
        maximumExecutionMillis: Long = 1_000L,
        maximumConcurrentInvocations: Int = 1,
        minimumSdk: String = "1.0.0",
    ): DataLoomPlugin = FakePlugin(
        manifest = PluginManifest(
            id = id,
            version = PluginVersion("1.0.0"),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = PluginCompatibilityRange(RuntimeVersion(minimumSdk)),
        ),
        executionBounds = PluginExecutionBounds(maximumExecutionMillis, maximumConcurrentInvocations),
    )

    private fun request(
        commandId: String,
        target: PluginLifecycleState,
        id: PluginId = pluginId,
    ): PluginLifecycleTransitionRequest = PluginLifecycleTransitionRequest(
        commandId = PluginLifecycleAdministrationCommandId(commandId),
        pluginId = id,
        target = target,
        principalId = PluginLifecycleAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(epochMilliseconds = 9_000L),
        reason = PluginLifecycleAdministrationReason("test"),
    )

    private class RecordingAuthorizer(
        private val decision: PluginLifecycleAdministrationAuthorizationDecision =
            PluginLifecycleAdministrationAuthorizationDecision.Authorized,
    ) : PluginLifecycleAdministrationAuthorizer {
        override suspend fun authorize(
            request: PluginLifecycleTransitionRequest,
        ): PluginLifecycleAdministrationAuthorizationDecision = decision
    }

    private class CountingClock : DataLoomClock {
        var reads: Int = 0
            private set

        override fun now(): DataLoomInstant {
            reads++
            return DataLoomInstant(epochMilliseconds = 9_000L)
        }
    }

    private fun runtimeDependencies(clock: DataLoomClock): RuntimeDependencies = RuntimeDependencies(
        clock = clock,
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("plugin-outbox-event") },
            queueEntryIds = generator { QueueEntryId("plugin-outbox-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("plugin-outbox-queue-lease") },
            conflictIds = generator { ConflictId("plugin-outbox-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class InMemoryOutboxStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        fun appendedCount(scope: OperationalEventOutboxScope): Int = records[scope]?.state?.entries?.size ?: 0

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

    private class FailingOutboxStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private fun failure(): DataLoomError = object : DataLoomError {
            override val code = ErrorCode("STORE_DOWN")
            override val category = ErrorCategory.NETWORK
            override val severity = ErrorSeverity.ERROR
            override val recoverability = Recoverability.RECOVERABLE
            override val message = "store failure"
            override val cause: Throwable? = null
        }

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(failure())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(failure())
    }

    private class StubTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("plugin-outbox-transport"),
            name = ProviderName("Plugin Outbox Transport"),
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
