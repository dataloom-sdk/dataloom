package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.plugin.PluginCompatibilityResult
import io.dataloom.plugin.PluginExecutionBoundsEnforcer
import io.dataloom.plugin.PluginExecutionBoundsOperationalEventBridge
import io.dataloom.plugin.PluginExecutionBoundsResult
import io.dataloom.plugin.PluginExecutionInvocationId
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizer
import io.dataloom.plugin.PluginLifecycleAdministrationOperationalEventBridge
import io.dataloom.plugin.PluginLifecycleStateTracker
import io.dataloom.plugin.PluginLifecycleTransitionRequest
import io.dataloom.plugin.PluginLifecycleTransitionResult
import io.dataloom.plugin.PluginRegistry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Immutable facade adapter over the `dataloom-plugin` engine.
 *
 * Results are the engine's own types, returned unchanged.
 *
 * ## DL-042 operational-event outbox bridge (optional)
 *
 * When [operationalEventOutbox], [operationalEventOutboxScope], and [clock] are
 * all supplied (see [DataLoomPluginOperationalEventOutboxSpec] and
 * `DataLoomBuilder.pluginOperationalEventOutboxConfiguration`), every
 * transition result and every bounded-invocation result this adapter returns
 * is also bridged into an [OperationalEventEnvelope] and durably appended,
 * after the real result already exists and never altering it. An append or
 * envelope-construction failure is swallowed; only [CancellationException]
 * propagates. A call that throws (an unregistered plugin id, or an exception
 * from the caller's operation) produces no result and therefore records
 * nothing. When any collaborator is `null`, this class behaves exactly as it
 * did before the bridge existed and reads no clock.
 */
internal class DefaultDataLoomPluginEngine(
    private val registry: PluginRegistry,
    private val tracker: PluginLifecycleStateTracker,
    private val enforcer: PluginExecutionBoundsEnforcer,
    private val authorizer: PluginLifecycleAdministrationAuthorizer,
    private val operationalEventOutbox: DurableOperationalEventOutbox? = null,
    private val operationalEventOutboxScope: OperationalEventOutboxScope? = null,
    private val clock: DataLoomClock? = null,
) : DataLoomPluginEngine {

    private val invocationCounterLock = Mutex()
    private var invocationCounter: Long = 0L

    override val resolutionOrder: List<PluginId>
        get() = registry.resolutionOrder

    override fun stateOf(id: PluginId): PluginLifecycleState = tracker.stateOf(id)

    override fun compatibilityOf(id: PluginId): PluginCompatibilityResult = tracker.compatibilityOf(id)

    override suspend fun transition(
        request: PluginLifecycleTransitionRequest,
    ): PluginLifecycleTransitionResult {
        val result = tracker.transition(request, authorizer)
        record {
            PluginLifecycleAdministrationOperationalEventBridge.toEnvelope(request, result)
        }
        return result
    }

    override suspend fun <T> execute(
        id: PluginId,
        operation: suspend () -> T,
    ): PluginExecutionBoundsResult<T> {
        val result = enforcer.execute(id, operation)
        record { clockReading ->
            PluginExecutionBoundsOperationalEventBridge.toEnvelope(
                pluginId = id,
                invocationId = nextInvocationId(id, clockReading.epochMilliseconds),
                result = result,
                occurredAt = clockReading,
            )
        }
        return result
    }

    /**
     * Appends the envelope [toEnvelope] builds, if the bridge is configured.
     * [toEnvelope] receives one clock reading, taken only when configured.
     */
    private suspend fun record(toEnvelope: suspend (DataLoomInstant) -> OperationalEventEnvelope) {
        val outbox = operationalEventOutbox ?: return
        val scope = operationalEventOutboxScope ?: return
        val activeClock = clock ?: return
        try {
            outbox.append(scope, toEnvelope(activeClock.now()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ordinary: Exception) {
            // Intentionally swallowed -- see class doc above.
        }
    }

    private suspend fun nextInvocationId(id: PluginId, epochMilliseconds: Long): PluginExecutionInvocationId {
        val sequence = invocationCounterLock.withLock { ++invocationCounter }
        return PluginExecutionInvocationId("${id.value}.$epochMilliseconds.$sequence")
    }
}
