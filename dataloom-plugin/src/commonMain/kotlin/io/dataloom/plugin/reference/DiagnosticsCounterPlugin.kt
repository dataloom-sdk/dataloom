package io.dataloom.plugin.reference

import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCapability
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginPermission
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reference, non-provider [DataLoomPlugin] implementation (`#98`, DL-044
 * plugin platform) -- a small in-memory named-event counter a host
 * application invokes directly, through
 * [io.dataloom.plugin.PluginExecutionBoundsEnforcer.execute], once this
 * plugin is [io.dataloom.api.plugin.PluginLifecycleState.ACTIVE].
 *
 * ## Why this is boundable without hook-point dispatch
 *
 * `docs/api/plugin-registry.md` named "a reference non-provider plugin" as
 * needing "a real invocation call site (hook-point dispatch) to exist
 * first" -- true only for a plugin whose work happens *inside* the engine's
 * own dispatch of a sync-pipeline hook, which indeed remains blocked (zero
 * subsystem adoption of [io.dataloom.api.plugin.PluginHookPoint], confirmed
 * repository-wide). [io.dataloom.plugin.PluginExecutionBoundsEnforcer.execute]
 * is a *different*, already-shipped invocation mechanism: any host can call
 * it directly whenever it wants bounds-enforced (timeout + concurrency),
 * lifecycle-gated access to a registered plugin's own behavior, with no
 * dependency on hook dispatch at all. [DiagnosticsCounterPlugin] is invoked
 * exactly that way (see `DiagnosticsCounterPluginIntegrationTest`), so it
 * needs no hook dispatch to exist and be a genuine, non-trivial
 * [DataLoomPlugin] implementation a real third-party plugin author can model
 * their own plugin on.
 *
 * ## What it does
 *
 * Counts occurrences of caller-supplied named events in memory, guarded by
 * one [Mutex] so it is safe to invoke concurrently -- the same discipline
 * `dataloom-assets`'s own reference provider,
 * `io.dataloom.assets.memory.InMemoryAssetProvider`, already applies for its
 * own in-memory state. It declares [DIAGNOSTICS_CAPABILITY] (matching
 * [io.dataloom.api.plugin.PluginHookPoint.DIAGNOSTICS]'s extension-point
 * family) and requires [RECORD_PERMISSION] before a host may bring it into
 * `ACTIVE`, demonstrating this platform's permission-gated-activation
 * contract with a realistic, non-synthetic permission label -- the exact
 * contract [io.dataloom.plugin.testkit.PluginCertificationKit] certifies.
 *
 * ## What it is not
 *
 * Not a data-sync provider ([io.dataloom.assets.AssetProvider] or any
 * `StorageProvider`/`TransportProvider` family): it does not move or store
 * application data, and declares no dependency on one. It is unbounded
 * in-memory state, like `InMemoryAssetProvider`'s own documented "for tests,
 * samples and development, never production" posture -- a long-running host
 * should periodically [reset] it or otherwise bound its memory use.
 *
 * @param manifest this plugin's manifest. [manifestFor] builds one declaring
 *   this plugin's own capability and permission, leaving identity, version,
 *   vendor, and SDK-compatibility range to the caller.
 * @param executionBounds this plugin's declared execution-time and
 *   concurrency bounds, enforced by
 *   [io.dataloom.plugin.PluginExecutionBoundsEnforcer] once this plugin is
 *   registered and `ACTIVE`.
 */
public class DiagnosticsCounterPlugin(
    override val manifest: PluginManifest,
    override val executionBounds: PluginExecutionBounds,
) : DataLoomPlugin {

    private val mutex = Mutex()
    private val counters = LinkedHashMap<String, Long>()

    /**
     * Increments [event]'s counter by one. Safe to call concurrently from
     * multiple invocations (for example several concurrent
     * [io.dataloom.plugin.PluginExecutionBoundsEnforcer.execute] calls, up to
     * [executionBounds]'s own declared concurrency ceiling).
     *
     * @throws IllegalArgumentException if [event] is blank.
     */
    public suspend fun recordEvent(event: String) {
        require(event.isNotBlank()) { "DiagnosticsCounterPlugin: event name must not be blank." }
        mutex.withLock { counters[event] = (counters[event] ?: 0L) + 1L }
    }

    /** An immutable snapshot of every event counted so far, in first-seen order. */
    public suspend fun snapshot(): Map<String, Long> = mutex.withLock { counters.toMap() }

    /** Clears every counted event. Intended for a long-running host to bound memory use. */
    public suspend fun reset() {
        mutex.withLock { counters.clear() }
    }

    public companion object {
        /**
         * [PluginCapability] label this plugin declares, matching
         * [io.dataloom.api.plugin.PluginHookPoint.DIAGNOSTICS]'s extension-point
         * family.
         */
        public const val DIAGNOSTICS_CAPABILITY: String = "diagnostics.counter"

        /** [PluginPermission] label a host must grant before this plugin may enter `ACTIVE`. */
        public const val RECORD_PERMISSION: String = "diagnostics.counter.record"

        /**
         * Builds a [PluginManifest] declaring [DIAGNOSTICS_CAPABILITY] and
         * requiring [RECORD_PERMISSION], leaving identity, version, vendor, and
         * SDK-compatibility range to the caller -- the same division of labor
         * [io.dataloom.plugin.testkit.PluginCertificationKit]'s own fixture
         * uses when building manifests for certification scenarios.
         */
        public fun manifestFor(
            id: PluginId,
            version: PluginVersion,
            vendor: PluginVendor,
            compatibleSdkRange: PluginCompatibilityRange,
        ): PluginManifest = PluginManifest(
            id = id,
            version = version,
            vendor = vendor,
            compatibleSdkRange = compatibleSdkRange,
            capabilities = setOf(PluginCapability(DIAGNOSTICS_CAPABILITY)),
            permissions = setOf(PluginPermission(RECORD_PERMISSION)),
        )
    }
}
