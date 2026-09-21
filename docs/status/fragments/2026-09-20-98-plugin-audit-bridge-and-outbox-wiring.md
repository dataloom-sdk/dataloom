# Fragment: #98 plugin execution-outcome audit bridge and outbox wiring

Gate: `#98` (DL-044 plugin platform), dashboard row 7. Builds on slices 1 and 2 (PRs #405 and #413).

## (a) Proposed "Recently shipped" row

| 2026-09-20 | `#98` slice 3: audit trail for the plugin engine. New `PluginExecutionBoundsOperationalEventBridge` (`dataloom-plugin`) maps every `PluginExecutionBoundsResult` (`Completed`, `TimedOut`, `ConcurrencyLimitExceeded`, and the slice-2 `NotActive`) to a redacted `AUDIT` `OperationalEventEnvelope`, in the exact shape of `PluginLifecycleAdministrationOperationalEventBridge`: stateless, no clock read, envelope and correlation id derived from a caller-minted `PluginExecutionInvocationId`, plugin id `INTERNAL`, numeric bounds and observed lifecycle state `PUBLIC`. `Completed.value` is never read and an exception from an operation is not a result, so plugin output and exception messages cannot reach an envelope (tested with a value whose `toString`/`equals`/`hashCode` throw). New opt-in `DataLoomBuilder.pluginOperationalEventOutboxConfiguration(DataLoomPluginOperationalEventOutboxSpec)` (store, scope defaulting to `plugin-events`, schema version, attempt bound; the builder supplies the runtime clock and the outbox health tracker, like the sibling specs) makes `DataLoom.pluginEngine` append every lifecycle transition result (the previously unwired lifecycle bridge) and every bounded-invocation result to the durable outbox after the result exists, swallowing append failures and never altering results. Absent spec, or spec without `pluginConfiguration`, is inert (no envelope, no append, no clock read). Both event kinds share one scope, so the outbox's per-key sequence numbers order them in append order. Bounded invocations have no natural id, so the engine mints one from plugin id, clock millisecond, and a mutex-guarded in-process counter; a cross-restart collision would cost one dropped audit record, never corruption. Verified on a Windows host with the Apple flag: `dataloom-plugin:jvmTest` 136 pass (13 new bridge tests: mapping table, redaction, identity), `dataloom-runtime:jvmTest` 1938 pass (11 new `DataLoomBuilderPluginOperationalEventOutboxTest` tests: absence-is-inert, ordered end-to-end append of 7 transition and execution events via `DataLoomBuilder`, concurrency-limit-before-completion ordering, refused transitions recorded, command-id idempotency, custom scope, failing store, throwing operation records nothing, output never stored). Not done: dependency version compatibility, isolation/bulkheading, certification kit, reference plugin, hook dispatch. Not verified: macOS/Linux CI, Simulator execution. |

## (b) Percentage

Row 7: 58% -> 62%. Justification: the audit-trail item and the lifecycle-bridge wiring named as pending are done and tested end to end through the builder; the remaining items (dependency version compatibility, isolation, certification kit, reference plugin, hook dispatch) are all still open and no subsystem can invoke a plugin yet.

## (c) "Still pending" text

Remove: "bridging `PluginExecutionBoundsResult` ... into the audit trail" and "connecting the lifecycle bridge to the outbox".

Remaining, in priority order:

1. Dependency version compatibility and dependency-state-gated activation (needs a canonical `PluginVersion` format).
2. Failure isolation/bulkheading beyond concurrency limiting.
3. Certification kit.
4. Reference non-provider plugin.
5. Hook-point dispatch (blocked on consuming subsystems adopting hook families).
