# DataLoom Least-Privilege Capabilities

[API reference index](./README.md)

> **Status:** Available primitive with no production enforcement engine
> wired to it yet. Plugin permission enforcement (`#98`), administrative
> overrides, and provider capability restriction are all named consumers in
> the `#93` acceptance criteria that may adopt this primitive later; none
> currently do. This page documents the primitive itself, not that any
> subsystem has adopted it.

**Package:** `io.dataloom.api.security`
**Module:** `dataloom-model`

## Overview

This page covers the **least privilege** capability of the `#93`
security-primitives foundation gate. The other five capabilities in that
gate's acceptance criterion are documented separately: integrity and
signature/key references in
[integrity and key references](./integrity-and-key-references.md),
redaction in [operational envelope and redaction](./operational-envelope-redaction.md),
and input validation alongside redaction's `isBoundedToken` primitive.

| Concern | Type | Purpose |
|---|---|---|
| Capability identity | [`Capability`](#capability) | A stable, comparable label for one grantable capability or permission |
| Deny-by-default grant | [`GrantedCapabilities`](#grantedcapabilities) | The exact set of capabilities one actor holds — nothing implicit |
| Authorization check | [`isAuthorized`](#isauthorized) | The one shared "does this actor hold X" function every subsystem can reuse |

DataLoom does not itself grant, revoke, persist, distribute, or enforce
capabilities. `#93`'s own required scope is to *establish* a security
primitive, not to build every subsystem's enforcement engine on top of it —
the same split already documented on
[`PluginManifest`](../../dataloom-plugin-api/src/commonMain/kotlin/io/dataloom/api/plugin/PluginManifest.kt):
declaring what is requested is a contract concern; deciding what is granted
and enforcing it is a runtime concern owned by each consumer (for plugins,
that is `#98`, the plugin lifecycle engine).

---

## `Capability`

**Type:** `value class`

```kotlin
@JvmInline
public value class Capability(public val value: String)
```

A stable label, for example `storage.read` or `network.push`. Validated as
non-blank; preserved exactly as supplied. Carries no grant, scope, or
expiry — it is pure identity, the same shape `PluginCapability` and
`PluginPermission` already use one module up in `dataloom-plugin-api`, just
generalized so any subsystem can depend on it without a plugin-specific
dependency.

---

## `GrantedCapabilities`

**Type:** `class`, immutable

```kotlin
public class GrantedCapabilities {
    public val size: Int
    public fun isEmpty(): Boolean
    public fun holds(capability: Capability): Boolean
    public fun holdsAll(capabilities: Set<Capability>): Boolean

    public companion object {
        public val None: GrantedCapabilities
        public fun of(capabilities: Set<Capability>): GrantedCapabilities
    }
}
```

An immutable, deny-by-default grant: an actor holds exactly the capabilities
passed to `of(...)`, never more. There is no wildcard entry and no implicit
grant path. `of(...)` returns the shared `None` singleton for an empty set
and rejects more than 64 entries — the same bounded-cardinality discipline
`PluginManifest` and `PolicySet` already apply, and a direct instance of
`#93`'s own "no ... unbounded-cardinality values" acceptance criterion.
`toString()` renders only a count, never the held labels, matching
`PluginManifest`'s own redaction-conscious `toString()`.

---

## `isAuthorized`

**Type:** `fun`

```kotlin
public fun isAuthorized(requested: Set<Capability>, granted: GrantedCapabilities): Boolean
```

The one shared least-privilege check: `true` only when every entry of
`requested` is present in `granted`. There is no default-allow path and no
partial match on a non-empty request — a single missing capability denies
the whole request. An empty `requested` set is trivially authorized, since
asking for nothing cannot be denied.

---

## Deliberately not included

- **Grant issuance, persistence, or distribution.** `GrantedCapabilities` is
  an in-memory value produced however the caller likes (parsed from a
  `PluginManifest`, read from an administrative record, hard-coded for a
  test) — this module does not decide where a grant comes from.
- **Enforcement wiring into `dataloom-plugin-api`.** `PluginManifest`'s own
  KDoc explicitly reserves deny-by-default enablement and least-privilege
  permission grants for the plugin lifecycle engine (`#98`). Wiring this
  primitive into plugin permission checks now would be speculative
  infrastructure ahead of that concrete consumer, not a completion of this
  primitive's own scope.
- **Role hierarchies, capability inheritance, or time-bounded grants.** Every
  `#93` primitive shipped so far (`KeyReference`, `isBoundedToken`,
  `PolicyEvaluator`) stays deliberately minimal until a real consumer
  demands more; this one follows the same discipline.

## Testing

`LeastPrivilegeTest.kt` (`dataloom-model/src/commonTest`) covers: blank
`Capability` rejection; `None`'s empty behavior; `of(...)` granting exactly
the supplied set with nothing implicit; the 64-entry bound (rejected at 65,
accepted at exactly 64); `holdsAll` requiring every entry; `isAuthorized`
denying a partially-granted request, allowing a fully-granted request,
denying any non-empty request against `None`, and trivially allowing an
empty request; value equality independent of construction order; and that
`toString()` never renders individual capability labels.
