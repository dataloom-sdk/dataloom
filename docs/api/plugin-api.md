# Plugin SPI (`dataloom-plugin-api`)

## Status

**SPI contracts only — this module itself contains no behavior.** This
module is `#93` (DL-039 foundations)'s bounded slice toward the "plugin
API" line in its required scope ("Freeze the V1 published artifact graph
and ownership boundaries, including the intended ... plugin API ..."). It
exists to freeze the *shape* of a plugin's identity, manifest, permission
requests, lifecycle labels, extension points, and execution bounds —
matching ADR-0002's own description of `dataloom-plugin-api` as a "Stable
SPI: Plugin manifest, hooks, permissions, lifecycle, compatibility, and
bounded-execution contracts."

It deliberately contains **zero behavior itself**: no plugin discovery,
loading, registration, deny-by-default enablement, permission enforcement,
dependency-cycle validation, compatibility-range comparison, timeout
cancellation, concurrency limiting, failure isolation/bulkheading, hot
disable, or audit recording. Most of that remains `#98` (DL-044 plugin
platform)'s open job, built on top of these contracts — its own issue text
says "Implement after the relevant `#93` ... slices," confirming this
ordering. `#98` has now shipped its first bounded slice on top of these
contracts, in `dataloom-core` rather than this module: deny-by-default
registration, dependency-graph validation/resolution ordering/cycle
rejection, and lifecycle state-machine transition enforcement — see
[Plugin registry and lifecycle state tracking](./plugin-registry.md).
Permission enforcement, execution-bounds enforcement, compatibility
validation, hook-point dispatch, hot disable, audit, and certification
remain unbuilt.

## What exists here

| Type | Purpose |
|---|---|
| `PluginId`, `PluginVersion`, `PluginVendor`, `PluginCapability`, `PluginPermission` | Validated (non-blank) identifier value classes, mirroring `dataloom-provider-api`'s `ProviderId`/`ProviderCapability` pattern. |
| `PluginCompatibilityRange` | Declared inclusive min/max SDK version bounds. A data shape only — it does not parse or compare versions. |
| `PluginDependency` | One declared edge from a plugin to another plugin it depends on, with that dependency's required compatibility range. |
| `PluginManifest` | Identity, version, vendor, compatibility range, and bounded (max 64 each) capability/permission/dependency sets. |
| `PluginLifecycleState` | Closed enum: `LOADED`, `VALIDATED`, `INITIALIZING`, `ACTIVE`, `DEGRADED`, `DISABLED`, `UNLOADED` — documents states only, does not enforce transitions, mirroring `ProviderLifecycleState`'s own documented rule. |
| `PluginHookPoint` | Closed enum naming the six extension-point families `#98` requires (`POLICY`, `CONFLICT`, `DIAGNOSTICS`, `EVENTS`, `METRICS`, `WORKFLOW_INTERCEPTOR`) — identifies *where* a plugin may extend DataLoom, not the callback signature for that extension point. |
| `PluginExecutionBounds` | Declared positive time/concurrency bounds for one plugin's invocations. A declared-bounds shape only — enforcement is `#98`'s job. |
| `DataLoomPlugin` | The stable identity contract every plugin implementation exposes: `manifest` and `executionBounds`. Deliberately has no lifecycle callback methods (`initialize`/`activate`/`disable`/hook invocation) — those signatures depend on the execution context `#98` designs and are not yet frozen. |

## Why hook callback signatures aren't defined yet

`#98`'s own requirements list "stable extension points for policy, conflict,
diagnostics, events, metrics, and workflow interceptors" as things to
**implement**, and each extension point's actual callback signature depends
on the subsystem it extends: `POLICY` on `#93`'s own policy foundation
(`io.dataloom.api.policy.PolicyEvaluator`'s family), `CONFLICT` on `#95`,
`DIAGNOSTICS`/`EVENTS`/`METRICS` on `#96`, and `WORKFLOW_INTERCEPTOR` on the
runtime execution pipeline. Defining concrete callback interfaces here,
ahead of those subsystems actually adopting them, would be exactly the kind
of speculative infrastructure ahead of a concrete consumer this project
avoids building. `PluginHookPoint` names the six families now so `#98`'s
manifest/permission model has something concrete to declare against; the
callback shapes themselves are each subsystem's own later integration work.

## Dependency boundary

`dataloom-plugin-api` depends only on `dataloom-model` (for
`RuntimeVersion`), the same minimal-dependency shape `dataloom-provider-api`
already uses. It does not depend on `dataloom-api`, so it cannot reference
the policy foundation's concrete types directly yet — a plugin's declared
`PluginPermission`s are request labels only in this module; routing a
permission request through the actual policy foundation for a real grant
decision is `#98`'s integration work, not this contract freeze.

## Verification

- `dataloom-plugin-api:jvmTest`: 16 tests, 0 failures.
- Cross-compiles clean for all three iOS targets
  (`compileKotlinIosArm64`/`IosSimulatorArm64`/`IosX64` with
  `-Pdataloom.appleKlibCrossCompile=true`).
- `checkKotlinAbi` clean repo-wide; the new module's baseline is additive
  only — no other module's baseline changed.
