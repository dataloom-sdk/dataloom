# Fragment: #98 canonical PluginVersion and dependency-gated activation

Gate: `#98` (DL-044 plugin platform), dashboard row 7. Builds on slices 1-3
(PRs #405, #413, #419). Decided and implemented together as
[ADR-0009](../../adr/ADR-0009-plugin-version-and-dependency-gated-activation.md)
(D19).

## (a) Proposed "Recently shipped" row

| 2026-09-22 | `#98` slice 4 (D19, [ADR-0009](../adr/ADR-0009-plugin-version-and-dependency-gated-activation.md)): canonical plugin version and dependency-gated activation, closing the row's own long-standing "dependency version compatibility" gap. `PluginVersion` (`dataloom-plugin-api`) is now strict Semantic Versioning 2.0.0 — a non-throwing `PluginVersion.parse` returns a typed `PluginVersionParseResult`/`PluginVersionParseFailure`, `precedenceCompareTo` implements semver precedence — delegating its grammar internally to `dataloom-model`'s `RuntimeVersion` rather than duplicating the parser (no `RuntimeVersion` type appears in `PluginVersion`'s own API). New `PluginVersionRange` is the plugin-to-plugin counterpart of `PluginCompatibilityRange`; `PluginDependency.compatibilityRange` is renamed and retyped to `supportedVersionRange: PluginVersionRange`. `PluginRegistry` no longer rejects an unresolved dependency at construction (only a cycle among registered plugins still is); instead every `PluginLifecycleStateTracker.transition` overload now refuses `VALIDATED`/`ACTIVE` (including `DEGRADED -> ACTIVE`) while a declared dependency is missing, outside its declared range, or `DISABLED`/`UNLOADED` — entering `ACTIVE` additionally requires the dependency itself be `ACTIVE`, which carries the check down a transitive chain with no separate closure walk — returning the new non-throwing `PluginLifecycleTransitionResult.DependencyUnsatisfied` (stable ids and closed `PluginDependencyIssueReason` values only, never free text). Check order is structural legality, then SDK compatibility, then dependencies, then permission/authorizer checks. `PluginLifecycleAdministrationOperationalEventBridge` bridges the new outcome like every other. Verified on a Windows host with the Apple flag: `dataloom-plugin-api:jvmTest` 33 pass (+17, `PluginVersionTest`), `dataloom-plugin:jvmTest` 156 pass (+19, `PluginDependencyGatingTest`, covering missing/out-of-range/inverted-range/disabled/unloaded dependencies, the VALIDATED-vs-ACTIVE gating difference, DEGRADED recovery re-check, a full transitive chain, deterministic multi-issue ordering, and check-ordering), `dataloom-runtime:jvmTest` 2016 pass (new `DataLoomBuilderPluginDependencyGatingTest`: end to end through the real `DataLoomBuilder`/`DataLoom.pluginEngine` using the production `DataLoomRuntimeVersion.CURRENT`, including the refusal reaching `DurableOperationalEventOutbox` when `pluginOperationalEventOutboxConfiguration` is configured, and a satisfied chain activating normally); iOS main and test sources cross-compile clean for all three targets in the three touched modules; `checkKotlinAbi` regenerated and reviewed in both the normal and `DATALOOM_ANDROID_BUILD=true` baseline layouts, byte-identical, additive except the intentional `PluginDependency`/`PluginVersion` shape changes. Not done: hook-point dispatch, failure isolation/bulkheading, the certification kit, a reference plugin. Not verified: macOS/Linux CI, Simulator execution. |

## (b) Percentage

Row 7: 60% -> 64%. Justification: the dependency version compatibility and
dependency-gated activation item this row's own log history named as
pending since 2026-09-19/20 is now shipped and tested end to end through the
real builder and outbox; the row's remaining named items (hook-point
dispatch, isolation/bulkheading, certification kit, reference plugin) are
unchanged and still block the gate from a larger jump.

## (c) "Still pending" text

The row's current last-column "Still pending" text already does not name
dependency version compatibility explicitly, so nothing needs removing
there. For clarity when folding this in, the row body's own narrative
mention of "Dependency version compatibility... [is] still not compared"
(carried in this gate's prior dated log entries, e.g. the 2026-09-19 and
2026-09-20 fragments) is now resolved and should not be repeated as
outstanding.

Remaining, in priority order (unchanged from the prior fragment):

1. Hook-point dispatch (blocked on consuming subsystems adopting hook
   families — `#93` policy, `#95`, `#96`, the runtime pipeline).
2. Failure isolation/bulkheading beyond concurrency limiting.
3. Certification kit.
4. Reference non-provider plugin (needs hook-point dispatch first for a
   real invocation call site).
