package io.dataloom.runtime.facade

import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.lifecycle.AppLifecycleProvider
import io.dataloom.api.provider.ProviderType

/**
 * Immutable configuration for the opt-in lifecycle-triggered queue drain
 * (ADR-0013, D23).
 *
 * Supplying this spec to [DataLoomBuilder.lifecycleDrainConfiguration] makes
 * [DataLoom.lifecycleDrain] non-null after [DataLoomBuilder.build]. Omitting it
 * leaves [DataLoom] behavior unchanged and [DataLoom.lifecycleDrain] `null`.
 * A queue worker ([DataLoomBuilder.queueWorkerConfiguration] or
 * [DataLoomBuilder.circuitQueueWorkerConfiguration]) is required: the drain
 * runs through it and has no worker path of its own.
 *
 * ## Ownership
 *
 * [lifecycleProvider] is a host or platform dependency. The builder only
 * reads its descriptor: it does not initialize, health-check, collect or close
 * the provider, and does not register it in the provider registry. Construction
 * and [DataLoomBuilder.build] perform no collection, clock read, identifier
 * generation, queue operation or coroutine launch.
 *
 * ## Consumer identity
 *
 * [consumerId] is the queue consumer every drain acquires under. It is stable
 * across drains; each drain draws a fresh lease identifier from the runtime's
 * lease-identifier generator.
 *
 * @param lifecycleProvider platform-neutral lifecycle source; its descriptor
 *   type must be [ProviderType.APP_LIFECYCLE].
 * @param consumerId queue consumer identity used for every drain.
 * @param policy which transitions drain, and the interval and size bounds.
 */
public class DataLoomLifecycleDrainSpec(
    public val lifecycleProvider: AppLifecycleProvider,
    public val consumerId: QueueConsumerId,
    public val policy: LifecycleDrainPolicy = LifecycleDrainPolicy(),
) {
    init {
        require(lifecycleProvider.descriptor.type == ProviderType.APP_LIFECYCLE) {
            "DataLoomLifecycleDrainSpec lifecycleProvider must have provider type APP_LIFECYCLE."
        }
    }

    /** Avoids rendering the provider's implementation state in diagnostics. */
    override fun toString(): String =
        "DataLoomLifecycleDrainSpec(consumerId=$consumerId, policy=$policy)"
}
