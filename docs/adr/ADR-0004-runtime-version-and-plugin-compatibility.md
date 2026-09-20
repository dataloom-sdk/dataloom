# ADR-0004: Canonical runtime version and plugin compatibility gating

## Status

Accepted. Builds on [ADR-0003](./ADR-0003-plugin-engine-module.md).

## Date

2026-09-19

## Context

`PluginCompatibilityRange` declares inclusive minimum and maximum
`RuntimeVersion` bounds, but `RuntimeVersion` was a plain non-blank string.
Existing call sites spelled it `"1.0.0"`, `"runtime-1.0.0"`, `"1.2.3"`, and
`"2.0.0"`, so no comparison of a plugin's range against the running SDK could
be defined. No source for "the running SDK version" existed anywhere either: no
Gradle project version and no generated build-config value.

`#98` requires compatibility validation before a plugin is activated. It also
left one lifecycle question open: what happens to an in-flight plugin
invocation when its plugin leaves `ACTIVE`.

Nothing is published and there are no external consumers, so breaking changes
to value types and result types are acceptable.

## Decisions

### D12: `RuntimeVersion` is strict Semantic Versioning 2.0.0

- A `RuntimeVersion` holds only `MAJOR.MINOR.PATCH` with optional
  `-PRERELEASE` and `+BUILD`. No leading `v`, label prefix, missing component,
  whitespace, or leading zero (in the core or in a numeric pre-release
  identifier). Core components fit in `Int`; total length at most 128.
- The constructor throws `IllegalArgumentException` for anything else, like
  every identifier type. `RuntimeVersion.parse` is the non-throwing path for
  untrusted input and returns a typed `RuntimeVersionParseResult`.
- `precedenceCompareTo` implements semver precedence (build metadata ignored).
  The type is deliberately not `Comparable`, because values differing only in
  build metadata are unequal yet tie on precedence.
- Every ad-hoc call site was migrated to canonical form.

### The running SDK version source

A hand-maintained constant, `DataLoomRuntimeVersion.CURRENT` in
`dataloom-runtime` (currently `0.1.0`), is the single source. It is the least
invasive option that exists today. The release process (DL-046) must set it to
the real release version, and a build-derived value should replace it when
publication wiring lands.

### Compatibility gate

- `PluginCompatibilityValidator` is a pure function over a range and an SDK
  version. Bounds are inclusive and compared by semver precedence, an absent
  maximum is unbounded, and a minimum above the maximum is `EMPTY_RANGE`.
- `PluginLifecycleStateTracker` requires the running SDK version (no unchecked
  mode) and refuses every transition into `VALIDATED` for an incompatible
  plugin with the non-throwing `PluginLifecycleTransitionResult.IncompatibleRuntime`.
  Because `VALIDATED` is the only route to `INITIALIZING` and `ACTIVE`, an
  incompatible plugin can never become active. It can still be disabled.
- `DataLoomBuilder.build()` does not reject an incompatible plugin. Rejection
  is a transition-time outcome, inspectable beforehand with `compatibilityOf`.

### D13: enforcer is gated by lifecycle state; in-flight work drains

- `PluginExecutionBoundsEnforcer` is bound to a `PluginLifecycleStateTracker`.
  A new invocation of a plugin that is not `ACTIVE` returns
  `PluginExecutionBoundsResult.NotActive` without running or consuming a
  concurrency slot.
- An invocation already in flight when its plugin leaves `ACTIVE` is not
  cancelled. It completes, or is cancelled by its own declared timeout or by
  caller cancellation.
- To make the cross-thread state read safe, the tracker keeps each plugin's
  state in a volatile cell. `transition` calls remain caller-serialized.

## Consequences

- A plugin declaring a minimum of `1.0.0` is incompatible with the `0.1.0`
  development SDK until the constant is bumped, which is the intended
  semver-correct behavior.
- Stored `RuntimeVersion` strings are decoded through the constructor, so a
  non-canonical stored value now fails to decode. That cannot occur for values
  written by a canonical `RuntimeVersion`.
- Dependency version ranges (`PluginDependency`) are still not compared
  against the depended-upon plugin's `PluginVersion`, which has no canonical
  format, and activation is not gated on dependency state.
- Actively cancelling in-flight invocations on disable is not provided; it
  would be a separate policy.

## Rejected alternatives

- **A lenient parser accepting `runtime-1.0.0` or `v1.0.0`.** Keeps multiple
  spellings for the same version, which defeats a single canonical form.
- **Checking compatibility at `build()` and throwing.** Contradicts the
  requirement for a well-defined non-throwing outcome and turns a plugin
  property into a host build failure.
- **An optional SDK version on the tracker.** A null meaning "unchecked" is a
  fail-open path in a deny-by-default engine.
- **Cancelling in-flight invocations when the plugin leaves `ACTIVE`.** No
  precedent exists for it, and a disabled plugin draining is the more
  conservative default.

## Validation

- `RuntimeVersionTest` (semver grammar, failure classes, precedence including
  the specification's ordering example), `PluginCompatibilityTest`,
  `PluginExecutionLifecycleGatingTest`, and `DataLoomBuilderPluginEngineTest`.
- `checkKotlinAbi` with regenerated baselines, including the Android-target
  `dataloom-model` baseline.

## References

- GitHub issue #98 — DL-044 plugin platform implementation gate
- [ADR-0003](./ADR-0003-plugin-engine-module.md)
- [Plugin registry and lifecycle state tracking](../api/plugin-registry.md)
