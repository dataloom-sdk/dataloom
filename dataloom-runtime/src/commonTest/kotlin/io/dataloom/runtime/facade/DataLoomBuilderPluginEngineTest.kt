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
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginDependency
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
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomPluginSpec]/`pluginConfiguration` wires the `dataloom-plugin`
 * engine into [DataLoom] as an opt-in capability: absent when not configured,
 * present and deny-by-default when configured, and returning the engine's own
 * result types unchanged (no translation layer).
 */
class DataLoomBuilderPluginEngineTest {

    // -------------------------------------------------------------------------
    // Absence is inert
    // -------------------------------------------------------------------------

    @Test
    fun pluginEngineIsNullWhenNotConfigured() {
        val dataLoom = builder().build()

        assertNull(dataLoom.pluginEngine)
    }

    @Test
    fun omittingPluginConfigurationDoesNotChangeProviderLifecycleBehavior() = runTest {
        val withoutPlugins = builder().build()
        val withPlugins = builder()
            .pluginConfiguration(DataLoomPluginSpec(listOf(plugin("plugin-a")), RecordingAuthorizer()))
            .build()

        assertEquals(withoutPlugins.providerLifecycleState, withPlugins.providerLifecycleState)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(withoutPlugins.initialize())
        assertIs<ProviderLifecycleResult.InitializeSuccess>(withPlugins.initialize())
        assertEquals(withoutPlugins.providerLifecycleState, withPlugins.providerLifecycleState)
        assertNull(withoutPlugins.queueWorker)
        assertNull(withoutPlugins.retryAdministration)
        assertNull(withoutPlugins.circuitAdministration)
        assertNull(withoutPlugins.conflictAdministration)
        assertNull(withPlugins.queueWorker)
        assertNull(withPlugins.retryAdministration)
        assertNull(withPlugins.circuitAdministration)
        assertNull(withPlugins.conflictAdministration)
    }

    // -------------------------------------------------------------------------
    // Builder wiring
    // -------------------------------------------------------------------------

    @Test
    fun pluginEngineIsNonNullWhenConfiguredEvenWithNoPlugins() {
        val dataLoom = builder()
            .pluginConfiguration(DataLoomPluginSpec(emptyList(), RecordingAuthorizer()))
            .build()

        val engine = assertNotNull(dataLoom.pluginEngine)
        assertTrue(engine.resolutionOrder.isEmpty())
    }

    @Test
    fun resolutionOrderPutsDependenciesBeforeDependents() {
        val dataLoom = builder()
            .pluginConfiguration(
                DataLoomPluginSpec(
                    plugins = listOf(
                        plugin("dependent", dependsOn = setOf("base")),
                        plugin("base"),
                    ),
                    lifecycleAuthorizer = RecordingAuthorizer(),
                ),
            )
            .build()

        assertEquals(
            listOf(PluginId("base"), PluginId("dependent")),
            assertNotNull(dataLoom.pluginEngine).resolutionOrder,
        )
    }

    @Test
    fun everyRegisteredPluginStartsLoadedAndBuildInvokesNothing() = runTest {
        val authorizer = RecordingAuthorizer()
        val dataLoom = builder()
            .pluginConfiguration(
                DataLoomPluginSpec(listOf(plugin("plugin-a"), plugin("plugin-b")), authorizer),
            )
            .build()
        dataLoom.initialize()

        val engine = assertNotNull(dataLoom.pluginEngine)

        assertEquals(PluginLifecycleState.LOADED, engine.stateOf(PluginId("plugin-a")))
        assertEquals(PluginLifecycleState.LOADED, engine.stateOf(PluginId("plugin-b")))
        assertTrue(authorizer.requests.isEmpty())
    }

    @Test
    fun invalidPluginGraphIsRejectedFromBuild() {
        val duplicate = builder().pluginConfiguration(
            DataLoomPluginSpec(listOf(plugin("plugin-a"), plugin("plugin-a")), RecordingAuthorizer()),
        )
        val unresolved = builder().pluginConfiguration(
            DataLoomPluginSpec(listOf(plugin("plugin-a", dependsOn = setOf("missing"))), RecordingAuthorizer()),
        )
        val cyclic = builder().pluginConfiguration(
            DataLoomPluginSpec(
                listOf(plugin("a", dependsOn = setOf("b")), plugin("b", dependsOn = setOf("a"))),
                RecordingAuthorizer(),
            ),
        )

        assertFailsWith<IllegalArgumentException> { duplicate.build() }
        assertFailsWith<IllegalArgumentException> { unresolved.build() }
        assertFailsWith<IllegalArgumentException> { cyclic.build() }
    }

    @Test
    fun specCopiesItsPluginListDefensively() {
        val mutable = mutableListOf(plugin("plugin-a"))
        val spec = DataLoomPluginSpec(mutable, RecordingAuthorizer())
        mutable.add(plugin("plugin-b"))

        val engine = assertNotNull(builder().pluginConfiguration(spec).build().pluginEngine)

        assertEquals(listOf(PluginId("plugin-a")), engine.resolutionOrder)
    }

    @Test
    fun specDiagnosticsDoNotRenderPluginOrAuthorizerState() {
        val spec = DataLoomPluginSpec(listOf(plugin("secret-plugin-id")), RecordingAuthorizer())

        assertEquals("DataLoomPluginSpec(pluginCount=1)", spec.toString())
    }

    @Test
    fun theMostRecentPluginConfigurationWins() {
        val dataLoom = builder()
            .pluginConfiguration(DataLoomPluginSpec(listOf(plugin("first")), RecordingAuthorizer()))
            .pluginConfiguration(DataLoomPluginSpec(listOf(plugin("second")), RecordingAuthorizer()))
            .build()

        assertEquals(listOf(PluginId("second")), assertNotNull(dataLoom.pluginEngine).resolutionOrder)
    }

    // -------------------------------------------------------------------------
    // Authorized lifecycle transitions (engine results returned unchanged)
    // -------------------------------------------------------------------------

    @Test
    fun authorizedTransitionReturnsAllowedAndUpdatesState() = runTest {
        val authorizer = RecordingAuthorizer()
        val engine = engineWith(authorizer, plugin("plugin-a"))
        val request = transitionRequest("plugin-a", PluginLifecycleState.VALIDATED)

        val result = engine.transition(request)

        assertEquals(
            PluginLifecycleTransitionResult.Allowed(
                from = PluginLifecycleState.LOADED,
                to = PluginLifecycleState.VALIDATED,
            ),
            result,
        )
        assertEquals(PluginLifecycleState.VALIDATED, engine.stateOf(PluginId("plugin-a")))
        assertEquals(listOf(request), authorizer.requests)
    }

    @Test
    fun deniedTransitionReturnsAuthorizationDeniedAndLeavesStateUnchanged() = runTest {
        val authorizer = RecordingAuthorizer(
            PluginLifecycleAdministrationAuthorizationDecision.Denied("NOT_PERMITTED"),
        )
        val engine = engineWith(authorizer, plugin("plugin-a"))

        val result = engine.transition(transitionRequest("plugin-a", PluginLifecycleState.VALIDATED))

        assertEquals(
            PluginLifecycleTransitionResult.AuthorizationDenied(
                from = PluginLifecycleState.LOADED,
                to = PluginLifecycleState.VALIDATED,
                reasonCode = "NOT_PERMITTED",
            ),
            result,
        )
        assertEquals(PluginLifecycleState.LOADED, engine.stateOf(PluginId("plugin-a")))
    }

    @Test
    fun structurallyIllegalTransitionIsRejectedWithoutConsultingTheAuthorizer() = runTest {
        val authorizer = RecordingAuthorizer()
        val engine = engineWith(authorizer, plugin("plugin-a"))

        val result = engine.transition(transitionRequest("plugin-a", PluginLifecycleState.ACTIVE))

        assertIs<PluginLifecycleTransitionResult.Rejected>(result)
        assertTrue(authorizer.requests.isEmpty())
        assertEquals(PluginLifecycleState.LOADED, engine.stateOf(PluginId("plugin-a")))
    }

    @Test
    fun transitionOfAnUnregisteredPluginThrows() = runTest {
        val engine = engineWith(RecordingAuthorizer(), plugin("plugin-a"))

        assertFailsWith<IllegalArgumentException> {
            engine.transition(transitionRequest("missing", PluginLifecycleState.VALIDATED))
        }
        assertFailsWith<IllegalArgumentException> { engine.stateOf(PluginId("missing")) }
    }

    // -------------------------------------------------------------------------
    // Bounded execution (engine results returned unchanged)
    // -------------------------------------------------------------------------

    @Test
    fun executeReturnsCompletedWithTheOperationValue() = runTest {
        val engine = engineWith(RecordingAuthorizer(), plugin("plugin-a"))

        val result = engine.execute(PluginId("plugin-a")) { "done" }

        assertEquals(PluginExecutionBoundsResult.Completed("done"), result)
    }

    @Test
    fun executeReturnsTimedOutWhenTheDeclaredBoundIsExceeded() = runTest {
        val engine = engineWith(RecordingAuthorizer(), plugin("plugin-a", maximumExecutionMillis = 100L))

        val result = engine.execute(PluginId("plugin-a")) {
            delay(1_000L)
            "late"
        }

        assertEquals(
            PluginExecutionBoundsResult.TimedOut(pluginId = PluginId("plugin-a"), maximumExecutionMillis = 100L),
            result,
        )
    }

    @Test
    fun executeRejectsAnInvocationBeyondTheDeclaredConcurrencyCeiling() = runTest {
        val engine = engineWith(RecordingAuthorizer(), plugin("plugin-a", maximumConcurrentInvocations = 1))
        val started = CompletableDeferred<Unit>()
        val inFlight = backgroundScope.async {
            engine.execute(PluginId("plugin-a")) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        var secondInvoked = false

        val rejected = engine.execute(PluginId("plugin-a")) {
            secondInvoked = true
            "unreachable"
        }

        assertEquals(
            PluginExecutionBoundsResult.ConcurrencyLimitExceeded(
                pluginId = PluginId("plugin-a"),
                maximumConcurrentInvocations = 1,
            ),
            rejected,
        )
        assertFalse(secondInvoked)
        inFlight.cancel()
    }

    @Test
    fun executeForAnUnregisteredPluginThrows() = runTest {
        val engine = engineWith(RecordingAuthorizer(), plugin("plugin-a"))

        assertFailsWith<IllegalArgumentException> {
            engine.execute(PluginId("missing")) { "unreachable" }
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun engineWith(
        authorizer: PluginLifecycleAdministrationAuthorizer,
        vararg plugins: DataLoomPlugin,
    ): DataLoomPluginEngine = assertNotNull(
        builder()
            .pluginConfiguration(DataLoomPluginSpec(plugins.toList(), authorizer))
            .build()
            .pluginEngine,
    )

    private fun builder(): DataLoomBuilder {
        val transport = StubTransportProvider()
        return DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(StrategyProviderBindings(transportProviderId = transport.descriptor.id))
    }

    private val compatibilityRange = PluginCompatibilityRange(minimumSdkVersion = RuntimeVersion("1.0.0"))

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun plugin(
        id: String,
        dependsOn: Set<String> = emptySet(),
        maximumExecutionMillis: Long = 1_000L,
        maximumConcurrentInvocations: Int = 1,
    ): DataLoomPlugin = FakePlugin(
        manifest = PluginManifest(
            id = PluginId(id),
            version = PluginVersion("1.0.0"),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = compatibilityRange,
            dependencies = dependsOn.map { PluginDependency(PluginId(it), compatibilityRange) }.toSet(),
        ),
        executionBounds = PluginExecutionBounds(maximumExecutionMillis, maximumConcurrentInvocations),
    )

    private fun transitionRequest(
        pluginId: String,
        target: PluginLifecycleState,
    ): PluginLifecycleTransitionRequest = PluginLifecycleTransitionRequest(
        commandId = PluginLifecycleAdministrationCommandId("command-1"),
        pluginId = PluginId(pluginId),
        target = target,
        principalId = PluginLifecycleAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(epochMilliseconds = 9_000L),
        reason = PluginLifecycleAdministrationReason("test"),
    )

    private class RecordingAuthorizer(
        private val decision: PluginLifecycleAdministrationAuthorizationDecision =
            PluginLifecycleAdministrationAuthorizationDecision.Authorized,
    ) : PluginLifecycleAdministrationAuthorizer {
        val requests: MutableList<PluginLifecycleTransitionRequest> = mutableListOf()

        override suspend fun authorize(
            request: PluginLifecycleTransitionRequest,
        ): PluginLifecycleAdministrationAuthorizationDecision {
            requests.add(request)
            return decision
        }
    }

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("plugin-engine-event") },
            queueEntryIds = generator { QueueEntryId("plugin-engine-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("plugin-engine-queue-lease") },
            conflictIds = generator { ConflictId("plugin-engine-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class StubTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("plugin-engine-transport"),
            name = ProviderName("Plugin Engine Transport"),
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
