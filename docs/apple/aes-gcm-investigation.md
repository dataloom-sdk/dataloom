# Apple AES-256-GCM: investigated, not achievable as a bounded slice

## Status

**Investigated (2026-10-01). Not achievable as a bounded PR with this
repository's current tooling and security posture.** ADR-0014 (2026-09-28)
already recorded that Apple AES-GCM did not ship in slice 3 and gave a short
reason ("Kotlin/Native's bundled `platform.CoreCrypto` binding exposes only
CBC/ECB/CFB/CTR/OFB modes, and CryptoKit is Swift-only"). This document goes
one level deeper for each of the three realistic paths — CommonCrypto
cinterop, a Swift/CryptoKit shim, and a vendored third-party C
implementation — with direct evidence (exact files, exact tool output, exact
klib inspection) rather than restating the prior summary, and extends the
investigation to the two paths ADR-0014 named but did not evaluate in depth
(the Swift shim and the vendoring option). No code changes in `dataloom-assets`
or its tests. `AesGcmAssetChunkCipher`'s Apple `platformAesGcmSupported() =
false` / `AssetTransformUnsupportedException` behaviour
(`dataloom-assets/src/iosMain/kotlin/io/dataloom/assets/transform/PlatformAesGcm.ios.kt`)
is unchanged. `docs/status/market-readiness.md`'s `#97` row is unchanged by
this document — see the fragment at
`docs/status/fragments/2026-10-01-97-apple-aes-gcm-investigation.md`.

This is a security-sensitive primitive. Per this program's standing posture
("bail honestly, don't fake it"), the conclusion below is a decision not to
guess, not a claim that AES-GCM on Apple is impossible in the abstract — it
clearly is possible (CryptoKit does it natively); the question this document
answers is narrower: is it reachable from *this* Kotlin/Native codebase, on
*this* toolchain, in one bounded, honestly-provable slice. The answer is no,
for three independent reasons below, and each would need either new build
infrastructure this repository does not have, or a security-vendoring
decision this agent is not positioned to make unilaterally.

## What `AesGcmAssetChunkCipher` needs from a platform primitive

Read directly from
`dataloom-assets/src/commonMain/kotlin/io/dataloom/assets/transform/AesGcmAssetChunkCipher.kt`
and its JVM actual
(`dataloom-assets/src/jvmMain/kotlin/io/dataloom/assets/transform/PlatformAesGcm.jvm.kt`):
the `expect`/`actual` SPI is three functions —
`platformAesGcmSupported(): Boolean`,
`platformAesGcmSeal(key, nonce, associatedData, plaintext): ByteArray`
(returns `ciphertext || 16-byte tag`), and
`platformAesGcmOpen(key, nonce, associatedData, sealed): ByteArray` (verifies
the tag, throws `AssetChunkAuthenticationException` on failure). The JVM
implementation is `javax.crypto` `Cipher.getInstance("AES/GCM/NoPadding")`
with a 128-bit `GCMParameterSpec` and `updateAAD`; nothing more exotic than
one-shot AEAD seal/open with a 12-byte nonce and 32-byte key. Any Apple
implementation must satisfy exactly this contract and pass the same
known-answer vectors JVM does
(`dataloom-assets/src/jvmTest/kotlin/io/dataloom/assets/AesGcmAssetChunkCipherTest.kt`:
McGrew & Viega GCM test cases 14 and 16).

## Path (a): CommonCrypto cinterop — confirmed not viable through the public SDK

This project already reaches CommonCrypto from Kotlin/Native for digests
(`dataloom-model/src/iosMain/kotlin/io/dataloom/api/security/AppleDataLoomDigestCalculator.kt`,
using `platform.CoreCrypto.CC_SHA256`/`CC_SHA512` one-shot and
`CC_SHA256_CTX`/`Init`/`Update`/`Final` incremental). That binding is not a
project-authored `.def` file — a repo-wide search for `*.def` files and for
`cinterop`/`defFile` blocks in every `build.gradle.kts` and in
`build-logic/src/main/java/io/dataloom/buildlogic/DataLoomKotlinMultiplatformLibraryPlugin.java`
finds zero matches. `platform.CoreCrypto` is Kotlin/Native's own bundled
platform library, generated once by JetBrains and shipped inside the
Kotlin/Native distribution itself, not something this repository can extend
by writing more Kotlin.

**Direct verification against the exact toolchain this project uses.** This
repo pins `kotlin = "2.4.10"` (`gradle/libs.versions.toml`), and the matching
Kotlin/Native distribution is present locally at
`C:\Users\shiva\.konan\kotlin-native-prebuilt-windows-x86_64-2.4.10`. Its
`.def` file for every Apple target
(`konan/platformDef/ios_arm64/CommonCrypto.def`, and identically for
`ios_simulator_arm64`, `ios_x64`, `macos_arm64`, `macos_x64`, `tvos_*`,
`watchos_*`) reads:

```
depends = darwin posix
language = Objective-C
package = platform.CoreCrypto
modules = CommonCrypto
compilerOpts = -D_XOPEN_SOURCE
```

`modules = CommonCrypto` means this binding is generated from Apple's public
`CommonCrypto` Clang module (the umbrella module every ordinary App
Store app gets when it writes `import CommonCrypto` / `#import
<CommonCrypto/CommonCrypto.h>`) — not from any private or SPI header. Dumping
the actual bound declarations confirms exactly what is and is not reachable:

```
klib dump-metadata "klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.CommonCrypto"
```

The dump (877 lines) contains `CCCryptorCreate`, `CCCryptorCreateFromData`,
`CCCryptorUpdate`, `CCCryptorFinal`, `CCCrypt`, and `CCCryptorCreateWithMode`
— so the *generic* cryptor machinery is bound — but the only `CCMode`
constants present are:

```
kCCModeECB  = 1
kCCModeCBC  = 2
kCCModeCFB  = 3
kCCModeCTR  = 4
kCCModeOFB  = 7
kCCModeRC4  = 9
kCCModeCFB8 = 10
```

`kCCModeF8` (5), `kCCModeLRW` (6), `kCCModeXTS` (8), and `kCCModeGCM` (11) are
all absent — not just GCM. A case-insensitive search of the full dump for
`gcm`, `aead`, `CCCryptorGCM` returns zero matches of any kind: no mode
constant, no `CCCryptorGCMInit`/`CCCryptorGCMAddAAD`/`CCCryptorGCMEncrypt`/
`CCCryptorGCMFinal` function, nothing. This is consistent with (and directly
confirms, rather than merely repeats) ADR-0014's claim. The gap is not a
Kotlin/Native binding oversight: Apple's own public `CommonCryptor.h` header
— the one the `CommonCrypto` Clang module actually exposes — has never
declared GCM mode in `CCMode`, or the dedicated `CCCryptorGCM*` family of
functions. Those live in `CommonCryptorSPI.h`, a private/SPI header that is
not part of the public `CommonCrypto` umbrella module and is not shipped to
third-party apps through the public iOS/macOS SDK.

**Why reaching the SPI header anyway is rejected, not just hard.** It is
*mechanically* possible to write a new `.def` file that declares the
`CCCryptorGCM*` C prototypes by hand (copying the known signatures rather
than parsing Apple's header, since the header itself is absent from the
public SDK) and link against the same `libcommonCrypto.dylib` the public
module already links, because the symbols exist in the compiled library even
though the public header does not declare them. This is exactly the kind of
"private/SPI usage" the existing Swift-interop doc's security posture and
this program's general caution about undocumented surfaces warns against for
an ordinary feature, and it is categorically worse for a security primitive:
Apple gives no ABI/signature stability guarantee for SPI headers across OS
versions (the functions have in fact changed shape across iOS releases
historically), there is no public documentation to verify correct usage
against, and static/App Review tooling can flag private-symbol linkage.
Shipping an SDK's core encryption path on undocumented, unstable, unreviewed
symbols is not a reasonable trade for closing one `TRANSFORM_UNSUPPORTED`
gap. This path is rejected on security grounds, not just effort.

## Path (b): a Swift/CryptoKit shim — zero precedent, needs new cross-host build infrastructure

ADR-0014 named this as a possible future direction without evaluating it.
This repo's *only* existing Swift interoperability is the opposite direction
from what AES-GCM would need, and it is already explicitly documented:
`docs/apple/swift-interop.md` states "Native Swift integration is optional
and distinct from mandatory KMP iOS support" and "Experimental Swift export
is not enabled. The current fixture uses conventional Kotlin/Native
Objective-C/Swift bridging" — meaning Kotlin/Native exports an
Objective-C header that a Swift consumer (`apple-smoke/Sources/
DataLoomSwiftSmoke/DataLoomSwiftSmoke.swift`) imports (`import DataLoom`) and
calls into. A repo-wide search for `*.swift` files found five, and every one
is an application-side consumer of the generated framework
(`apple-smoke/...DataLoomSwiftSmoke.swift`, three `AppDelegate.swift` files
in the process-contention/termination proof apps) — none is a shim that
Kotlin calls *into*. There is no case of the reverse direction (Kotlin/Native
calling Swift) anywhere in this codebase.

Building that reverse bridge for CryptoKit's `AES.GCM` would need, at
minimum: a small Objective-C-compatible Swift shim (CryptoKit's Swift-native
`AES.GCM.seal`/`open` use generics and structs that are not directly
cinterop-friendly, so the shim's exposed surface would have to be plain
`@objc`-compatible methods taking `NSData`/raw pointers); a new Swift
module/target built with `swiftc`/`xcodebuild` as a distinct Gradle build
step (nothing like this exists in `build-logic/src/main/java/io/dataloom/
buildlogic/DataLoomKotlinMultiplatformLibraryPlugin.java` or any
`build.gradle.kts` today — the project's own cinterop usage is 100% against
*bundled* `platform.*` libraries, never a custom module); and a new
Kotlin/Native cinterop `.def` file binding against that shim's generated
Objective-C header, then linking the resulting Swift runtime object code
into the final Kotlin/Native binary and ultimately the XCFramework.

**This cannot even be compile-verified from this Windows host**, which
matters because every other Apple-side slice in this gate's history (frame
versioning, zlib, the file-backed providers, parallel transfer) was at least
cross-compiled and klib-verified locally before relying on macOS CI. Apple
cross-compilation here works through `-Pdataloom.appleKlibCrossCompile=true`,
which lets Kotlin/Native produce **klib metadata** for `iosArm64`/
`iosSimulatorArm64`/`iosX64` using the bundled platform klibs as stand-ins —
it never needs an actual C/Swift compiler or linker for those targets. Direct
evidence: the local Kotlin/Native distribution's own `konan/konan.properties`
defines `targetToolchain.<host>-<target>` entries that provide the real
Xcode-based C/Swift toolchain only for macOS hosts reaching Apple targets
(`targetToolchain.macos_x64-ios_arm64`, `targetToolchain.macos_arm64-ios_arm64`,
etc., all pointing at `target-toolchain-xcode_26.4_17E192`). Searching the
same file for `mingw_x64` (the Windows host triple) finds toolchain entries
only for `mingw_x64-android_*` (the Android NDK); there is no
`mingw_x64-ios_*`/`mingw_x64-macos_*` entry at all. A real Swift shim needs
`swiftc`, which needs Xcode, which needs macOS — there is no cross-compile
story for actually building and linking Swift code from Windows, only for
type-checking plain Kotlin against pre-built platform metadata. This is a
genuinely new, multi-week build-system project (new Gradle tasks invoking
Xcode tooling, a new Swift package/target committed to the repo, CI changes
to `apple-validation.yml` which this agent is explicitly barred from
editing — playbook rule 8 — plus a security review of the new native code
path) that needs a project-lead decision before any agent starts it, not
something a single bounded PR slice can deliver or even partially verify.

## Path (c): a vendored third-party portable C AES-GCM implementation — mechanically closer, but a security-vendoring decision outside this slice's authority

A small, portable, constant-time C AES-GCM implementation (the kind found in
libraries like BearSSL or a minimal audited single-file implementation) could
in principle be cinterop'd the same way CommonCrypto is, since it is plain C
with no Swift/ObjC boundary to cross. Even this path has the same build-time
ceiling as path (b): a vendored `.c` file needs an actual target-specific C
compiler to produce the object code linked into the final Apple binary, and
the toolchain evidence above (`mingw_x64` has no Apple target-toolchain
entry) means this repository's Windows worktrees could, at best, only
type-check a Kotlin-side header declaration against it, never compile or
link the vendored C itself; a real build would still only happen in macOS CI.

That is a secondary concern next to the real one this program's task brief
already flags explicitly: AES-256-GCM is DataLoom's core at-rest
confidentiality/integrity primitive for asset transfer (ADR-0014, D25,
`FR-ASSET-008`). Choosing, vendoring, and committing to maintaining a
third-party cryptographic implementation is a materially bigger trust
decision than binding to the operating system's own vetted library (what the
JVM path already does via `javax.crypto`, and what CommonCrypto would have
been on Apple had GCM been public there) — it means this project, not Apple
or a JCE provider, becomes responsible for that code's correctness against
timing side channels, its freedom from implementation bugs past the known-
answer vectors this slice would run, and its patch lifecycle if a flaw is
found later. That decision is explicitly out of scope for a single agent
slice to make unilaterally; it needs the same kind of recorded project-lead
decision ADR-0014's D24/D25 already were. This document does not recommend a
specific library, precisely to avoid presenting a de facto choice as already
made.

## What is left exactly as it was

- `AesGcmAssetChunkCipher.isSupported` is `false` on Apple;
  `seal`/`open` throw `AssetTransformUnsupportedException`
  (`dataloom-assets/src/iosMain/kotlin/io/dataloom/assets/transform/PlatformAesGcm.ios.kt`,
  unchanged by this investigation).
- The engine's refusal behaviour
  (`AssetTransferOutcome.NotStarted(AssetErrorKind.TRANSFORM_UNSUPPORTED)`
  before any I/O) is unchanged and is still exercised by
  `dataloom-assets/src/iosTest/kotlin/io/dataloom/assets/AppleTransformSupportTest.kt`.
- Apple compression (`DeflateAssetCompressor` via `platform.zlib`) is
  unaffected and remains the only transform available on Apple.
- `docs/status/market-readiness.md`'s `#97` row is unchanged; see
  `docs/status/fragments/2026-10-01-97-apple-aes-gcm-investigation.md`.

## What would need to exist first (ranked by how much new infrastructure it needs)

1. **Smallest, but security-gated, not effort-gated**: a project-lead decision
   to accept a named, vetted, actively-maintained third-party (or
   newly-written and independently reviewed) portable C AES-GCM
   implementation for this SDK's core encryption primitive, recorded as its
   own ADR the way D24/D25 were. Only after that decision exists does the
   mechanical work (cinterop `.def`, Apple-side `actual` functions, the same
   known-answer-vector tests run on JVM) become a bounded slice — and even
   then, the actual Apple compile/link only happens in macOS CI, same as
   every other Apple slice in this gate.
2. **Largest**: a Swift/CryptoKit shim, which needs new Gradle build-system
   work invoking `swiftc`/`xcodebuild` (nothing like this exists in
   `build-logic` today), a new committed Swift target, a new cinterop `.def`
   file against its generated header, and macOS CI changes to
   `apple-validation.yml` — explicitly out of this agent's authority per the
   playbook (rule 8) and not even compile-verifiable from a Windows worktree
   given the toolchain evidence above.
3. **Not pursued**: reaching Apple's private `CommonCryptorSPI.h` GCM
   functions directly. Mechanically the smallest change, but rejected on
   security grounds (undocumented, version-unstable symbols for a core
   encryption primitive) rather than left open as an effort question.

## References

- [ADR-0014](../adr/ADR-0014-asset-chunk-transforms-and-digest-domain.md) —
  the original decision record this document extends.
- [Swift interoperability](swift-interop.md) — confirms the existing Swift
  bridge direction (Kotlin exports, Swift imports) and that no reverse
  bridge exists.
- [Process termination investigation](process-termination-investigation.md) —
  the prior investigation this document's shape and evidentiary standard
  follows.
- `dataloom-assets/src/commonMain/kotlin/io/dataloom/assets/transform/AesGcmAssetChunkCipher.kt`,
  `PlatformAesGcm.jvm.kt`, `PlatformAesGcm.ios.kt`,
  `AesGcmAssetChunkCipherTest.kt`, `AppleTransformSupportTest.kt`.
- Local toolchain evidence (not committed to the repo, reproducible from any
  machine with the same Kotlin/Native distribution installed):
  `%USERPROFILE%\.konan\kotlin-native-prebuilt-windows-x86_64-2.4.10\konan\platformDef\ios_arm64\CommonCrypto.def`,
  `...\konan\konan.properties`, and
  `klib dump-metadata "klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.CommonCrypto"`
  run from that distribution's root.
- NIST SP 800-38D (GCM); McGrew & Viega, "The Galois/Counter Mode of
  Operation" (the test vectors `AesGcmAssetChunkCipherTest` already runs).
