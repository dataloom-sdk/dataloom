package io.dataloom.plugin.testkit

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginDependency
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginPermission
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import io.dataloom.api.plugin.PluginVersionRange
import io.dataloom.api.security.Capability
import io.dataloom.api.security.GrantedCapabilities
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.plugin.PluginDependencyIssueReason
import io.dataloom.plugin.PluginExecutionBoundsEnforcer
import io.dataloom.plugin.PluginExecutionBoundsResult
import io.dataloom.plugin.PluginFailureCircuitPolicy
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizationDecision
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizer
import io.dataloom.plugin.PluginLifecycleAdministrationCommandId
import io.dataloom.plugin.PluginLifecycleAdministrationPrincipalId
import io.dataloom.plugin.PluginLifecycleAdministrationReason
import io.dataloom.plugin.PluginLifecycleStateTracker
import io.dataloom.plugin.PluginLifecycleTransitionRequest
import io.dataloom.plugin.PluginLifecycleTransitionResult
import io.dataloom.plugin.PluginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Framework-neutral behavioural certification suite for DataLoom's plugin
 * platform (`#98`, DL-044) -- the plugin-platform counterpart of
 * `dataloom-assets`'s `AssetProviderContractKit`, matching its shape: [run]
 * executes every scenario against a fresh [DataLoomPlugin] built by
 * [pluginFactory] and the real `dataloom-plugin` engine, and returns a
 * [PluginCertificationReport]; a plugin's own test asserts the report (see
 * [PluginCertificationReport.assertAllPassed]). The kit uses no test
 * framework, so it works under any test runner on every platform.
 *
 * ## What is certified, and by what
 *
 * Unlike `AssetProviderContractKit`, where the swappable implementation
 * under test (`AssetProvider`) has real behavioural methods the kit calls
 * directly, [DataLoomPlugin] is deliberately a pure identity-and-bounds
 * shape (see its own KDoc) with no lifecycle callback to invoke. This kit
 * therefore certifies a [DataLoomPlugin] implementation by wiring the real,
 * unmodified [PluginRegistry] / [PluginLifecycleStateTracker] /
 * [PluginExecutionBoundsEnforcer] engine around it and asserting the
 * documented outcomes -- the lifecycle transition graph
 * (`io.dataloom.plugin.PluginLifecycleTransitions`), dependency/version
 * gating, execution-bounds enforcement, and permission-gated activation all
 * come from `docs/api/plugin-registry.md` and this gate's own shipped source,
 * not from this kit's own invention.
 *
 * The plugin platform's other genuinely swappable extension point --
 * [PluginLifecycleAdministrationAuthorizer], the host-supplied "authorized hot
 * disable" boundary -- is certified the same way: [authorizedAuthorizerFactory]
 * and [deniedAuthorizerFactory] default to simple built-in authorizers so a
 * caller certifying only a plugin need not supply either, but a host wanting
 * to certify its *own* [PluginLifecycleAdministrationAuthorizer]
 * implementation overrides them.
 *
 * ## Scenario categories
 *
 * - Lifecycle transitions: every edge
 *   `io.dataloom.plugin.PluginLifecycleTransitions` documents as legal
 *   succeeds and updates tracked state; every other ordered pair of states,
 *   including same-state requests, is structurally rejected and leaves
 *   tracked state unchanged.
 * - Dependency/version-gated activation: a dependency in range, below its
 *   minimum, above its maximum, missing (unregistered), disabled, or merely
 *   not yet `ACTIVE` each produce the documented
 *   [PluginLifecycleTransitionResult.DependencyUnsatisfied] (or, once
 *   satisfied, [PluginLifecycleTransitionResult.Allowed]).
 * - Execution-bounds enforcement: a non-`ACTIVE` plugin's invocation is
 *   refused without running the operation; a call beyond the declared
 *   concurrency ceiling is rejected as a fail-fast bulkhead without running
 *   the operation; an operation that overruns its declared timeout is
 *   cancelled and reported, not left to hang; an operation that throws is
 *   contained as [PluginExecutionBoundsResult.Failed], frees its slot, and
 *   does not affect another plugin; with an opt-in
 *   [io.dataloom.plugin.PluginFailureCircuitPolicy], consecutive failures
 *   degrade the plugin (a completed invocation resets the count) and it
 *   recovers only through a manual transition.
 * - Authorized-transition gating: an authorized request applies a
 *   structurally legal transition; a denied request leaves tracked state
 *   unchanged and reports the authorizer's own reason code; a structurally
 *   illegal transition is rejected before the authorizer is ever consulted.
 * - Permission-gated activation: every declared permission granted allows
 *   entry into `ACTIVE`; a missing declared permission denies entry into
 *   `ACTIVE`, leaves tracked state unchanged, and names exactly the missing
 *   permission.
 *
 * @param sdkVersion the running SDK version every scenario's plugins are
 *   checked against. Defaults to a version compatible with every manifest
 *   this kit itself builds.
 * @param pluginFactory builds the implementation under certification from a
 *   manifest and bounds this kit constructs per scenario. Called once (or
 *   more, for scenarios needing more than one plugin) per scenario, exactly
 *   as `AssetProviderContractKit`'s own `providerFactory` is called once per
 *   scenario.
 * @param authorizedAuthorizerFactory builds the
 *   [PluginLifecycleAdministrationAuthorizer] the "authorized-transition
 *   gating" scenarios use for a request that must be allowed. Defaults to an
 *   authorizer that always authorizes.
 * @param deniedAuthorizerFactory builds the
 *   [PluginLifecycleAdministrationAuthorizer] the "authorized-transition
 *   gating" scenarios use for a request that must be denied, given the
 *   [PluginLifecycleAdministrationAuthorizationDecision.Denied.reasonCode] the
 *   scenario expects back unchanged. Defaults to an authorizer that always
 *   denies with that reason code.
 */
public class PluginCertificationKit(
    private val sdkVersion: RuntimeVersion = RuntimeVersion("1.0.0"),
    private val pluginFactory: (PluginManifest, PluginExecutionBounds) -> DataLoomPlugin,
    private val authorizedAuthorizerFactory: () -> PluginLifecycleAdministrationAuthorizer = { AlwaysAuthorizeAuthorizer },
    private val deniedAuthorizerFactory: (String) -> PluginLifecycleAdministrationAuthorizer = { reasonCode ->
        AlwaysDenyAuthorizer(reasonCode)
    },
) {

    /** Runs every scenario and reports each one's outcome. Never throws for a scenario failure. */
    public suspend fun run(): PluginCertificationReport {
        val results = scenarios.map { (name, scenario) ->
            val failure = try {
                scenario(Fixture())
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            } catch (e: AssertionError) {
                e
            }
            PluginCertificationReport.Result(name, failure)
        }
        return PluginCertificationReport(results)
    }

    private inner class Fixture {
        private val vendor = PluginVendor("plugin-certification-kit")

        fun compatibleRange(): PluginCompatibilityRange = PluginCompatibilityRange(minimumSdkVersion = sdkVersion)

        fun manifest(
            id: String,
            version: String = "1.0.0",
            range: PluginCompatibilityRange = compatibleRange(),
            permissions: Set<PluginPermission> = emptySet(),
            dependencies: Set<PluginDependency> = emptySet(),
        ): PluginManifest = PluginManifest(
            id = PluginId(id),
            version = PluginVersion(version),
            vendor = vendor,
            compatibleSdkRange = range,
            permissions = permissions,
            dependencies = dependencies,
        )

        fun bounds(maximumExecutionMillis: Long = 5_000L, maximumConcurrentInvocations: Int = 1): PluginExecutionBounds =
            PluginExecutionBounds(maximumExecutionMillis, maximumConcurrentInvocations)

        fun plugin(manifest: PluginManifest, bounds: PluginExecutionBounds = bounds()): DataLoomPlugin =
            pluginFactory(manifest, bounds)

        fun trackerOver(vararg plugins: DataLoomPlugin): PluginLifecycleStateTracker =
            PluginLifecycleStateTracker(PluginRegistry(plugins.toList()), sdkVersion)

        /** Drives [id] from `LOADED` through the documented legal path to [state]. */
        fun driveTo(tracker: PluginLifecycleStateTracker, id: PluginId, state: PluginLifecycleState) {
            for (step in pathTo(state)) {
                val result = tracker.transition(id, step)
                check(result is PluginLifecycleTransitionResult.Allowed) {
                    "PluginCertificationKit fixture setup could not drive '$id' to $step: $result"
                }
            }
        }

        fun request(id: PluginId, target: PluginLifecycleState): PluginLifecycleTransitionRequest =
            PluginLifecycleTransitionRequest(
                commandId = PluginLifecycleAdministrationCommandId("certify-${id.value}-$target"),
                pluginId = id,
                target = target,
                principalId = PluginLifecycleAdministrationPrincipalId("plugin-certification-kit"),
                requestedAt = DataLoomInstant(0L),
                reason = PluginLifecycleAdministrationReason("plugin certification kit scenario"),
            )
    }

    private val scenarios: List<Pair<String, suspend (Fixture) -> Unit>> = listOf(

        // ---------------------------------------------------------------
        // Lifecycle transitions
        // ---------------------------------------------------------------

        "every legal transition in the documented lifecycle graph succeeds and updates tracked state" to { f ->
            for ((from, target) in legalEdges) {
                val id = PluginId("lifecycle-legal-$from-$target")
                val tracker = f.trackerOver(f.plugin(f.manifest(id.value)))
                f.driveTo(tracker, id, from)
                val result = tracker.transition(id, target)
                require(result is PluginLifecycleTransitionResult.Allowed) {
                    "legal transition $from -> $target was not Allowed: $result"
                }
                require(tracker.stateOf(id) == target) {
                    "legal transition $from -> $target did not update tracked state"
                }
            }
        },

        "every undocumented transition, including same-state requests, is structurally rejected and leaves tracked state unchanged" to { f ->
            for (from in allStates) {
                for (target in allStates) {
                    if (Pair(from, target) in legalEdges) continue
                    val id = PluginId("lifecycle-illegal-$from-$target")
                    val tracker = f.trackerOver(f.plugin(f.manifest(id.value)))
                    f.driveTo(tracker, id, from)
                    val result = tracker.transition(id, target)
                    require(result is PluginLifecycleTransitionResult.Rejected) {
                        "undocumented transition $from -> $target was not Rejected: $result"
                    }
                    require(tracker.stateOf(id) == from) {
                        "undocumented transition $from -> $target changed tracked state to ${tracker.stateOf(id)}"
                    }
                }
            }
        },

        // ---------------------------------------------------------------
        // Dependency / version-gated activation
        // ---------------------------------------------------------------

        "a dependency within its declared version range allows validation, and once it is ACTIVE, allows activation" to { f ->
            val depId = PluginId("dep-in-range")
            val dependentId = PluginId("dependent-in-range")
            val dep = f.plugin(f.manifest(depId.value, version = "2.0.0"))
            val dependent = f.plugin(
                f.manifest(
                    dependentId.value,
                    dependencies = setOf(
                        PluginDependency(depId, PluginVersionRange(PluginVersion("1.0.0"), PluginVersion("3.0.0"))),
                    ),
                ),
            )
            val tracker = f.trackerOver(dep, dependent)

            val validated = tracker.transition(dependentId, PluginLifecycleState.VALIDATED)
            require(validated is PluginLifecycleTransitionResult.Allowed) {
                "an in-range dependency blocked VALIDATED: $validated"
            }
            tracker.transition(dependentId, PluginLifecycleState.INITIALIZING)

            val blockedByNotActive = tracker.transition(dependentId, PluginLifecycleState.ACTIVE)
            require(blockedByNotActive is PluginLifecycleTransitionResult.DependencyUnsatisfied) {
                "dependent reached ACTIVE while its own dependency was not yet ACTIVE: $blockedByNotActive"
            }
            require(blockedByNotActive.issues.single().reason == PluginDependencyIssueReason.NOT_ACTIVE) {
                "wrong dependency issue reason: ${blockedByNotActive.issues}"
            }

            f.driveTo(tracker, depId, PluginLifecycleState.ACTIVE)
            val activated = tracker.transition(dependentId, PluginLifecycleState.ACTIVE)
            require(activated is PluginLifecycleTransitionResult.Allowed) {
                "dependent failed to activate once its dependency was ACTIVE: $activated"
            }
        },

        "a dependency below its declared minimum version blocks validation with VERSION_BELOW_MINIMUM" to { f ->
            val depId = PluginId("dep-too-old")
            val dependentId = PluginId("dependent-needs-newer")
            val dep = f.plugin(f.manifest(depId.value, version = "1.0.0"))
            val dependent = f.plugin(
                f.manifest(
                    dependentId.value,
                    dependencies = setOf(PluginDependency(depId, PluginVersionRange(PluginVersion("2.0.0")))),
                ),
            )
            val tracker = f.trackerOver(dep, dependent)

            val result = tracker.transition(dependentId, PluginLifecycleState.VALIDATED)
            require(result is PluginLifecycleTransitionResult.DependencyUnsatisfied) {
                "an out-of-range (too old) dependency did not block VALIDATED: $result"
            }
            require(result.issues.single().reason == PluginDependencyIssueReason.VERSION_BELOW_MINIMUM) {
                "wrong dependency issue reason: ${result.issues}"
            }
        },

        "a dependency above its declared maximum version blocks validation with VERSION_ABOVE_MAXIMUM" to { f ->
            val depId = PluginId("dep-too-new")
            val dependentId = PluginId("dependent-needs-older")
            val dep = f.plugin(f.manifest(depId.value, version = "5.0.0"))
            val dependent = f.plugin(
                f.manifest(
                    dependentId.value,
                    dependencies = setOf(
                        PluginDependency(depId, PluginVersionRange(PluginVersion("1.0.0"), PluginVersion("3.0.0"))),
                    ),
                ),
            )
            val tracker = f.trackerOver(dep, dependent)

            val result = tracker.transition(dependentId, PluginLifecycleState.VALIDATED)
            require(result is PluginLifecycleTransitionResult.DependencyUnsatisfied) {
                "an out-of-range (too new) dependency did not block VALIDATED: $result"
            }
            require(result.issues.single().reason == PluginDependencyIssueReason.VERSION_ABOVE_MAXIMUM) {
                "wrong dependency issue reason: ${result.issues}"
            }
        },

        "a missing, unregistered dependency blocks validation with NOT_REGISTERED" to { f ->
            val dependentId = PluginId("dependent-on-missing")
            val dependent = f.plugin(
                f.manifest(
                    dependentId.value,
                    dependencies = setOf(
                        PluginDependency(PluginId("nonexistent"), PluginVersionRange(PluginVersion("1.0.0"))),
                    ),
                ),
            )
            val tracker = f.trackerOver(dependent)

            val result = tracker.transition(dependentId, PluginLifecycleState.VALIDATED)
            require(result is PluginLifecycleTransitionResult.DependencyUnsatisfied) {
                "a dependency on an unregistered plugin did not block VALIDATED: $result"
            }
            require(result.issues.single().reason == PluginDependencyIssueReason.NOT_REGISTERED) {
                "wrong dependency issue reason: ${result.issues}"
            }
        },

        "a disabled dependency blocks validation with DISABLED" to { f ->
            val depId = PluginId("dep-disabled")
            val dependentId = PluginId("dependent-on-disabled")
            val dep = f.plugin(f.manifest(depId.value))
            val dependent = f.plugin(
                f.manifest(
                    dependentId.value,
                    dependencies = setOf(PluginDependency(depId, PluginVersionRange(PluginVersion("1.0.0")))),
                ),
            )
            val tracker = f.trackerOver(dep, dependent)
            tracker.transition(depId, PluginLifecycleState.DISABLED)

            val result = tracker.transition(dependentId, PluginLifecycleState.VALIDATED)
            require(result is PluginLifecycleTransitionResult.DependencyUnsatisfied) {
                "a disabled dependency did not block VALIDATED: $result"
            }
            require(result.issues.single().reason == PluginDependencyIssueReason.DISABLED) {
                "wrong dependency issue reason: ${result.issues}"
            }
        },

        // ---------------------------------------------------------------
        // Execution-bounds enforcement
        // ---------------------------------------------------------------

        "execute refuses a plugin that is not ACTIVE, without invoking the operation" to { f ->
            val id = PluginId("bounds-not-active")
            val tracker = f.trackerOver(f.plugin(f.manifest(id.value), f.bounds(maximumConcurrentInvocations = 3)))
            val enforcer = PluginExecutionBoundsEnforcer(tracker)
            var invoked = false

            val result = enforcer.execute(id) { invoked = true }

            require(result is PluginExecutionBoundsResult.NotActive) {
                "a non-ACTIVE plugin's invocation was not refused: $result"
            }
            require(result.state == PluginLifecycleState.LOADED) { "wrong observed state: ${result.state}" }
            require(!invoked) { "the operation ran despite the plugin not being ACTIVE" }
        },

        "execute enforces the declared concurrency ceiling as a fail-fast bulkhead, without invoking the rejected call" to { f ->
            val id = PluginId("bounds-bulkhead")
            val tracker = f.trackerOver(f.plugin(f.manifest(id.value), f.bounds(maximumConcurrentInvocations = 1)))
            f.driveTo(tracker, id, PluginLifecycleState.ACTIVE)
            val enforcer = PluginExecutionBoundsEnforcer(tracker)

            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val inFlight = launch {
                    enforcer.execute(id) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
                started.await()

                var rejectedCallInvoked = false
                val rejected = enforcer.execute(id) { rejectedCallInvoked = true }

                require(rejected is PluginExecutionBoundsResult.ConcurrencyLimitExceeded) {
                    "a call beyond the declared concurrency ceiling was not rejected: $rejected"
                }
                require(!rejectedCallInvoked) { "the rejected call's operation ran anyway" }
                inFlight.cancel()
            }
        },

        "execute cancels an operation that overruns its declared timeout and reports TimedOut" to { f ->
            val id = PluginId("bounds-timeout")
            val tracker = f.trackerOver(
                f.plugin(f.manifest(id.value), f.bounds(maximumExecutionMillis = 50L, maximumConcurrentInvocations = 1)),
            )
            f.driveTo(tracker, id, PluginLifecycleState.ACTIVE)
            val enforcer = PluginExecutionBoundsEnforcer(tracker)

            val result = enforcer.execute(id) {
                awaitCancellation()
            }

            require(result is PluginExecutionBoundsResult.TimedOut) {
                "an operation overrunning its declared timeout was not cancelled and reported: $result"
            }
            require(result.maximumExecutionMillis == 50L) { "wrong reported timeout: ${result.maximumExecutionMillis}" }
        },

        "execute contains an exception thrown by the operation as Failed, frees its slot, and leaves other plugins unaffected" to { f ->
            val failingId = PluginId("bounds-failing")
            val healthyId = PluginId("bounds-healthy")
            val tracker = f.trackerOver(
                f.plugin(f.manifest(failingId.value), f.bounds(maximumConcurrentInvocations = 1)),
                f.plugin(f.manifest(healthyId.value), f.bounds(maximumConcurrentInvocations = 1)),
            )
            f.driveTo(tracker, failingId, PluginLifecycleState.ACTIVE)
            f.driveTo(tracker, healthyId, PluginLifecycleState.ACTIVE)
            val enforcer = PluginExecutionBoundsEnforcer(tracker)

            val failed = enforcer.execute<Unit>(failingId) { throw IllegalStateException("plugin failure") }

            require(failed is PluginExecutionBoundsResult.Failed) {
                "an exception thrown by the operation was not contained: $failed"
            }
            require(failed.pluginId == failingId) { "wrong plugin reported: ${failed.pluginId}" }
            // Message, not identity: JVM coroutine stack-trace recovery may substitute a copy.
            require(failed.cause.message == "plugin failure") {
                "the thrown exception was not reported as the cause: ${failed.cause}"
            }

            val retried = enforcer.execute(failingId) { "again" }
            require(retried is PluginExecutionBoundsResult.Completed && retried.value == "again") {
                "a failed invocation leaked its concurrency slot: $retried"
            }
            val other = enforcer.execute(healthyId) { "unaffected" }
            require(other is PluginExecutionBoundsResult.Completed && other.value == "unaffected") {
                "another plugin was affected by the failing plugin: $other"
            }
            require(tracker.stateOf(failingId) == PluginLifecycleState.ACTIVE) {
                "a contained failure changed the failing plugin's lifecycle state"
            }
        },

        "a failure circuit degrades a repeatedly failing plugin, refuses it, and recovers only through a manual transition" to { f ->
            val id = PluginId("circuit-trips")
            val tracker = f.trackerOver(f.plugin(f.manifest(id.value), f.bounds(maximumConcurrentInvocations = 1)))
            f.driveTo(tracker, id, PluginLifecycleState.ACTIVE)
            val enforcer = PluginExecutionBoundsEnforcer(tracker, PluginFailureCircuitPolicy(consecutiveFailureThreshold = 2))

            val first = enforcer.execute<Unit>(id) { throw IllegalStateException("plugin failure") }
            require(first is PluginExecutionBoundsResult.Failed && !first.degradedPlugin) {
                "the first failure should be reported without degrading the plugin: $first"
            }
            require(tracker.stateOf(id) == PluginLifecycleState.ACTIVE) { "the plugin was degraded below its threshold" }

            val second = enforcer.execute<Unit>(id) { throw IllegalStateException("plugin failure") }
            require(second is PluginExecutionBoundsResult.Failed && second.degradedPlugin) {
                "the failure reaching the threshold did not report the degradation: $second"
            }
            require(tracker.stateOf(id) == PluginLifecycleState.DEGRADED) { "the plugin was not degraded: ${tracker.stateOf(id)}" }

            var invoked = false
            val refused = enforcer.execute(id) { invoked = true }
            require(refused is PluginExecutionBoundsResult.NotActive && !invoked) {
                "a degraded plugin was not refused without running: $refused"
            }

            val recovery = tracker.transition(id, PluginLifecycleState.ACTIVE)
            require(recovery is PluginLifecycleTransitionResult.Allowed) { "manual recovery was refused: $recovery" }
            val afterRecovery = enforcer.execute(id) { "again" }
            require(afterRecovery is PluginExecutionBoundsResult.Completed && afterRecovery.value == "again") {
                "a recovered plugin was not admitted again: $afterRecovery"
            }
        },

        "a failure circuit counts only consecutive failures: a completed invocation resets the count" to { f ->
            val id = PluginId("circuit-resets")
            val tracker = f.trackerOver(f.plugin(f.manifest(id.value), f.bounds(maximumConcurrentInvocations = 1)))
            f.driveTo(tracker, id, PluginLifecycleState.ACTIVE)
            val enforcer = PluginExecutionBoundsEnforcer(tracker, PluginFailureCircuitPolicy(consecutiveFailureThreshold = 2))

            enforcer.execute<Unit>(id) { throw IllegalStateException("plugin failure") }
            enforcer.execute(id) { "ok" }
            enforcer.execute<Unit>(id) { throw IllegalStateException("plugin failure") }

            require(tracker.stateOf(id) == PluginLifecycleState.ACTIVE) {
                "non-consecutive failures degraded the plugin: ${tracker.stateOf(id)}"
            }
        },

        // ---------------------------------------------------------------
        // Authorized-transition gating ("authorized hot disable")
        // ---------------------------------------------------------------

        "an authorized request applies a structurally legal transition" to { f ->
            val id = PluginId("auth-allow")
            val tracker = f.trackerOver(f.plugin(f.manifest(id.value)))
            val authorizer = authorizedAuthorizerFactory()

            val result = tracker.transition(f.request(id, PluginLifecycleState.VALIDATED), authorizer)

            require(result is PluginLifecycleTransitionResult.Allowed) {
                "an authorized request did not apply the transition: $result"
            }
            require(tracker.stateOf(id) == PluginLifecycleState.VALIDATED) { "tracked state was not updated" }
        },

        "a denied request leaves tracked state unchanged and reports the authorizer's own reason code" to { f ->
            val id = PluginId("auth-deny")
            val tracker = f.trackerOver(f.plugin(f.manifest(id.value)))
            val reasonCode = "NOT_AN_OPERATOR"
            val authorizer = deniedAuthorizerFactory(reasonCode)

            val result = tracker.transition(f.request(id, PluginLifecycleState.DISABLED), authorizer)

            require(result is PluginLifecycleTransitionResult.AuthorizationDenied) {
                "a denied request did not block the transition: $result"
            }
            require(result.reasonCode == reasonCode) { "wrong reason code: ${result.reasonCode}" }
            require(tracker.stateOf(id) == PluginLifecycleState.LOADED) {
                "tracked state changed despite the authorizer denying the request"
            }
        },

        "a structurally illegal transition is rejected before the authorizer is ever consulted" to { f ->
            val id = PluginId("auth-illegal")
            val tracker = f.trackerOver(f.plugin(f.manifest(id.value)))
            val authorizer = object : PluginLifecycleAdministrationAuthorizer {
                override suspend fun authorize(
                    request: PluginLifecycleTransitionRequest,
                ): PluginLifecycleAdministrationAuthorizationDecision =
                    throw AssertionError("the authorizer must not be consulted for a structurally illegal transition")
            }

            val result = tracker.transition(f.request(id, PluginLifecycleState.ACTIVE), authorizer)

            require(result is PluginLifecycleTransitionResult.Rejected) {
                "a structurally illegal transition was not rejected: $result"
            }
            require(tracker.stateOf(id) == PluginLifecycleState.LOADED) { "tracked state changed" }
        },

        // ---------------------------------------------------------------
        // Permission-gated activation
        // ---------------------------------------------------------------

        "every declared permission granted allows entry into ACTIVE" to { f ->
            val id = PluginId("perm-granted")
            val tracker = f.trackerOver(
                f.plugin(f.manifest(id.value, permissions = setOf(PluginPermission("storage.read")))),
            )
            val granted = GrantedCapabilities.of(setOf(Capability("storage.read")))
            tracker.transition(id, PluginLifecycleState.VALIDATED, granted)
            tracker.transition(id, PluginLifecycleState.INITIALIZING, granted)

            val result = tracker.transition(id, PluginLifecycleState.ACTIVE, granted)

            require(result is PluginLifecycleTransitionResult.Allowed) {
                "activation was denied despite every declared permission being granted: $result"
            }
        },

        "a missing declared permission denies entry into ACTIVE, leaves state unchanged, and names exactly the missing permission" to { f ->
            val id = PluginId("perm-missing")
            val tracker = f.trackerOver(
                f.plugin(
                    f.manifest(
                        id.value,
                        permissions = setOf(PluginPermission("storage.read"), PluginPermission("network.push")),
                    ),
                ),
            )
            val granted = GrantedCapabilities.of(setOf(Capability("storage.read")))
            tracker.transition(id, PluginLifecycleState.VALIDATED, granted)
            tracker.transition(id, PluginLifecycleState.INITIALIZING, granted)

            val result = tracker.transition(id, PluginLifecycleState.ACTIVE, granted)

            require(result is PluginLifecycleTransitionResult.PermissionDenied) {
                "activation was allowed despite a missing declared permission: $result"
            }
            require(result.missingPermissions == setOf(PluginPermission("network.push"))) {
                "wrong missing permissions reported: ${result.missingPermissions}"
            }
            require(tracker.stateOf(id) == PluginLifecycleState.INITIALIZING) {
                "tracked state changed despite the permission denial"
            }
        },
    )

    private companion object {
        private val allStates: List<PluginLifecycleState> = PluginLifecycleState.values().toList()

        /**
         * The exact graph `io.dataloom.plugin.PluginLifecycleTransitions` documents as
         * legal -- duplicated here deliberately, as the certification kit's own
         * independent statement of the documented contract, the same way
         * `AssetProviderContractKit` encodes its own expectations about
         * `AssetErrorKind` rather than reaching into provider internals.
         */
        private val legalEdges: Set<Pair<PluginLifecycleState, PluginLifecycleState>> = setOf(
            PluginLifecycleState.LOADED to PluginLifecycleState.VALIDATED,
            PluginLifecycleState.LOADED to PluginLifecycleState.DISABLED,
            PluginLifecycleState.VALIDATED to PluginLifecycleState.INITIALIZING,
            PluginLifecycleState.VALIDATED to PluginLifecycleState.DISABLED,
            PluginLifecycleState.INITIALIZING to PluginLifecycleState.ACTIVE,
            PluginLifecycleState.INITIALIZING to PluginLifecycleState.DISABLED,
            PluginLifecycleState.ACTIVE to PluginLifecycleState.DEGRADED,
            PluginLifecycleState.ACTIVE to PluginLifecycleState.DISABLED,
            PluginLifecycleState.DEGRADED to PluginLifecycleState.ACTIVE,
            PluginLifecycleState.DEGRADED to PluginLifecycleState.DISABLED,
            PluginLifecycleState.DISABLED to PluginLifecycleState.UNLOADED,
        )

        /** The documented legal path from `LOADED` to [state], exclusive of `LOADED` itself. */
        private fun pathTo(state: PluginLifecycleState): List<PluginLifecycleState> = when (state) {
            PluginLifecycleState.LOADED -> emptyList()
            PluginLifecycleState.VALIDATED -> listOf(PluginLifecycleState.VALIDATED)
            PluginLifecycleState.INITIALIZING -> listOf(PluginLifecycleState.VALIDATED, PluginLifecycleState.INITIALIZING)
            PluginLifecycleState.ACTIVE -> listOf(
                PluginLifecycleState.VALIDATED,
                PluginLifecycleState.INITIALIZING,
                PluginLifecycleState.ACTIVE,
            )
            PluginLifecycleState.DEGRADED -> listOf(
                PluginLifecycleState.VALIDATED,
                PluginLifecycleState.INITIALIZING,
                PluginLifecycleState.ACTIVE,
                PluginLifecycleState.DEGRADED,
            )
            PluginLifecycleState.DISABLED -> listOf(
                PluginLifecycleState.VALIDATED,
                PluginLifecycleState.INITIALIZING,
                PluginLifecycleState.ACTIVE,
                PluginLifecycleState.DISABLED,
            )
            PluginLifecycleState.UNLOADED -> listOf(
                PluginLifecycleState.VALIDATED,
                PluginLifecycleState.INITIALIZING,
                PluginLifecycleState.ACTIVE,
                PluginLifecycleState.DISABLED,
                PluginLifecycleState.UNLOADED,
            )
        }
    }
}

/** Built-in [PluginLifecycleAdministrationAuthorizer] that authorizes every request. */
private object AlwaysAuthorizeAuthorizer : PluginLifecycleAdministrationAuthorizer {
    override suspend fun authorize(
        request: PluginLifecycleTransitionRequest,
    ): PluginLifecycleAdministrationAuthorizationDecision = PluginLifecycleAdministrationAuthorizationDecision.Authorized
}

/** Built-in [PluginLifecycleAdministrationAuthorizer] that denies every request with a fixed reason code. */
private class AlwaysDenyAuthorizer(private val reasonCode: String) : PluginLifecycleAdministrationAuthorizer {
    override suspend fun authorize(
        request: PluginLifecycleTransitionRequest,
    ): PluginLifecycleAdministrationAuthorizationDecision =
        PluginLifecycleAdministrationAuthorizationDecision.Denied(reasonCode)
}

/** Outcome of a [PluginCertificationKit.run]. */
public class PluginCertificationReport internal constructor(public val results: List<Result>) {

    /** One scenario's outcome; [failure] is `null` when it passed. */
    public class Result(public val name: String, public val failure: Throwable?) {
        /** `true` if the scenario passed. */
        public val passed: Boolean get() = failure == null
    }

    /** Results of the scenarios that failed. */
    public val failures: List<Result> get() = results.filter { !it.passed }

    /** Throws an [AssertionError] listing every failed scenario, if any failed. */
    public fun assertAllPassed() {
        if (failures.isNotEmpty()) {
            throw AssertionError(
                failures.joinToString(
                    separator = "\n",
                    prefix = "${failures.size} of ${results.size} plugin certification scenarios failed:\n",
                ) { "- ${it.name}: ${it.failure}" },
            )
        }
    }
}
