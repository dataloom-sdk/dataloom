package io.dataloom.api.connectivity

import io.dataloom.api.context.DataLoomMetadata

/**
 * Immutable snapshot of the current device-level network connectivity state.
 *
 * [ConnectivitySnapshot] represents what a
 * [ConnectivityProvider] observed at the time of a connectivity query. It
 * describes device-level connectivity, not backend server reachability or
 * application-level endpoint availability.
 *
 * ## Metering state
 *
 * [isMetered] is nullable because some platforms or provider implementations
 * may not be able to determine whether the active connection is metered. A
 * `null` value means the provider cannot determine metering state and must
 * not be treated as unmetered.
 *
 * ## Sensitive-data restrictions
 *
 * [ConnectivitySnapshot] must not expose IP addresses, interface names, SSIDs,
 * carrier names, VPN details, MAC addresses, or platform network handles.
 * [metadata] must not contain credentials, authentication tokens, personal
 * data, or sensitive network information.
 *
 * ## Equality
 *
 * Equality compares [status], [isMetered], and [metadata] by value.
 *
 * @param status required current connectivity state reported by the platform.
 * @param isMetered `true` when the active connection is metered, `false` when
 *   it is unmetered, or `null` when the provider cannot determine the metering
 *   state.
 * @param metadata optional contextual attributes for this snapshot. Defaults
 *   to [DataLoomMetadata.Empty].
 */
public data class ConnectivitySnapshot(
    /** Required current connectivity state reported by the platform. */
    public val status: ConnectivityStatus,

    /**
     * Metering state of the active connection, or `null` when the provider
     * cannot determine whether the connection is metered.
     *
     * `null` must not be treated as unmetered.
     */
    public val isMetered: Boolean?,

    /**
     * Optional contextual attributes for this snapshot.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied. Must not contain
     * IP addresses, SSIDs, carrier names, VPN details, credentials, or
     * personal data.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
