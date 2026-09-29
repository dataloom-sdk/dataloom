# ADR-0009: Canonical plugin version and dependency-gated activation

## Status

Accepted. Amends [ADR-0004](./ADR-0004-runtime-version-and-plugin-compatibility.md),
closing the two items its own Consequences section named as still open.
Builds on [ADR-0003](./ADR-0003-plugin-engine-module.md).

## Date

2026-09-22

## Context

ADR-0004 (decision D12) made `RuntimeVersion` — the SDK version a plugin
declares compatibility with — strict Semantic Versioning 2.0.0. Its own
Consequences section left two related questions explicitly open:

- "Dependency version ranges (`PluginDependency`) are still not compared
  against the depended-upon plugin's actual `PluginVersion`, which has no
  canonical format, and activation is not gated on dependency state."

`PluginVersion` (`dataloom-plugin-api`) was a plain non-blank `String`, unlike
`RuntimeVersion`. `PluginRegistry` (`dataloom-plugin`) validated the
dependency graph's *shape* only: duplicate ids and cycles were rejected at
construction, and an unresolved dependency id was **also** a construction-time
`IllegalArgumentException`, but no dependency's declared range was ever
compared against the depended-upon plugin's actual version, and a plugin's
own dependency state (disabled, unloaded, or simply not yet active) was never
consulted before letting it validate or activate.
`docs/api/plugin-registry.md` tracked this as "Dependency version
compatibility" under "What remains open" since 2026-09-19.

Nothing is published and there are no external consumers, so breaking the
ad-hoc `PluginVersion`/`PluginDependency` shapes is acceptable pre-V1.

## Decisions

### D19: `PluginVersion` is strict Semantic Versioning 2.0.0, delegating to `RuntimeVersion`

- `PluginVersion` (`dataloom-plugin-api`) now enforces exactly the grammar
  `RuntimeVersion` already enforces: `MAJOR.MINOR.PATCH` with optional
  `-PRERELEASE`/`+BUILD`, no leading `v`, no label prefix, no leading zeros,
  core components within `Int`, at most 128 characters. The constructor
  throws `IllegalArgumentException` for a non-canonical value; the new
  `PluginVersion.parse` is the non-throwing path, returning a typed
  `PluginVersionParseResult`/`PluginVersionParseFailure` that mirrors
  `RuntimeVersionParseResult`/`RuntimeVersionParseFailure` failure-for-failure.
  `precedenceCompareTo` implements semver precedence; like `RuntimeVersion`,
  the type is deliberately not `Comparable`.
- **No second parser.** `dataloom-plugin-api` already depends on
  `dataloom-model` for `RuntimeVersion`/`PluginCompatibilityRange`, so
  `PluginVersion` delegates its construction, parsing, and ordering to
  `RuntimeVersion` internally (translating `RuntimeVersionParseFailure` to
  the plugin-side `PluginVersionParseFailure` one-for-one) rather than
  duplicating roughly 200 lines of grammar and precedence logic already
  reviewed once. `RuntimeVersion` is an implementation detail here: no
  `RuntimeVersion` type appears in `PluginVersion`'s own public API.
- **New `PluginVersionRange`**, the plugin-to-plugin counterpart of
  `PluginCompatibilityRange`: inclusive `minimum`/`maximum` `PluginVersion`
  bounds, `maximum` optional. Kept as a distinct type from
  `PluginCompatibilityRange` rather than reused, because the two bound
  different axes with different depended-upon types (the SDK's
  `RuntimeVersion` versus another plugin's `PluginVersion`); sharing one type
  would let a caller splice a dependency range where an SDK range belongs, or
  vice versa, without a compile error.
- `PluginDependency.compatibilityRange: PluginCompatibilityRange` is renamed
  and retyped to `supportedVersionRange: PluginVersionRange`. Every existing
  call site already used canonical-looking version strings (`"1.0.0"`, and so
  on), so no literal needed rewriting — the type itself is what changed from
  unchecked to enforced.

### Dependency version compatibility and dependency-gated activation

- **Unresolved dependency is no longer a construction-time error.**
  `PluginRegistry` still rejects a dependency cycle among registered plugins
  at construction (including self-dependency), because a cycle makes
  `resolutionOrder` impossible to compute at all. An edge to a plugin that is
  simply not registered is different: it is a property of one plugin, not a
  defect in the graph's shape, exactly like an incompatible SDK range already
  is. It is now registered without throwing, contributes nothing to
  `resolutionOrder`, and is reported, non-throwing, at the moment a
  transition is attempted.
- **`PluginLifecycleStateTracker` gates `VALIDATED` and `ACTIVE`.** Every
  `transition` overload additionally refuses to move a plugin into
  `VALIDATED` or `ACTIVE` (including the `DEGRADED -> ACTIVE` recovery edge)
  while one of its declared `PluginDependency` entries is unsatisfied,
  returning the new `PluginLifecycleTransitionResult.DependencyUnsatisfied`
  and leaving state unchanged. A dependency is unsatisfied when: it is not
  registered; its actual `PluginManifest.version` falls outside the declared
  `PluginVersionRange` (compared by the same inclusive, semver-precedence
  logic `PluginCompatibilityValidator` already applies to the SDK check,
  extracted into one shared `rangeViolation` helper so the two checks cannot
  disagree); or it is `DISABLED` or `UNLOADED`. Entering `ACTIVE`
  additionally requires the dependency itself to be `ACTIVE` — `VALIDATED`
  does not, so a host may validate an entire plugin set before activating
  any of it. Every reason is one closed `PluginDependencyIssueReason` value
  paired with the blocking dependency's `PluginId`; `DependencyUnsatisfied`
  carries only stable ids and closed reasons, never free text, matching the
  shape `IncompatibleRuntime`/`PermissionDenied`/`AuthorizationDenied`
  already established.
- **Check order** per transition: structural legality
  ([`PluginLifecycleTransitions`](./ADR-0003-plugin-engine-module.md)), then
  SDK compatibility (D12), then dependencies (D19), then — for the overloads
  that have one — the permission or authorizer check. Each earlier check
  short-circuits the ones after it.
- **Only direct, own-declared dependencies are read**, at the moment of the
  transition; there is no separate transitive-closure walk. A transitive
  chain is still fully covered, because a dependency can only itself be
  `ACTIVE` if its own gate already passed, so requiring "the dependency is
  `ACTIVE`" carries the check down the chain automatically.
- **No cascading.** A dependency that becomes `DISABLED` after its dependent
  is already `ACTIVE` is not retroactively degraded or disabled. This mirrors
  ADR-0004's D13, which already leaves an in-flight invocation to drain
  rather than inventing a cancellation policy nobody asked for; cascading
  is a separate, currently undecided policy.
- **Audit bridging.** `PluginLifecycleAdministrationOperationalEventBridge`
  bridges `DependencyUnsatisfied` like every other outcome: the count of
  unsatisfied dependencies and the distinct closed reason names are
  `PUBLIC`; the specific `(dependency id, reason)` pairs are one `INTERNAL`
  attribute, the same conservative treatment every other plugin identifier
  already receives from this bridge.

## Consequences

- Breaking change, acceptable pre-V1: `PluginVersion`'s constructor now
  throws for a non-canonical value (previously accepted any non-blank
  string), and `PluginDependency`'s second constructor parameter is renamed
  and retyped. Both changes are additive from a *behavior* perspective, since
  every value ever constructed in this codebase was already canonical semver.
- `PluginRegistry` no longer throws for an unresolved dependency at
  construction. A caller that relied on that exception as an early validation
  signal must instead inspect a `transition` result (or add its own
  pre-check over `resolutionOrder`/`findById`) to learn the same fact.
- A dependency's own SDK compatibility is still judged independently, at that
  dependency's own transition (unchanged from ADR-0004); this decision does
  not couple the two checks together beyond the shared range-comparison
  helper.
- Cascading disable/degrade to dependents remains unimplemented. This is
  recorded here as a deliberate deferral, not a silent gap: a future slice
  that wants it must design the policy (force-disable versus degrade, how
  promptly, whether it recurses) rather than inherit one from this change.

## Rejected alternatives

- **A second, independent semver parser for `PluginVersion`.** Duplicates
  grammar and precedence logic already reviewed once for `RuntimeVersion`,
  with drift risk on every future grammar fix (an edge case fixed in one
  parser but not the other).
- **Reusing `PluginCompatibilityRange` for `PluginDependency`.** Conflates
  "the SDK version this plugin needs" with "the version of another plugin
  this plugin needs" — different axes bounding different depended-upon
  types — and would let a caller supply one where the other belongs with no
  compile-time signal.
- **Keeping the unresolved-dependency construction-time throw.** Would leave
  two different "this plugin's dependency graph has a problem" cases (a
  cycle versus a missing id) reported through two different mechanisms
  (throw versus a non-throwing result) for no reason once every other
  dependency defect is already a non-throwing transition-time outcome.
- **Cascading a `DISABLED` dependency to its already-`ACTIVE` dependents
  immediately.** No precedent in this engine for actively disturbing
  already-running state on a related state change (ADR-0004 D13 makes the
  same choice for in-flight invocations), and it invents an unscoped policy
  question this slice was not asked to answer.

## Validation

- `PluginVersionTest` (`dataloom-plugin-api`): parsing, every rejection
  failure, and Semantic Versioning specification precedence ordering,
  verified directly against `PluginVersion`'s own public API rather than
  assumed from `RuntimeVersion`'s.
- `PluginDependencyGatingTest` (`dataloom-plugin`): missing dependency,
  version below/above/an inverted declared range, a `DISABLED` dependency, an
  `UNLOADED` dependency, `VALIDATED` not requiring `ACTIVE` while `ACTIVE`
  does, the `DEGRADED -> ACTIVE` recovery edge re-checking the gate,
  `DISABLED` itself never gated, a full transitive chain activating end to
  end, deterministic multi-issue ordering, and check ordering against
  structural legality and SDK compatibility.
- `PluginRegistryTest`: an unresolved dependency registers without throwing
  and is excluded from `resolutionOrder`; a cycle among registered plugins
  (including self-dependency) remains a construction-time
  `IllegalArgumentException`.
- `DataLoomBuilderPluginEngineTest`/`DataLoomBuilderPluginDependencyGatingTest`
  (`dataloom-runtime`): end to end through the real `DataLoomBuilder`/
  `DataLoom.pluginEngine`, using the production `DataLoomRuntimeVersion.CURRENT`
  (no test-only override), including the refusal reaching
  `DurableOperationalEventOutbox` when
  `pluginOperationalEventOutboxConfiguration` (PR #419) is configured, and a
  fully satisfied dependency chain activating normally.
- `checkKotlinAbi` with regenerated baselines for `dataloom-plugin-api` and
  `dataloom-plugin`, at module and whole-build scope, in both the normal and
  `DATALOOM_ANDROID_BUILD=true` ABI-baseline layouts.

## References

- GitHub issue #98 — DL-044 plugin platform implementation gate
- [ADR-0003](./ADR-0003-plugin-engine-module.md) — the `dataloom-plugin`
  engine module this decision extends
- [ADR-0004](./ADR-0004-runtime-version-and-plugin-compatibility.md) — D12
  (canonical `RuntimeVersion`) and D13 (lifecycle-gated execution), whose
  Consequences section named this decision's two open items
- [Plugin registry and lifecycle state tracking](../api/plugin-registry.md)
- [Plugin SPI (`dataloom-plugin-api`)](../api/plugin-api.md)
