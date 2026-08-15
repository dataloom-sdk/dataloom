# Supply-chain dependency verification

> **Audience:** Contributors regenerating or reasoning about
> `gradle/verification-metadata.xml`
> **Purpose:** Explain what dependency verification proves, its current
> coverage, and how to regenerate it
> **Status:** Lenient mode, Windows-host-generated; not yet reconciled
> against the macOS/Linux CI runners

[Project overview](../../README.md) ·
[Build and validate locally](./building.md)

## What this is

`gradle/verification-metadata.xml` is Gradle's own built-in dependency
verification feature. It records a SHA-256 checksum for every dependency
artifact (jar, `.module`, `.pom`) this repository has resolved so far. On
every subsequent build, Gradle re-hashes each downloaded artifact and
compares it against the recorded value before it is placed on a compile or
runtime classpath.

This is `#93`'s **supply-chain evidence** security primitive. Unlike the
other five primitives in that gate (integrity, signature/key references,
redaction, input validation, least privilege — see
[the API reference index](../api/README.md)), there is no plausible runtime
Kotlin API for this concern: it protects the *build's own dependencies*, not
data the SDK processes at runtime. Gradle's own verification-metadata
mechanism is the concrete, non-speculative equivalent.

## What it actually defends against

A dependency whose published artifact bytes differ from what was previously
reviewed and pinned here — for example a compromised Maven repository
mirror, a tampered proxy cache, or a supply-chain substitution attack — now
produces a visible, attributed warning (`kotlin-test-2.4.10.jar ...
This can indicate that a dependency has been compromised`) instead of
silently entering the build.

It does **not** defend against: a legitimately-published new version of a
dependency being intentionally malicious (that is a dependency-review
process concern, not this file's job), transitive dependency confusion at
the repository-resolution level, or anything about DataLoom's own published
artifacts (that is signing/publication evidence, a separate, still-open
`#100` release-gate concern).

## Current coverage and why it is lenient, not strict

`gradle.properties` sets `org.gradle.dependency.verification=lenient`. In
lenient mode, a missing or mismatched checksum is logged as a warning and
the build continues; in the default **strict** mode, it fails the build.

Lenient mode is the deliberate, honest choice for this checkpoint, not a
placeholder that was meant to be strict and got forgotten:

- The committed metadata was generated from **one Windows host**, running
  `./gradlew --write-verification-metadata sha256 build --continue` with
  `DATALOOM_ANDROID_BUILD=true` and `-Pdataloom.appleKlibCrossCompile=true`
  set, so it captures the shared/JVM, Android, and Kotlin/Native
  cross-compiled dependency graph resolvable from Windows — roughly 770
  components, including Gradle plugin/buildscript dependencies.
- It has **not** been reconciled against `apple-validation.yml`
  (`macos-15`) or `android-validation.yml`/`pr-validation.yml`
  (`ubuntu-latest`) — those runners can resolve additional
  platform-specific artifacts (for example Xcode/Kotlin-Native macOS host
  toolchain components) this Windows host never touches. Switching to
  strict mode without first regenerating from those runners would very
  likely fail CI on an artifact this file has no entry for — exactly the
  kind of GitHub Actions-as-interactive-debugger loop `#93` explicitly
  warns against.
- The one known, expected local build failure while generating this file —
  `dataloom-storage-datastore`'s unit tests, which fail on Windows due to a
  documented `androidx.datastore` file-move limitation, not a product bug —
  did not prevent metadata collection for the rest of the build, since the
  generation ran with `--continue`.

Verified the mechanism is genuinely active, not inert configuration: a
`kotlin-test-2.4.10.jar` checksum was deliberately corrupted locally, a
rebuild produced Gradle's real "may have been compromised" verification
failure report, the build still completed because of lenient mode, and the
checksum was then restored. This was a manual, one-time proof — it is not a
repeatable test in this repository.

## Regenerating the metadata

Whenever a dependency version changes, run the same command used to
generate the committed file so new artifacts get a recorded checksum:

```bash
DATALOOM_ANDROID_BUILD=true ./gradlew \
  -Pdataloom.appleKlibCrossCompile=true \
  --write-verification-metadata sha256 \
  build --continue
```

Review the resulting `git diff` on `gradle/verification-metadata.xml`
before committing — every new entry should correspond to a dependency
version bump you recognize, not an unexplained addition.

## Path to strict mode

Not attempted in this checkpoint. It requires running the same
`--write-verification-metadata` command from a macOS runner (for
`apple-validation.yml`'s Kotlin/Native host toolchain artifacts) and a
Linux runner (for `android-validation.yml`/`pr-validation.yml`), merging
the resulting `<components>` entries into one file, then flipping
`org.gradle.dependency.verification` to `strict` (or removing the property,
since strict is Gradle's default) once local + all three CI legs pass
cleanly with it enabled.
