package io.dataloom.platform.ios.scheduling.internal

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Which concrete `BGTaskRequest` subclass a [SchedulePlan] should be
 * submitted as.
 *
 * - [APP_REFRESH] backs `BGAppRefreshTaskRequest`, Apple's lightweight,
 *   short-running background-refresh mechanism. Chosen when the schedule
 *   declares no connectivity requirement and does not require charging.
 * - [PROCESSING] backs `BGProcessingTaskRequest`, the only `BGTaskRequest`
 *   subclass exposing `requiresNetworkConnectivity` / `requiresExternalPower`.
 *   Chosen whenever the schedule declares a connectivity or charging
 *   constraint.
 */
internal enum class BGTaskRequestKind {
    APP_REFRESH,
    PROCESSING,
}

/**
 * Platform-independent plan for one `BGTaskRequest` submission.
 *
 * Carries only primitive fields -- never a `BGTaskRequest`, `NSDate`, or
 * other platform handle. See `internal/BackgroundTaskGateway.kt` (`actual`,
 * `iosMain`) for the one place this module builds a concrete
 * `BGAppRefreshTaskRequest` / `BGProcessingTaskRequest` from a [SchedulePlan].
 */
internal data class SchedulePlan(
    val identifier: String,
    val kind: BGTaskRequestKind,
    val requiresNetworkConnectivity: Boolean,
    val requiresExternalPower: Boolean,
    val delayMilliseconds: Long,
)

/** Closed set of reasons [planSchedule] rejects a request before touching the platform. */
internal enum class SchedulePlanRejection {
    /**
     * [ConnectivityRequirement.UNMETERED] was requested.
     *
     * `BGProcessingTaskRequest` exposes only a boolean
     * `requiresNetworkConnectivity` flag -- there is no `BGTaskScheduler` API
     * to require specifically-unmetered (non-cellular) connectivity. Honoring
     * this constraint by silently downgrading it to
     * `requiresNetworkConnectivity = true` would let a schedule run over a
     * metered connection while claiming an unmetered guarantee, so this
     * combination is rejected instead of silently under-enforced.
     */
    UNSUPPORTED_UNMETERED_CONNECTIVITY,

    /**
     * The requested [io.dataloom.api.identifier.ScheduleId] is not present in
     * the set of identifiers the host application declared as pre-registered
     * at construction time (see [io.dataloom.platform.ios.scheduling.AppleSchedulerProvider]).
     *
     * `BGTaskScheduler` requires every task identifier to be listed in the
     * host app's `Info.plist` under `BGTaskSchedulerPermittedIdentifiers` and
     * registered via `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)`
     * before `applicationDidFinishLaunching` returns. DataLoom cannot perform
     * either step for the host app, so an identifier the host never declared
     * is rejected here rather than forwarded to the platform, where it would
     * otherwise surface only as an opaque `NSError`.
     */
    IDENTIFIER_NOT_PREREGISTERED,
}

/** Result of [planSchedule]: either a submittable [SchedulePlan], or a [SchedulePlanRejection]. */
internal sealed class SchedulePlanResult {
    internal data class Supported(val plan: SchedulePlan) : SchedulePlanResult()
    internal data class Rejected(val reason: SchedulePlanRejection) : SchedulePlanResult()
}

/**
 * Pure translation from a DataLoom schedule request into a
 * platform-independent [SchedulePlan], or a [SchedulePlanRejection] when the
 * request cannot be honestly represented on `BGTaskScheduler`.
 *
 * Never touches `BGTaskScheduler`, `BGTaskRequest`, `NSDate`, or any other
 * platform type, and never queries pending requests -- it is a pure function
 * of its arguments, unit-tested directly in `commonTest`.
 *
 * @param identifier the [io.dataloom.api.identifier.ScheduleId.value] this
 *   schedule should be submitted under.
 * @param constraints the request's [ScheduleConstraints].
 * @param delay the request's [SchedulingDelay].
 * @param preRegisteredIdentifiers identifiers the host application declared
 *   as pre-registered with `BGTaskScheduler` at construction time.
 */
internal fun planSchedule(
    identifier: String,
    constraints: ScheduleConstraints,
    delay: SchedulingDelay,
    preRegisteredIdentifiers: Set<String>,
): SchedulePlanResult {
    if (identifier !in preRegisteredIdentifiers) {
        return SchedulePlanResult.Rejected(SchedulePlanRejection.IDENTIFIER_NOT_PREREGISTERED)
    }
    if (constraints.connectivity == ConnectivityRequirement.UNMETERED) {
        return SchedulePlanResult.Rejected(SchedulePlanRejection.UNSUPPORTED_UNMETERED_CONNECTIVITY)
    }

    val requiresNetworkConnectivity = constraints.connectivity != ConnectivityRequirement.NONE
    val kind = if (requiresNetworkConnectivity || constraints.requiresCharging) {
        BGTaskRequestKind.PROCESSING
    } else {
        BGTaskRequestKind.APP_REFRESH
    }

    return SchedulePlanResult.Supported(
        SchedulePlan(
            identifier = identifier,
            kind = kind,
            requiresNetworkConnectivity = requiresNetworkConnectivity,
            requiresExternalPower = constraints.requiresCharging,
            delayMilliseconds = delay.milliseconds,
        ),
    )
}
