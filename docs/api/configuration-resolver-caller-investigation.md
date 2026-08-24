# `DataLoomConfigurationResolver`/`DurableConfigurationHistory` real caller: investigated, not achievable as a genuine integration yet

[API reference index](./README.md)

## Status

**Investigated (2026-08-24). No genuine real caller found in `dataloom-runtime`
today.** This documents why, so a future attempt does not re-derive the same
conclusion from scratch, and names exactly what would need to exist first.
No call site was force-wired to close this gap — inventing one where no real
multi-source configuration precedence need exists would not actually prove
what closing the gap claims to prove, so none was written.
`docs/status/market-readiness.md`'s `#93` row's percentage is unchanged by
this document; only the "Still pending" wording is sharpened to name the
precise blocker this investigation found, replacing the previous "genuinely
blocked, not just undone" phrasing (which was accurate but did not yet say
*why*) with the specific reason below.

## What this compares against

[`configuration-snapshots.md`](configuration-snapshots.md) already documents
`DataLoomConfigurationResolver`/`ConfigurationSnapshot`/
`DataLoomConfigurationHistory`/`DurableConfigurationHistory` as "a bounded
first slice" whose own "Deliberately not included" section names "Wiring
into `RuntimeDependencies`/`DataLoomBuilder`" as explicit, separate follow-up
work — the same posture already used for `DataLoomSecureRandom`/
`DataLoomClock`/the digest and HMAC calculators before *their* first real
adopters existed. This investigation is that follow-up attempt for the
configuration-resolver half of `#93`'s remaining gap (`DurablePolicyDecisionLog`/
`PolicyEvaluator` is a separate, sibling half tracked independently).

## What `DataLoomConfigurationResolver.resolve` actually needs from a caller

`DataLoomConfigurationResolver.resolve(sources: List<ConfigurationSource>, version: Long)`
(`dataloom-config/src/commonMain/kotlin/io/dataloom/api/configuration/DataLoomConfigurationResolver.kt`)
merges one or more `ConfigurationSource` layers — each tagged with a fixed
`ConfigurationScope` (`BUILT_IN_DEFAULT` < `REMOTE_ASSIGNED` < `LOCAL_OVERRIDE`,
later wins) — into a single validated, checksummed `ConfigurationSnapshot`,
and reports unknown-key/type-mismatch/missing-required-key/same-scope-conflict
findings.

For this to be a genuine call site rather than a contrived one, a real
subsystem in `dataloom-runtime` would need to:

1. Actually receive configuration values for the **same logical settings**
   from **more than one source** with a genuine precedence relationship
   between them (a built-in default, optionally overridden by something
   fetched remotely, optionally overridden again locally) — not just accept
   one flat struct from whoever constructs it.
2. Need those values validated against a **closed, declared schema** before
   admission (`ConfigurationSchema`'s "every admitted key must be declared,
   there is no permissive/passthrough mode" contract) — not just accept
   whatever shape the caller's Kotlin type system already enforces at compile
   time.
3. Need the resolved result **versioned and audited over time**, optionally
   survivable across a process restart (`DurableConfigurationHistory`) —
   not just consumed once and discarded.

## What actually exists in `dataloom-runtime` today

A systematic search of every `*Configuration`/`*Spec` type `DataLoomBuilder`
accepts or constructs turned up thirteen configuration data classes:
`QueueWorkerConfiguration`, `StandardRetryPolicy`,
`RetrySchedulingConfiguration`, `RetryTimeoutConfiguration`,
`RetryHintConfiguration`, `RetryBudgetConfiguration`,
`CircuitBreakerConfiguration`, `BidirectionalPipelineConfiguration`,
`InboundPullConflictDetectionConfiguration`, `InboundPullPipelineConfiguration`,
`OutboundPushPipelineConfiguration`, `SynchronizationConnectivityConfiguration`,
plus the eighteen-plus `DataLoomXxxSpec` opt-in classes `DataLoomBuilder`
already exposes (`DataLoomConflictDetectionSpec`,
`DataLoomOperationalEventOutboxSpec`, `DataLoomProviderProtectionSpec`,
`DataLoomRetryAdministrationSpec`, and so on).

Every one of them is a **flat data class supplied whole by whichever single
call site constructs it** — `CircuitBreakerConfiguration`, read in full, is
representative: `failureThreshold`, `failureWindow`, `openDuration`, and two
more fields, each with a compile-time-typed default, validated by `init {
require(...) }` blocks, and handed to `DataLoomBuilder` as one already-decided
value. None of the thirteen have a second, independent source for the same
setting that a real precedence rule would need to arbitrate between — there
is no "built-in default overridden by a remote-assigned value overridden by
a local override" shape anywhere in `dataloom-runtime`, because nothing in
this repository today fetches remote configuration, reads a local override
file, or otherwise produces more than one `ConfigurationSource`-shaped layer
for the same logical setting. `DataLoomBuilder`'s own opt-in `Spec` pattern
(mirrored by `DataLoomOperationalEventOutboxSpec`'s KDoc and reused
throughout this investigation as the wiring template it would follow *if* a
real need existed) is itself the only "precedence" DataLoom has today, and
it is a compile-time choice by the host application about which capability
to enable — not a runtime merge of layered values needing `ConfigurationScope`
arbitration.

A targeted search for the shape a genuine caller would need —
remote-configuration fetching, feature-flag delivery, or a local-override
file mechanism — found none:

```
grep -riE "RemoteConfig|FeatureFlag|remoteAssigned|localOverride" .
```

returns matches only inside `dataloom-config`'s own primitive, its tests,
its ABI baseline files, and this repository's own documentation describing
the primitive (`configuration-snapshots.md`, `change-model.md`,
`docs/strategies/README.md`'s unrelated `ChangeOperation` enum) — nothing
in `dataloom-runtime`, `dataloom-api`, or any provider module.

`configuration-snapshots.md`'s own "Deliberately not included" section
independently corroborates this: it lists "administrative overrides" among
the follow-up items *not yet wired*, in the same sentence as
`RuntimeDependencies`/`DataLoomBuilder` wiring itself — i.e. the doc that
shipped the primitive already recorded, at the time it shipped, that nothing
consuming it existed yet.

## Why forcing a call site would not be genuine

The two nearest-looking candidates were considered and rejected:

- **Treating one `DataLoomBuilder` `Spec`'s fields as a single
  `ConfigurationSource` at `LOCAL_OVERRIDE` scope, with no other layer.**
  This would compile and "wire" the resolver, but `resolve` with exactly one
  source at exactly one scope never exercises the resolver's actual reason
  to exist — precedence between competing sources. It would be a
  `ConfigurationSchema` validation pass wrapped around a value that was
  already fully valid by construction (every field already has a Kotlin
  type and an `init { require(...) }` check), producing no behavior a real
  application could observe or configure differently than today, and no
  precedence decision for `DurableConfigurationHistory` to durably record —
  exactly the "inventing a fake need" the task describes.
- **Fabricating a second, synthetic `BUILT_IN_DEFAULT` source purely so a
  `LOCAL_OVERRIDE` source has something to out-rank.** This manufactures the
  very precedence relationship the investigation is supposed to confirm
  exists, rather than finding one that already does. It would also silently
  change nothing about runtime behavior (the "default" would always lose to
  the override that was going to be used anyway), so the only thing gained
  would be an unreachable code path exercised solely by its own tests — not
  a real integration.

## What would need to exist first

A genuine call site needs, at minimum, one of:

1. **A remote configuration/feature-flag delivery mechanism** producing a
   `REMOTE_ASSIGNED`-scope `ConfigurationSource` for some real runtime
   setting (for example, retry/circuit-breaker thresholds fetched from a
   control plane) that a `LOCAL_OVERRIDE` source could then override on top
   of a `BUILT_IN_DEFAULT`. `configuration-snapshots.md` already scopes
   "remote config delivery or fetching" out of the primitive itself and
   into "the host application's job" — so this would most plausibly arrive
   as a reference/sample integration (an application wiring its own remote
   source through `ConfigurationSource`) rather than something
   `dataloom-runtime` itself grows.
2. **A local-override file or environment mechanism** for an existing
   runtime setting, giving `LOCAL_OVERRIDE` scope something real to
   represent beyond "whatever the host application already passed in
   directly."
3. Failing either of those, a deliberate product decision to model one of
   the existing thirteen flat `*Configuration` classes as
   `ConfigurationSchema`-validated even with only ever one source — which
   is a real design choice with real tradeoffs (schema validation overhead
   and `ConfigurationValue`'s narrower type set vs. Kotlin's native typing)
   that this investigation is not positioned to make unilaterally, since it
   would change behavior/API shape for a widely-used builder surface rather
   than add a new opt-in capability alongside existing behavior.

None of these exists in the repository today, and each is itself a separate,
larger piece of product/infrastructure work — not a bounded slice addable
alongside a single new `Spec` class the way this task's other `#93` foundation
wiring (`DataLoomOperationalEventOutboxSpec`, `DataLoomConflictDetectionSpec`,
and so on) has been.

## What is not in question

- `DataLoomConfigurationResolver`, `ConfigurationSnapshot`,
  `DataLoomConfigurationHistory`, and `DurableConfigurationHistory` are all
  fully implemented and tested exactly as documented in
  [`configuration-snapshots.md`](configuration-snapshots.md). This
  investigation found no defect in any of them — only the absence of a
  genuine multi-source consumer in `dataloom-runtime` today.
- This finding does not change `#93`'s overall percentage. It sharpens the
  "Still pending" wording in `docs/status/market-readiness.md` to name the
  specific blocker (no subsystem in this codebase currently produces more
  than one `ConfigurationSource`-shaped layer for the same setting) instead
  of only stating that no caller exists.

## References

- [Configuration snapshots](configuration-snapshots.md) — the primitive
  this investigation searched for a caller of, including its own
  "Deliberately not included" section corroborating this finding.
- `docs/apple/process-termination-investigation.md` — the investigation-doc
  precedent this document follows in structure and posture.
- `docs/android/kmp-android-target-blocker.md` — an earlier example of the
  same "investigated and confirmed blocked, recorded so a future attempt
  starts from a different angle" pattern.
