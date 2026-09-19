# Fragment: #98 canonical RuntimeVersion, plugin compatibility gate, lifecycle-gated execution

Gate: `#98` (DL-044 plugin platform), dashboard row 7. Builds on slice 1 (PR #405, ADR-0003).

## (a) Proposed "Recently shipped" row

| 2026-09-19 | `#98` slice 2 (decisions D12/D13, [ADR-0004](../adr/ADR-0004-runtime-version-and-plugin-compatibility.md)). (1) `RuntimeVersion` (`dataloom-model`) is now strict Semantic Versioning 2.0.0: the constructor throws for non-canonical values, `RuntimeVersion.parse` is the non-throwing path returning a typed `RuntimeVersionParseResult`/`RuntimeVersionParseFailure`, and `precedenceCompareTo` implements semver precedence. Every ad-hoc call site (`"runtime-1.0.0"` in two tests, an identifier contract test, and two docs) was migrated; 18 new `RuntimeVersionTest` tests. (2) No running-SDK version source existed, so `DataLoomRuntimeVersion.CURRENT` (`dataloom-runtime`, `0.1.0`, hand-maintained, to be set by the release process) was added. `PluginCompatibilityValidator` (pure, non-throwing `PluginCompatibilityResult`) checks a manifest's inclusive SDK range against it; `PluginLifecycleStateTracker` now requires the SDK version and refuses every transition into `VALIDATED` for an incompatible plugin with the new non-throwing `PluginLifecycleTransitionResult.IncompatibleRuntime` (so it can never become `ACTIVE`; it can still be disabled); `DataLoom.pluginEngine.compatibilityOf(id)` exposes the check, and the lifecycle audit bridge maps the new variant. (3) `PluginExecutionBoundsEnforcer` is now bound to the tracker: a new invocation of a non-`ACTIVE` plugin returns the new `PluginExecutionBoundsResult.NotActive` without running or consuming a slot, while an in-flight invocation is not cancelled by a state change (it completes or hits its own timeout). Tracker state moved to volatile cells so the enforcer's read is thread-safe. New tests: `PluginCompatibilityTest` (19), `PluginExecutionLifecycleGatingTest` (13), 7 more in `DataLoomBuilderPluginEngineTest`, 3 more bridge tests. Verified on a Windows host with the Apple flag: `dataloom-model`/`-api`/`-provider-api`/`-plugin-api`/`-plugin`/`-core`/`-runtime` `jvmTest` all pass; `dataloom-model:testAndroidHostTest` 189 pass; iOS main and test sources compile for model, plugin and runtime; Android `runtime-android-reference-consumer:assembleDebug` and `dataloom-queue-room:compileDebugUnitTestKotlin` compile; ABI baselines regenerated (including the Android-config `dataloom-model` `api/jvm` baseline) and diff-reviewed. Not verified: macOS/Linux CI and Simulator execution. Not done: dependency version compatibility (`PluginVersion` has no canonical format), dependency-state-gated activation, audit bridging of execution results, isolation, certification kit, reference plugin, hook dispatch. |

## (b) Percentage

Row 7: 52% (after slice 1's fragment) -> 58%. Justification: the two items the row named as blocked or open design questions (compatibility validation and tracker/enforcer integration) are resolved and tested; remaining items are all still open and no subsystem can invoke a plugin yet.

## (c) "Still pending" text

Remove: "Compatibility validation against a real running SDK version (blocked: `RuntimeVersion` has no canonical parseable format ...)" and "wiring `PluginExecutionBoundsEnforcer` to `PluginLifecycleStateTracker` ...".

Remaining, in priority order:

1. Bridging `PluginExecutionBoundsResult` (including `NotActive`) into the operational-event audit trail, and connecting the existing lifecycle bridge to the outbox through a builder spec.
2. Dependency version compatibility and dependency-state-gated activation (needs a canonical `PluginVersion` format).
3. Failure isolation/bulkheading beyond concurrency limiting.
4. Certification kit.
5. Reference non-provider plugin.
6. Hook-point dispatch (blocked on consuming subsystems adopting hook families).

Also note: `DataLoomRuntimeVersion.CURRENT` is a placeholder (`0.1.0`) that the release process (DL-046) must set; add it to the release-readiness checklist.
