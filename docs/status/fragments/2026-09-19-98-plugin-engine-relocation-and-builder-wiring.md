# Fragment: #98 plugin engine relocation and DataLoomBuilder wiring

Gate: `#98` (DL-044 plugin platform), dashboard row 7.

## (a) Proposed "Recently shipped" row

| 2026-09-19 | Resolved `#98`'s named module-ownership blocker (decision D1, [ADR-0003](../adr/ADR-0003-plugin-engine-module.md)) by relocating the whole plugin engine (`PluginRegistry`, `PluginLifecycleTransitions`/`PluginLifecycleStateTracker` and its request/decision/result types, `PluginExecutionBoundsEnforcer`, `PluginLifecycleAdministrationOperationalEventBridge`) out of `dataloom-core` into the new published module `dataloom-plugin` (`io.dataloom.plugin`, `io.dataloom:dataloom-plugin`), rather than keeping it in `dataloom-core` behind a duplicate translation layer in `dataloom-runtime`. Types moved unchanged apart from package; the 88 existing plugin tests moved with them and pass unchanged (`dataloom-plugin:jvmTest`). `dataloom-core` now holds no plugin code and no dependency on `dataloom-plugin-api` or `kotlinx-coroutines`. Wired the engine into `DataLoomBuilder` as an opt-in capability following the existing `*Configuration`/`*Spec` pattern: `pluginConfiguration(DataLoomPluginSpec)` and a nullable `DataLoom.pluginEngine` (`resolutionOrder`, `stateOf`, authorizer-gated `transition`, bounds-enforced `execute`), returning the engine's own types with no translation. A host lifecycle authorizer is required (no authorizer-free path); an invalid plugin graph fails `build()` with the registry's `IllegalArgumentException`. 17 new `dataloom-runtime` tests (`DataLoomBuilderPluginEngineTest`) cover absence-is-inert, wiring, and every transition/execution result variant; full `dataloom-runtime:jvmTest` 1840 tests, 0 failures. ABI baselines regenerated and diff-reviewed: `dataloom-core` loses exactly its plugin declarations (226 JVM / 248 klib lines, zero additions), `dataloom-plugin` gains its first baseline, `dataloom-runtime` gains only additive declarations and no `io.dataloom.core.` type (`checkPublicAbiBoundaries` passes). Also added an external-consumer probe and exported `dataloom-plugin-api`/`dataloom-plugin` from the Apple umbrella (XCFramework assembly not verifiable on Windows; macOS CI covers it). Not done: compatibility validation, tracker/enforcer integration, audit-trail bridging (lifecycle bridge is still unconnected to an outbox), isolation, certification kit, reference plugin, hook-point dispatch. |

## (b) Percentage

Row 7: 48% -> 52%. Justification: the one named architectural blocker is resolved and the engine is now reachable from a real composition root through a public, ABI-clean, opt-in surface; every remaining item (compatibility validation, in-flight semantics, audit bridging, isolation, certification kit, reference plugin, hook dispatch) is still open, and no plugin can yet be invoked by any subsystem.

## (c) "Still pending" text

Remove from the pending list: "wiring into `DataLoomBuilder` (blocked on a real module-ownership decision ...)".

Add/replace with, in priority order:

1. Canonical parseable `RuntimeVersion` format and compatibility validation against the running SDK version.
2. Wiring `PluginExecutionBoundsEnforcer` to `PluginLifecycleStateTracker` (in-flight invocation on state change).
3. Bridging `PluginExecutionBoundsResult` (and connecting the existing lifecycle bridge to the operational-event outbox via a builder spec) into the audit trail.
4. Failure isolation/bulkheading beyond concurrency limiting.
5. Certification kit.
6. Reference non-provider plugin.
7. Hook-point dispatch (blocked on consuming subsystems adopting hook families).

Also note in the row-7 "Finished on main" cell: engine module is `dataloom-plugin` (was `dataloom-core`'s `io.dataloom.core.plugin`); `docs/api/plugin-platform-databuilder-wiring-investigation.md` is now resolved.
