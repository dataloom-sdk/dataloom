package io.dataloom.platform.ios.connectivity.internal

import io.dataloom.api.connectivity.ConnectivityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests [classifyPath], the pure logic that translates a raw
 * [NetworkPathObservation] into a [io.dataloom.api.connectivity.ConnectivitySnapshot].
 *
 * This exercises only the classification rules -- it never constructs an
 * `NWPathMonitor` and does not require an iOS host, simulator, or device to
 * type-check and compile. This module declares only iOS Kotlin/Native
 * targets (`iosArm64`, `iosSimulatorArm64`, `iosX64`), so this test compiles
 * for those targets but, like every other Kotlin/Native test in this
 * module, cannot be *executed* from this Windows host -- running a
 * Kotlin/Native iOS test binary requires an actual macOS host or simulator.
 * See docs/apple/connectivity-provider.md for the exact verification this
 * slice ran instead (klib-level compilation of both main and test sources).
 */
class ConnectivityClassificationTest {

    @Test
    fun `satisfied path maps to AVAILABLE`() {
        val snapshot = classifyPath(
            NetworkPathObservation(
                status = NetworkPathStatus.SATISFIED,
                isExpensive = false,
                isConstrained = false,
            ),
        )

        assertEquals(ConnectivityStatus.AVAILABLE, snapshot.status)
        assertEquals(false, snapshot.isMetered)
    }

    @Test
    fun `satisfied and expensive path is reported metered`() {
        val snapshot = classifyPath(
            NetworkPathObservation(
                status = NetworkPathStatus.SATISFIED,
                isExpensive = true,
                isConstrained = false,
            ),
        )

        assertEquals(ConnectivityStatus.AVAILABLE, snapshot.status)
        assertEquals(true, snapshot.isMetered)
    }

    @Test
    fun `satisfied and constrained path is reported metered`() {
        val snapshot = classifyPath(
            NetworkPathObservation(
                status = NetworkPathStatus.SATISFIED,
                isExpensive = false,
                isConstrained = true,
            ),
        )

        assertEquals(ConnectivityStatus.AVAILABLE, snapshot.status)
        assertEquals(true, snapshot.isMetered)
    }

    @Test
    fun `satisfiable path maps to LIMITED with unknown metering`() {
        val snapshot = classifyPath(
            NetworkPathObservation(
                status = NetworkPathStatus.SATISFIABLE,
                isExpensive = false,
                isConstrained = false,
            ),
        )

        assertEquals(ConnectivityStatus.LIMITED, snapshot.status)
        assertNull(snapshot.isMetered)
    }

    @Test
    fun `unsatisfied path maps to UNAVAILABLE with unknown metering`() {
        val snapshot = classifyPath(
            NetworkPathObservation(
                status = NetworkPathStatus.UNSATISFIED,
                isExpensive = true,
                isConstrained = true,
            ),
        )

        assertEquals(ConnectivityStatus.UNAVAILABLE, snapshot.status)
        assertNull(snapshot.isMetered)
    }

    @Test
    fun `invalid path maps to UNKNOWN with unknown metering`() {
        val snapshot = classifyPath(
            NetworkPathObservation(
                status = NetworkPathStatus.INVALID,
                isExpensive = false,
                isConstrained = false,
            ),
        )

        assertEquals(ConnectivityStatus.UNKNOWN, snapshot.status)
        assertNull(snapshot.isMetered)
    }
}
