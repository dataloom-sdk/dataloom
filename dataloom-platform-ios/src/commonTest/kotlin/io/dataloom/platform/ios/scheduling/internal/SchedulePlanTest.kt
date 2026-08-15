package io.dataloom.platform.ios.scheduling.internal

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests [planSchedule], the pure logic that translates a DataLoom schedule
 * request into a platform-independent [SchedulePlan] or a
 * [SchedulePlanRejection].
 *
 * This exercises only the classification rules -- it never constructs a
 * `BGTaskRequest` or touches `BGTaskScheduler`, and does not require an iOS
 * host, simulator, or device to type-check and compile. Like every other
 * Kotlin/Native test in this module, it compiles for the `iosArm64`,
 * `iosSimulatorArm64`, and `iosX64` targets but cannot be *executed* from a
 * Windows host -- see docs/apple/scheduler-provider.md for the exact
 * verification this slice ran instead.
 */
class SchedulePlanTest {

    private val preRegistered = setOf("io.dataloom.example.sync")

    @Test
    fun `unconstrained request plans an APP_REFRESH request`() {
        val result = planSchedule(
            identifier = "io.dataloom.example.sync",
            constraints = ScheduleConstraints(),
            delay = SchedulingDelay.ZERO,
            preRegisteredIdentifiers = preRegistered,
        )

        val supported = assertIs<SchedulePlanResult.Supported>(result)
        assertEquals(BGTaskRequestKind.APP_REFRESH, supported.plan.kind)
        assertEquals(false, supported.plan.requiresNetworkConnectivity)
        assertEquals(false, supported.plan.requiresExternalPower)
    }

    @Test
    fun `connectivity constraint plans a PROCESSING request requiring network`() {
        val result = planSchedule(
            identifier = "io.dataloom.example.sync",
            constraints = ScheduleConstraints(connectivity = ConnectivityRequirement.AVAILABLE),
            delay = SchedulingDelay.ZERO,
            preRegisteredIdentifiers = preRegistered,
        )

        val supported = assertIs<SchedulePlanResult.Supported>(result)
        assertEquals(BGTaskRequestKind.PROCESSING, supported.plan.kind)
        assertEquals(true, supported.plan.requiresNetworkConnectivity)
        assertEquals(false, supported.plan.requiresExternalPower)
    }

    @Test
    fun `charging constraint alone plans a PROCESSING request requiring power`() {
        val result = planSchedule(
            identifier = "io.dataloom.example.sync",
            constraints = ScheduleConstraints(requiresCharging = true),
            delay = SchedulingDelay.ZERO,
            preRegisteredIdentifiers = preRegistered,
        )

        val supported = assertIs<SchedulePlanResult.Supported>(result)
        assertEquals(BGTaskRequestKind.PROCESSING, supported.plan.kind)
        assertEquals(false, supported.plan.requiresNetworkConnectivity)
        assertEquals(true, supported.plan.requiresExternalPower)
    }

    @Test
    fun `delay is carried through unchanged`() {
        val result = planSchedule(
            identifier = "io.dataloom.example.sync",
            constraints = ScheduleConstraints(),
            delay = SchedulingDelay(90_000L),
            preRegisteredIdentifiers = preRegistered,
        )

        val supported = assertIs<SchedulePlanResult.Supported>(result)
        assertEquals(90_000L, supported.plan.delayMilliseconds)
    }

    @Test
    fun `unmetered connectivity is rejected as unsupported`() {
        val result = planSchedule(
            identifier = "io.dataloom.example.sync",
            constraints = ScheduleConstraints(connectivity = ConnectivityRequirement.UNMETERED),
            delay = SchedulingDelay.ZERO,
            preRegisteredIdentifiers = preRegistered,
        )

        val rejected = assertIs<SchedulePlanResult.Rejected>(result)
        assertEquals(SchedulePlanRejection.UNSUPPORTED_UNMETERED_CONNECTIVITY, rejected.reason)
    }

    @Test
    fun `identifier outside the pre-registered set is rejected`() {
        val result = planSchedule(
            identifier = "io.dataloom.example.unregistered",
            constraints = ScheduleConstraints(),
            delay = SchedulingDelay.ZERO,
            preRegisteredIdentifiers = preRegistered,
        )

        val rejected = assertIs<SchedulePlanResult.Rejected>(result)
        assertEquals(SchedulePlanRejection.IDENTIFIER_NOT_PREREGISTERED, rejected.reason)
    }

    @Test
    fun `pre-registration is checked before the unmetered connectivity check`() {
        val result = planSchedule(
            identifier = "io.dataloom.example.unregistered",
            constraints = ScheduleConstraints(connectivity = ConnectivityRequirement.UNMETERED),
            delay = SchedulingDelay.ZERO,
            preRegisteredIdentifiers = preRegistered,
        )

        val rejected = assertIs<SchedulePlanResult.Rejected>(result)
        assertEquals(SchedulePlanRejection.IDENTIFIER_NOT_PREREGISTERED, rejected.reason)
    }
}
