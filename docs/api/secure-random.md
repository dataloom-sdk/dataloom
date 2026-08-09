# DataLoom Secure Random

[API reference index](./README.md)

> **Status:** Available secure-randomness contract with production JVM/Android
> and Apple implementations. SDK-wide adoption (key/nonce/token generation
> using this boundary instead of an ad hoc source) remains a separate, broader
> V1 gap — this page documents the primitive itself, not that every consumer
> has adopted it yet.

**Package:** `io.dataloom.api.random`

## Overview

`DataLoomSecureRandom` provides a platform-independent abstraction for
obtaining cryptographically secure random bytes. It is a distinct primitive
from `io.dataloom.runtime.retry.RetryRandomSource`, which is intentionally
**deterministic and reproducible** for retry-jitter scheduling and explicitly
documents that it must not be used for security purposes.

| | `DataLoomSecureRandom` | `RetryRandomSource` |
|---|---|---|
| Purpose | Unpredictable values for security-sensitive use | Deterministic retry-jitter scheduling |
| Reproducible from the same input? | No, by design | Yes, by design |
| Backed by | Platform CSPRNG | Seeded deterministic hash |
| Safe for key/nonce/token generation? | Yes | No — explicitly documented as unsafe |

Do not substitute one for the other.

---

## `DataLoomSecureRandom`

**Type:** `interface`

```kotlin
public interface DataLoomSecureRandom {
    public fun nextBytes(byteCount: Int): ByteArray
}
```

### `nextBytes(byteCount)`

Returns a new `ByteArray` of exactly `byteCount` cryptographically secure
random bytes.

- `byteCount` must be greater than zero; implementations throw
  `IllegalArgumentException` otherwise.
- Does not read a clock or depend on a caller-supplied seed.
- Returned bytes must never be logged, persisted in diagnostics, or included
  in `toString()` output by any caller.

---

## Production implementations

| Implementation | Target | Backing |
|---|---|---|
| `SystemDataLoomSecureRandom` | JVM (also serves native Android today; see [clock docs](./clock.md) for why) | `java.security.SecureRandom` |
| `AppleDataLoomSecureRandom` | `iosArm64`, `iosSimulatorArm64`, `iosX64` | `arc4random_buf` (Apple's documented recommendation for secure random bytes on Darwin) |

Both live in `dataloom-model`, have no mutable state, and are safe to share
across threads.

Neither implementation is wired into `RuntimeDependencies`/`DataLoomBuilder`
automatically yet, and no current subsystem (key generation, nonce generation,
token generation) consumes this boundary — those remain open follow-up work,
same as the equivalent note on the [clock page](./clock.md).

---

## Testing

There is no shared `dataloom-testing` fake for `DataLoomSecureRandom` yet.
Tests that need deterministic "random" bytes for reasons other than actually
exercising randomness should inject a small test-local fake rather than the
production implementations, exactly as clock/monotonic-clock tests already do.
