package io.dataloom.api.lifecycle

import io.dataloom.api.provider.ProviderCapability

/**
 * Capability labels an [AppLifecycleProvider] may declare in
 * [io.dataloom.api.provider.ProviderDescriptor.capabilities].
 */
public object AppLifecycleCapabilities {
    /**
     * Declared by every provider: [AppLifecycleProvider.states] delivers
     * lifecycle transitions as they happen.
     */
    public val STATE_STREAM: ProviderCapability = ProviderCapability("state-stream")

    /**
     * Declared only by providers whose platform can signal imminent process
     * termination, meaning [AppLifecycleState.TERMINATING_SOON] can be
     * observed. Absence is not an error: the platform simply offers no signal.
     */
    public val TERMINATING_SOON: ProviderCapability = ProviderCapability("terminating-soon")
}
