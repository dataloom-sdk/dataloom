package io.dataloom.api.connectivity

/**
 * Closed set of device-level network connectivity states.
 *
 * [ConnectivityStatus] represents what a platform connectivity provider
 * currently reports about the device network state. It does not represent
 * backend server reachability or application-level endpoint availability.
 *
 * These values are not bound to Android network-capability constants,
 * Apple network-path status values, or any other platform-specific
 * representation.
 *
 * Enum ordinals are not a compatibility contract and must not be persisted.
 */
public enum class ConnectivityStatus {
    /**
     * Connectivity cannot currently be determined.
     *
     * The provider is unable to evaluate the current network state. This may
     * occur during initialization, after a platform permission denial, or when
     * the underlying connectivity API is temporarily unavailable.
     */
    UNKNOWN,

    /**
     * No usable network connection is currently reported.
     *
     * The platform reports that no network interface with usable connectivity
     * is available.
     */
    UNAVAILABLE,

    /**
     * Usable network connectivity is currently reported.
     *
     * The platform reports that at least one network interface with usable
     * connectivity is available. This does not prove that a backend endpoint
     * is reachable or that any specific request will succeed.
     */
    AVAILABLE,

    /**
     * Connectivity exists but may be restricted, captive, unvalidated, or
     * otherwise unsuitable for reliable synchronization.
     *
     * Examples include a captive Wi-Fi portal that has not been authenticated,
     * an interface with no validated internet path, or a connection reported
     * as having limited capability by the platform.
     */
    LIMITED,
}
