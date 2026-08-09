# DataLoom Configuration Snapshots

[API reference index](./README.md)

> **Status:** Available in-memory contract with a production implementation
> (no platform-specific code is required — everything below is pure common
> Kotlin). This is a bounded first slice of `#93`'s "versioned immutable
> configuration snapshots, validation, precedence, safe rollout, and
> rollback" requirement, not the full target described in
> [ADR-0002](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)'s
> `### Configuration` section. Durable/transactional persistence, the shared
> policy engine, remote config delivery, and wiring into
> `RuntimeDependencies`/`DataLoomBuilder` are explicitly out of scope here —
> see [Deliberately not included](#deliberately-not-included).

**Package:** `io.dataloom.api.configuration`

## Overview

An application resolves one or more layered [`ConfigurationSource`](#configurationsource-and-configurationscope)
objects — for example a built-in defaults layer, a remote-assigned layer,
and a local-override layer — into a single, validated, checksummed
[`ConfigurationSnapshot`](#configurationsnapshot) via
[`DataLoomConfigurationResolver`](#dataloomconfigurationresolver). Applying
resolved snapshots over time, with monotonically increasing versions and
rollback to the last known good version, is
[`DataLoomConfigurationHistory`](#dataloomconfigurationhistory)'s job.

| Concern | Type |
|---|---|
| Typed value + declared schema | [`ConfigurationValue`](#configurationvalue-and-configurationvaluetype), [`ConfigurationSchema`](#configurationschema) |
| Precedence layering | [`ConfigurationSource`](#configurationsource-and-configurationscope), [`ConfigurationScope`](#configurationsource-and-configurationscope) |
| Validation before admission | [`DataLoomConfigurationResolver`](#dataloomconfigurationresolver), [`ConfigurationValidationResult`](#configurationvalidationresult) |
| Immutable, checksummed result | [`ConfigurationSnapshot`](#configurationsnapshot) |
| Versioning and rollback | [`DataLoomConfigurationHistory`](#dataloomconfigurationhistory) |

---

## `ConfigurationKey`

**Type:** `@JvmInline value class`

A non-blank canonical key, same flat shape as `io.dataloom.api.identifier`'s
identifiers and `io.dataloom.api.security.KeyReference`.

---

## `ConfigurationValue` and `ConfigurationValueType`

```kotlin
public enum class ConfigurationValueType { STRING, LONG, DOUBLE, BOOLEAN, SECRET_REFERENCE }

public sealed class ConfigurationValue {
    public abstract val type: ConfigurationValueType
    public data class StringValue(val value: String) : ConfigurationValue()
    public data class LongValue(val value: Long) : ConfigurationValue()
    public data class DoubleValue(val value: Double) : ConfigurationValue()
    public data class BooleanValue(val value: Boolean) : ConfigurationValue()
    public data class SecretReferenceValue(val reference: KeyReference) : ConfigurationValue()
}
```

### Secret references, never secret values

There is deliberately no `ConfigurationValue` variant that carries a raw
secret (a password, API key, or token) directly. A configuration entry that
names secret material must use `SecretReferenceValue`, which wraps a
`KeyReference` (from `io.dataloom.api.security`, see
[integrity and key references](./integrity-and-key-references.md)) — an
opaque label the host application resolves through its own key/secret
store. DataLoom never holds, logs, or exports the underlying secret through
this type.

---

## `ConfigurationSchema`

```kotlin
public data class ConfigurationEntrySchema(
    val key: ConfigurationKey,
    val type: ConfigurationValueType,
    val required: Boolean = true,
)

public class ConfigurationSchema(entries: Collection<ConfigurationEntrySchema>)
```

The closed, declared set of keys a resolver will admit and the type each
must match. Must be non-empty; duplicate keys throw
`IllegalArgumentException`.

### Unknown-key strictness

A source entry whose key is not declared in the schema is an
error-severity validation finding, not a silently ignored value — see
`DataLoomConfigurationResolver.resolve`. There is no permissive or
passthrough mode.

---

## `ConfigurationSource` and `ConfigurationScope`

```kotlin
public enum class ConfigurationScope { BUILT_IN_DEFAULT, REMOTE_ASSIGNED, LOCAL_OVERRIDE }

public class ConfigurationSource(
    val scope: ConfigurationScope,
    entries: Map<ConfigurationKey, ConfigurationValue>,
)
```

One immutable, single-scope layer of raw entries. Precedence is fixed by
`ConfigurationScope`'s declaration order (later wins): `LOCAL_OVERRIDE`
always wins over `REMOTE_ASSIGNED`, which always wins over
`BUILT_IN_DEFAULT`, for the same key — regardless of the order sources are
passed to the resolver in. A `ConfigurationSource` performs no I/O itself;
producing its entries (parsing a remote payload, reading a local override
file, and so on) is the host application's responsibility.

This three-tier model is deliberately small and closed rather than an open,
caller-defined ordering — it covers the common "defaults, overridden
remotely, overridden locally" shape without introducing an extensibility
surface nothing currently needs.

---

## `DataLoomConfigurationResolver`

```kotlin
public class DataLoomConfigurationResolver(
    schema: ConfigurationSchema,
    digestCalculator: DataLoomDigestCalculator,
) {
    public fun resolve(sources: List<ConfigurationSource>, version: Long): ConfigurationResolution
}

public sealed class ConfigurationResolution {
    public abstract val findings: List<ConfigurationValidationFinding>
    public data class Admitted(val snapshot: ConfigurationSnapshot, override val findings: ...) : ConfigurationResolution()
    public data class Rejected(override val findings: ...) : ConfigurationResolution()
}
```

Merges `sources` in ascending `ConfigurationScope` precedence and validates
the result against `schema` before admission:

- **Unknown key** → error finding, entry dropped.
- **Type mismatch** (declared type ≠ supplied value's type) → error finding,
  entry dropped.
- **Missing required key** (no source supplied it at all) → error finding.
  A key that some source *attempted* to supply but was rejected for one of
  the reasons above is not additionally reported as missing — one finding
  per root cause, not per side effect.
- **Same-scope conflict** (two sources at the identical `ConfigurationScope`
  disagree on a key) → warning finding; the later source in the supplied
  list order wins, but this is surfaced rather than silently resolved.

Validation is exhaustive, not fail-fast: every problem in one resolution
attempt is reported, not just the first one found. A result is
`Rejected` only if at least one **error**-severity finding exists;
`Admitted` results may still carry warning findings.

`DataLoomConfigurationResolver` performs no I/O, network access, or
persistence, and does not itself enforce monotonically increasing
`ConfigurationSnapshot.version` numbers across separate calls — that is
`DataLoomConfigurationHistory`'s job below. Must be injected; must not be
accessed through a global singleton.

---

## `ConfigurationSnapshot`

```kotlin
public class ConfigurationSnapshot internal constructor(
    val version: Long,
    entries: Map<ConfigurationKey, ConfigurationValue>,
    val checksum: DataLoomDigest,
) {
    public companion object {
        public fun create(
            version: Long,
            entries: Map<ConfigurationKey, ConfigurationValue>,
            digestCalculator: DataLoomDigestCalculator,
        ): ConfigurationSnapshot
    }
}
```

Deeply immutable, versioned, checksummed bundle of resolved values. Never
changes after construction — applying a new configuration produces a new
snapshot with a higher version; rollback restores a previously produced
snapshot instance rather than reversing mutations.

### Integrity checksum

`checksum` is a `DataLoomDigest` (see
[integrity and key references](./integrity-and-key-references.md)) over a
deterministic, canonical encoding of every entry — entries sorted by
`ConfigurationKey.value`, each rendered as `key type value\n` and UTF-8
encoded, hashed with SHA-256 — computed once at construction via an
injected `DataLoomDigestCalculator`. Two snapshots with identical entries
always produce an identical checksum, regardless of the order entries were
supplied in, and the checksum does not depend on `version`.

The constructor is `internal`: every `ConfigurationSnapshot` must be built
through `create` (directly, or via `DataLoomConfigurationResolver`) so
`checksum` is always genuinely derived from `entries`, never supplied out of
band and possibly mismatched.

---

## `DataLoomConfigurationHistory`

```kotlin
public class DataLoomConfigurationHistory(maxRetainedVersions: Int = 10) {
    public val current: ConfigurationSnapshot?
    public val retainedVersions: List<Long>
    public fun apply(snapshot: ConfigurationSnapshot): ConfigurationApplyOutcome
    public fun rollbackToLastKnownGood(): ConfigurationSnapshot?
}
```

Bounded, in-memory, monotonically versioned history of applied snapshots.

- **`apply`** only accepts a candidate whose version strictly exceeds
  `current`'s version (or any version, if `current` is `null`). A candidate
  with an equal or lower version is rejected
  (`ConfigurationApplyOutcome.VersionNotMonotonic`) and `current` does not
  change.
- **`rollbackToLastKnownGood`** restores the snapshot applied immediately
  before `current` and drops `current` from history. Returns `null` if
  there is no earlier retained snapshot (history has zero or one entries).
  After a rollback, a future `apply` call is judged solely against the
  restored snapshot's version — the version number the rolled-back snapshot
  used is not specially blocked; it succeeds again as long as it still
  exceeds the newly current (restored) version.
- History is bounded by `maxRetainedVersions` (default `10`, must be at
  least `1`); the oldest retained snapshot is discarded once the bound is
  exceeded, which can make rollback unavailable even with prior applied
  versions if they have already aged out.
- Instances are **not** safe for concurrent use from multiple threads.

This is an in-memory primitive only — it does not persist history across a
process restart, and provides no distributed compare-and-set/fencing
guarantee. See [Deliberately not included](#deliberately-not-included).

---

## Deliberately not included

This slice intentionally does not build the full target described in
[ADR-0002](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)'s
`### Configuration` section:

- **Durable/transactional persistence with atomic compare-and-set/fencing.**
  `DataLoomConfigurationHistory` is in-memory only. ADR-0002's separate
  "Durable state" foundation bullet is a materially different, bigger piece
  of work — this slice does not attempt it.
- **The shared cross-subsystem policy engine.** A separate `#93`
  required-scope item on its own, not this one.
- **Wiring into `RuntimeDependencies`/`DataLoomBuilder`, retry
  reclassification, conflict selection, content policy, plugin permissions,
  or administrative overrides.** Adoption is deliberately separate
  follow-up work, the same posture already established for
  `DataLoomSecureRandom`/`DataLoomClock`/the digest and HMAC calculators —
  see their own doc pages' "Neither implementation is wired into
  `RuntimeDependencies`/`DataLoomBuilder` automatically yet" notes.
- **Remote config delivery or fetching.** This is a local
  resolution/validation/rollback primitive only; producing `ConfigurationSource`
  entries (parsing a remote payload, reading a local override file) is the
  host application's job.
- **Feature-flag-specific metadata** (ownership/default/expiry/scope/audit).
  That reads as a distinct feature-flags application layer built on top of
  this primitive, not the primitive itself.
- **Reimplementing redaction.** `DataRedaction` already exists (see
  [operational envelope and redaction](./operational-envelope-redaction.md));
  a `SecretReferenceValue`-tagged entry is where a future integration would
  plug into that existing boundary, not a reason to duplicate it here.
- **An open, caller-defined precedence ordering.** `ConfigurationScope` is a
  closed, three-tier enum; nothing currently needs more than
  default/remote/local-override.

---

## Testing

No platform-specific implementation is required for this slice — everything
lives in `commonMain`, so there is no `System*`/`Apple*` split to test. Tests
use a small deterministic, non-cryptographic `DataLoomDigestCalculator` fake
(this module has no platform-specific source set to host the real
`SystemDataLoomDigestCalculator`/`AppleDataLoomDigestCalculator`
implementations, which live in `dataloom-model`) — same
fake-over-production-implementation posture as
[secure random](./secure-random.md)'s testing note.
