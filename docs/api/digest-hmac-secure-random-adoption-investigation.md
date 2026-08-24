# `DataLoomDigestCalculator`/`DataLoomHmacCalculator`/`DataLoomSecureRandom` real-caller adoption: investigated, no genuine gap remains

[API reference index](./README.md)

## Status

**Investigated (2026-08-24). No genuine, un-adopted real call site found.** This
documents why, so a future attempt does not re-derive the same conclusion
from scratch. No call site was force-wired to close this gap — inventing one
where no real need exists would not actually prove SDK-wide adoption, so
none was written.
`docs/status/market-readiness.md`'s `#93` row's percentage is unchanged by
this document; only the "Still pending" wording is narrowed to describe what
this investigation actually found, replacing the previous blanket
"SDK-wide adoption of the secure-random/digest/HMAC/policy primitives at
real call sites" phrasing (accurate about policy at the time it was written,
but no longer precise about the digest/HMAC/secure-random third) with the
specific, narrower remainder below.

## What this compares against

`#353` (this session) closed the policy half of this gate's named gap by
wiring `PolicyEvaluator`/`DurablePolicyDecisionLog` into
`StrategySynchronizationExecutionCoordinator`'s real admission boundary. This
investigation is the sibling attempt for the other three primitives named in
the same "Still pending" clause: `DataLoomDigestCalculator`,
`DataLoomHmacCalculator`, and `DataLoomSecureRandom` (all shipped by `#230`/
`#234`, documented in [secure-random.md](secure-random.md) and
[integrity-and-key-references.md](integrity-and-key-references.md), both of
which already state "no current subsystem consumes these boundaries" as of
when they shipped). It also checked, per this task's own instruction, whether
that statement had quietly become false and the dashboard simply never
caught up (the way `#357` found for a different `#93` sub-gap) — it has not:
the only production non-test consumers of any of the three interfaces found
anywhere in this repository are the ones catalogued below, and none of them
is a gap this investigation can close.

## Method

Three separate searches, one per primitive, each looking for a genuine
existing need rather than a place the type would merely compile:

1. **`DataLoomDigestCalculator`** — searched for ad hoc identifier/content
   hashing (`.hashCode()` used for anything other than a Kotlin
   `equals`/`hashCode()` override pair, hand-rolled checksums, ad hoc
   corruption detection) at any point this codebase persists or transmits
   data, to see whether it should have used a real digest instead.
2. **`DataLoomHmacCalculator`** — searched for any place that needs to prove
   authenticity or tamper-evidence of a message (not just detect accidental
   corruption), where a keyed MAC would apply.
3. **`DataLoomSecureRandom`** — searched for ad hoc "unique enough" ID/token/
   nonce generation via `kotlin.random.Random`, `java.util.UUID`, or a
   hand-rolled scheme, anywhere cryptographic unpredictability would
   genuinely matter.

## `DataLoomDigestCalculator` — already adopted everywhere a genuine need exists

`ConfigurationSnapshot.create` (`dataloom-config/src/commonMain/kotlin/io/dataloom/api/configuration/ConfigurationSnapshot.kt`)
already computes its own `checksum` via an injected `DataLoomDigestCalculator`
over a canonical, NUL-separated encoding of its entries — precedent, not a
gap. `ConfigurationHistoryStateCodec` (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/configuration/ConfigurationHistoryStateCodec.kt`)
builds directly on that precedent: `decode` recomputes the checksum from
decoded entries via `ConfigurationSnapshot.create` and requires it to match
the persisted hex, so storage-layer corruption that still parses as
well-formed fields fails closed.

Every other `DurableStateCodec` implementation in the repository
(`OperationalEventOutboxStateCodec`, `PolicyDecisionRecordCodec`,
`UnresolvedConflictRecordCodec`, `ResolvedConflictDecisionRecordCodec`,
`StrategyDecisionEventCodec`, `AssetManifestHistoryStateCodec`) was read in
full. None of them hand-rolls a checksum or hash `DataLoomDigestCalculator`
should have computed instead:

- `OperationalEventOutboxStateCodec` never computes any checksum of its own —
  it delegates entirely to `OperationalEnvelopeWireCodec`'s existing frozen
  wire frame, which fails decoding on a malformed frame already. There is no
  ad hoc hash here to replace.
- `AssetManifestHistoryStateCodec`'s own KDoc explicitly documents why it
  does *not* follow `ConfigurationHistoryStateCodec`'s recompute-and-compare
  pattern: `AssetManifest.checksum` is a digest over the asset's actual
  bytes, which this codec never has access to (it only ever sees manifest
  metadata), so there is nothing for `decode` to independently recompute.
  Instead it relies on `AssetManifest`/`AssetChunkLayout`'s own constructor
  invariants to fail closed on corrupt decoded data — a real, considered
  design decision recorded at the time `#356` shipped it this session, not
  an oversight.
- The remaining four codecs (policy decisions, unresolved/resolved conflict
  decisions, strategy decision events) persist structured records with no
  content-integrity requirement beyond what their own domain types' `init`
  validation already enforces, and none of them computes any hash at all,
  ad hoc or otherwise.

`AssetManifest` itself only ever *carries* a `checksum` field — nothing in
this repository yet produces one, because asset chunking/upload is `#97`'s
still-open work (confirmed by this session's own `#356`, which shipped only
the durable-history layer, deliberately not a real caller). There is no
producer site today for this investigation to migrate onto the digest
calculator, because no producer site exists yet at all.

## `DataLoomHmacCalculator` — no message-authentication need exists yet

A targeted search for authenticity/tamper-evidence language
(`tamper`, `authenticat`, `signature`, `MAC`) across every `*Main` source set
turned up only prose mentions (KDoc listing "authentication" as out of
scope, transport codec KDoc mentioning "authentication headers" as an
application concern) — no code path anywhere signs, verifies, or checks a
keyed MAC. This matches `integrity-and-key-references.md`'s own "Deliberately
not included" section: DataLoom does not do encryption, PKI, or
authenticated transport framing itself, and no wire format shipped so far
(`OperationalEnvelopeWireCodec`, the various `DurableStateCodec`
implementations) is exposed to an untrusted network boundary that would need
authenticity proof rather than mere accidental-corruption detection — every
one of them is local, application-controlled persistence, or paired with a
transport the host application (not DataLoom) authenticates. There is
nothing to force-wire this onto without inventing a threat model this
codebase does not have.

## `DataLoomSecureRandom` — the two existing consumers are illustrative, not real SDK internals

Two production (non-test) files reference `DataLoomSecureRandom`:

- `runtime-ios-reference-consumer/src/iosMain/kotlin/io/dataloom/consumer/ios/IosReferenceConsumer.kt`
  uses `AppleDataLoomSecureRandom`-backed `IdentifierGenerator`s for its
  reference `RuntimeDependencies`.
- `runtime-android-reference-consumer/src/main/kotlin/io/dataloom/consumer/android/AndroidReferenceConsumer.kt`
  uses `java.util.UUID` instead — a real, pre-existing asymmetry between the
  two reference consumers, but not a gap this investigation's scope covers
  fixing, because of what these modules actually are (next paragraph).

Both modules are explicitly, self-documented as illustrative fixtures
proving provider composition for `#101` (DL-039A) — `IosReferenceConsumer.kt`'s
own KDoc: "A production application should replace the identifier generators
with whatever scheme fits its own durability/observability requirements —
random hex tokens are a reasonable, dependency-free default, not a DataLoom
requirement." Neither is a real internal SDK call site; both are
sample/reference code a host application is expected to replace. Adopting
`DataLoomSecureRandom` more consistently between them (or switching the
Android one to match) would be a reference-fixture consistency nit, not
closing a genuine SDK-wide-adoption gap.

More importantly, [`identifier-generation.md`](identifier-generation.md)
explicitly, deliberately defers production identifier generation as a
separate, future decision: *"Production identifier implementations are
deferred... These implementations will be introduced in dedicated future
issues. **Do not introduce production-generation strategies in DL-017.**"*
Wiring `DataLoomSecureRandom` into a real, non-reference identifier
generator inside `dataloom-runtime`/`dataloom-api` today would directly
contradict that explicit, already-recorded product decision — it is not an
oversight this investigation can quietly fix, it is a decision this
investigation is not positioned to override.

No other candidate site was found: no lease/token concept exists anywhere
in this codebase that needs unpredictability (queue leases are identified
via the same deferred `IdentifierGenerator` contract, not a separately
generated secret token), and the one other random-number source in the
runtime, `RetryRandomSource`, is *intentionally* deterministic for
retry-jitter reproducibility and explicitly documented as unsafe for
security use — substituting `DataLoomSecureRandom` there would be a
correctness regression, not an adoption win. [`secure-random.md`](secure-random.md)
already documents this exact distinction and why the two must never be
swapped.

## Why forcing a call site would not be genuine

The two nearest-looking candidates were considered and rejected:

- **Switching `AndroidReferenceConsumer.kt`'s `UUID`-based identifiers to
  `SystemDataLoomSecureRandom`, to match the iOS reference consumer.** This
  would compile and technically add a call site, but both reference
  consumers are illustrative fixtures a host application is expected to
  replace, not real SDK internals — the same "would not exercise a genuine
  need" problem `configuration-resolver-caller-investigation.md` rejected a
  similarly narrow fix for.
- **Wiring `DataLoomSecureRandom` into a real, non-reference default
  `IdentifierGenerator` inside `dataloom-runtime` or `dataloom-api`.** This
  would directly contradict `identifier-generation.md`'s explicit, recorded
  "do not introduce production-generation strategies in DL-017" instruction
  — a considered project decision, not an accidental gap.

## What is not in question

- `DataLoomDigestCalculator`, `DataLoomHmacCalculator`, `DataLoomSecureRandom`,
  and `KeyReference` are all fully implemented and tested exactly as
  documented in [secure-random.md](secure-random.md) and
  [integrity-and-key-references.md](integrity-and-key-references.md). This
  investigation found no defect in any of them.
- `DataLoomDigestCalculator` adoption is genuinely complete wherever a real
  need exists today (`ConfigurationSnapshot`/`ConfigurationHistoryStateCodec`);
  the one place it visibly does *not* apply (`AssetManifestHistoryStateCodec`)
  documents, correctly, why not.
- This finding does not change `#93`'s overall percentage. It narrows the
  "Still pending" wording in `docs/status/market-readiness.md` to state that
  the digest/HMAC/secure-random third of that clause has been investigated
  and found not to name a genuine remaining gap, leaving the still-real
  `RetryPolicy`/`StrategyPolicy`-onto-policy-foundation migration named
  separately.

## References

- [Secure random](secure-random.md) — the `DataLoomSecureRandom` primitive.
- [Integrity and key references](integrity-and-key-references.md) — the
  `DataLoomDigestCalculator`/`DataLoomHmacCalculator`/`KeyReference`
  primitives.
- [Identifier generation](identifier-generation.md) — the explicit
  "production generation deferred, do not introduce it in DL-017" boundary
  this investigation respects.
- [Configuration resolver caller investigation](configuration-resolver-caller-investigation.md) —
  the sibling `#93` investigation this document follows in structure and
  posture, for the policy-adjacent `DataLoomConfigurationResolver` half of
  this gate's gap.
