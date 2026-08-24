# `#98` plugin-platform first slice: investigated, already shipped under `#93`

[API reference index](./README.md)

## Question

`#98` (DL-044, "Implement and certify the full V1 plugin platform") is
tracked at `NOT STARTED`/15% in `docs/status/market-readiness.md`, described
as "Provider SPI exists; no V1 plugin lifecycle is accepted." This task's
brief was to investigate whether a genuinely bounded first slice exists for
`#98` today, mirroring the precedent set by `#93`'s own foundational
primitives and `#97`'s `AssetManifest`: a pure, dependency-free value type
(a plugin manifest — identity, version, declared permissions/capabilities,
compatibility range) with zero loading/registration/enforcement/isolation
logic attached, deferring any closed permission taxonomy or real semver-range
implementation the way `OperationalPayloadEncoding`/`AssetCompressionAlgorithm`
already do for analogous "keep the shape open" situations elsewhere in this
codebase.

## Finding: that exact slice already exists — built under `#93`, not `#98`

`dataloom-plugin-api` (module present since `#276`, "Add dataloom-plugin-api
SPI contracts") already contains precisely the candidate first slice this
task was sent to identify:

| Type | File | Shape |
|---|---|---|
| `PluginId`, `PluginVersion`, `PluginVendor`, `PluginCapability`, `PluginPermission` | `PluginIdentifiers.kt` | Non-blank-validated `value class` identifiers, mirroring `dataloom-provider-api`'s `ProviderId`/`ProviderCapability` pattern exactly. |
| `PluginManifest` | `PluginManifest.kt` | Identity, version, vendor, compatibility range, and bounded (max 64 each, defensively copied) capability/permission/dependency sets, with `init { require(...) }` bounds checks, full `equals`/`hashCode`/`toString`. |
| `PluginCompatibilityRange` | `PluginCompatibilityRange.kt` | Declared inclusive min/max `RuntimeVersion` bounds — a data shape only, no parsing or comparison. |
| `PluginDependency` | `PluginDependency.kt` | One declared edge to a depended-upon plugin plus that dependency's required compatibility range. |
| `PluginExecutionBounds` | `PluginExecutionBounds.kt` | Declared positive `maximumExecutionMillis`/`maximumConcurrentInvocations`, `init`-validated. |
| `PluginHookPoint` | `PluginHookPoint.kt` | Closed enum naming `#98`'s own required six extension-point families (`POLICY`, `CONFLICT`, `DIAGNOSTICS`, `EVENTS`, `METRICS`, `WORKFLOW_INTERCEPTOR`) — identifies *where* a plugin may extend DataLoom, not a callback signature. |
| `PluginLifecycleState` | `PluginLifecycleState.kt` | Closed enum documenting `#98`'s own required `LOADED`/`VALIDATED`/`INITIALIZING`/`ACTIVE`/`DEGRADED`/`DISABLED`/`UNLOADED` labels — labels only, no transition enforcement. |
| `DataLoomPlugin` | `DataLoomPlugin.kt` | The stable identity contract (`manifest`, `executionBounds`) every plugin implementation exposes — no lifecycle callback methods. |

This is backed by 241 lines of `commonTest` coverage
(`PluginManifestTest.kt`, `PluginIdentifiersTest.kt`,
`PluginExecutionBoundsTest.kt`) proving construction, bounds validation, and
equality/immutability — the same shape of coverage `AssetManifest`/
`ConfigurationSnapshot` shipped with — and is already documented end to end
in [`plugin-api.md`](./plugin-api.md), which explicitly states 16 passing
`jvmTest` tests, clean cross-compilation for all three iOS targets, and a
clean repo-wide `checkKotlinAbi` baseline.

Every design question this task's brief raised as a candidate open decision
is already resolved, in the codebase, exactly the way the brief anticipated:

- **Deny-by-default permissions** do not need a closed taxonomy decided now:
  `PluginPermission` is already a bounded, extensible non-blank `String`
  label type — the same "defer the closed set, keep the shape open" pattern
  `OperationalPayloadEncoding`/`AssetCompressionAlgorithm` established this
  session for analogous cases. Enforcing deny-by-default over these labels
  is `#98`'s runtime job; the label shape itself is done.
- **Compatibility range** does not need a real semver-range implementation
  yet: `PluginCompatibilityRange` already reuses `RuntimeVersion` (the same
  primitive `dataloom-model` already ships) as a plain inclusive min/max
  pair, explicitly deferring parsing/comparison to whoever validates
  compatibility later.
- **Manifest identity/version/vendor/capabilities/dependencies** are all
  already frozen as validated value types with real `init`-block validation
  and bounded (64-entry) cardinality, matching `PolicySet`'s own bounded-set
  discipline.

`dataloom-plugin-api/build.gradle.kts`'s own header comment confirms the
scope boundary directly: this module "contains the stable plugin manifest,
permission, lifecycle, compatibility, and bounded-execution *contracts*
required by `#93` (DL-039) to freeze the V1 published artifact graph. It
intentionally contains no plugin loading, registration, enforcement,
isolation, or certification behavior — that engine is `#98` (DL-044 plugin
platform)'s job, built on top of these contracts." `docs/api/plugin-api.md`
states the same boundary in its own words and was itself written as part of
`#93`, not `#98`. `docs/architecture/modules.md` and
`docs/architecture/artifact-graph-bom-gap-analysis.md` (round 14, `#93`)
both independently corroborate this module's existence and exact scope.

## Why nothing is implemented by this change

Phase 2 of this task's brief authorized building the manifest-shaped value
type *only if Phase 1 found it genuinely missing and buildable without an
unresolved product decision*. It is not missing — the type, its supporting
identifier/range/dependency/bounds/enum types, its tests, and its
documentation page all already exist, already shipped, already ABI-baselined,
under `#93`'s own commit history. Reimplementing any part of it here would
duplicate existing, working, already-reviewed code for no reason, which this
project's own discipline (`modules.md`'s "Future Module Expansion" rule, and
every prior investigation doc's "do not force a slice into existence")
explicitly rejects.

What remains genuinely open for `#98` is not a bounded value-type slice at
all — it is exactly the list `dataloom-plugin-api`'s own KDoc and
`build.gradle.kts` already name as deliberately excluded, and it is real
engineering/design work, not a value-type freeze:

- **Deny-by-default registration and enablement** — an actual runtime
  registry that defaults every plugin to disabled until explicitly granted.
- **The lifecycle state machine itself** — transition enforcement,
  validation ordering, and authorization for `LOADED → VALIDATED →
  INITIALIZING → ACTIVE ⇄ DEGRADED → DISABLED → UNLOADED`, not just the
  closed label set.
- **Permission enforcement** — routing a `PluginPermission` request through
  a real grant decision (this module explicitly cannot depend on
  `dataloom-api`'s policy foundation yet, so this is deferred integration
  work, not a value-type gap) with denied-operation diagnostics.
- **Execution-bounds enforcement** — actual timeout cancellation,
  concurrency limiting, and failure isolation/bulkheading over
  `PluginExecutionBounds`' declared numbers, not just declaring them.
- **Deterministic ordering, dependency validation, and cycle rejection**
  over the `PluginDependency` graph — `PluginDependency` only declares an
  edge; nothing resolves or validates the graph yet.
- **Compatibility validation before activation** — comparing a real running
  SDK `RuntimeVersion` against a `PluginCompatibilityRange`; the range type
  exists but nothing compares against it yet.
- **Hook-point callback signatures and dispatch** — `PluginHookPoint` names
  six families; no callback interface or invocation path exists for any of
  them, and per `plugin-api.md`'s own reasoning, each signature depends on
  a subsystem (`#93`'s policy foundation, `#95`, `#96`, the runtime pipeline)
  that has not yet adopted it — defining them now would be exactly the
  speculative-infrastructure-ahead-of-a-consumer pattern this project avoids.
- **Audit records, hot disable, and the certification kit/catalog** — none
  of these have any code today; each is nontrivial standalone engineering
  work with its own design surface (what an audit record schema looks like,
  what "authorized" hot disable requires, what a repeatable certification
  kit emits as evidence).
- **A reference non-provider plugin** — demonstrating the full lifecycle
  end to end requires the lifecycle engine to exist first; there is nothing
  to reference yet.

Every one of these is lifecycle/enforcement/isolation/audit/certification
*behavior*, which is precisely the category the task's own Phase 1 step 5
authorized concluding is "a larger design decision than this task should
make unilaterally" if found. It was found. None of it is a small, bounded,
dependency-free value type in the shape `AssetManifest`/`ConfigurationSnapshot`
established — each item above needs its own concrete design (a state-machine
authorization model, a permission-grant call path, a bulkheading strategy, an
audit schema, a certification evidence format) before any code could be
written, exactly the same class of finding this session already recorded for
the artifact-graph/BOM gap
(`docs/architecture/artifact-graph-bom-gap-analysis.md`) and the
configuration-resolver caller
(`docs/api/configuration-resolver-caller-investigation.md`).

## Correction applied

`docs/status/market-readiness.md`'s `#98` row's "Still pending" cell
previously read "Implement manifests, compatibility, ..." as if the manifest
and compatibility-range *shapes* were still open. They are not — only their
runtime *behavior* (enforcement, comparison, validation) is. The cell is
sharpened to say so precisely, the way `#93` and `#95`'s own rows were
already sharpened by this session's prior investigations. The `#98`
percentage (15%) and status (`NOT STARTED`) are unchanged: `dataloom-plugin-api`
was `#93`'s own contribution (already reflected in `#93`'s percentage, not
`#98`'s), and zero of `#98`'s own FR-PLUGIN-001–012 lifecycle/enforcement/
isolation/audit/certification behavior exists on `main` today. This is a
documentation-accuracy correction, not a delivery of new functionality.

## What is not in question

- `dataloom-plugin-api`'s existing types are all fully implemented, tested,
  and documented exactly as `plugin-api.md` describes. This investigation
  found no defect in any of them.
- The provider SPI (`dataloom-provider-api`) and the plugin SPI
  (`dataloom-plugin-api`) are two distinct, already-shipped things: a
  "provider" (`StorageProvider`, `TransportProvider`, `QueueProvider`, and
  so on) is a synchronous adapter DataLoom's runtime calls directly through
  `ProviderDescriptor`/`ProviderLifecycleState`/`ProviderRegistry`; a
  "plugin" (per `#98`'s own text distinguishing "provider plugins" from "a
  reference non-provider plugin") is the broader, still-unbuilt concept of
  an independently loaded/enabled/permissioned/bounded extension that may
  hook policy, conflict, diagnostics, events, metrics, or workflow
  interception without necessarily being a provider at all.
- This finding does not change `#93`'s percentage either — `dataloom-plugin-api`
  was already counted there when it shipped under `#276`.

## References

- [Plugin SPI (`dataloom-plugin-api`)](./plugin-api.md) — the existing
  contract module this investigation confirmed already covers the candidate
  first slice.
- [Least-privilege capabilities](./least-privilege.md) — the other bounded
  primitive already available for `#98`'s eventual permission-enforcement
  engine to adopt, per its own "plugin permission enforcement remains `#98`'s
  job" note.
- `docs/architecture/artifact-graph-bom-gap-analysis.md` — the round-14
  `#93` investigation that first flagged `dataloom-plugin-api`'s
  contracts-only scope boundary, cited by this task's own brief.
- [Second conflict resolver investigation](./second-conflict-resolver-investigation.md)
  and
  [Configuration resolver caller investigation](./configuration-resolver-caller-investigation.md)
  — the structural precedent this document follows.
- GitHub issue `#98` — DL-044 plugin platform implementation gate.
- GitHub issue `#93` — DL-039 foundations gate that shipped
  `dataloom-plugin-api` (`#276`).
