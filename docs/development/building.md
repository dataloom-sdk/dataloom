# Building DataLoom Locally

This guide covers the tools required to build DataLoom, the Gradle Wrapper,
available build commands, and common troubleshooting steps.

---

## Required Tools

| Tool | Required version |
|---|---|
| JDK | 17 or newer |
| Gradle | 9.5.0 (via the Gradle Wrapper — no separate installation required) |
| Kotlin | 2.4.10 (managed by Gradle) |

You do not need to install Gradle or Kotlin manually. The Gradle Wrapper
(`gradlew` / `gradlew.bat`) downloads and caches the correct Gradle
distribution automatically.

---

## Verifying Your Java Installation

```bash
java -version
```

Expected output (version must be 17 or newer):

```
openjdk version "17.x.x" ...
```

On Windows:

```powershell
java -version
```

---

## The Gradle Wrapper

Always use the Gradle Wrapper instead of a locally installed Gradle binary to
ensure the correct version is used.

- Unix/macOS: `./gradlew`
- Windows: `.\gradlew.bat`

The wrapper downloads Gradle 9.5.0 from `https://services.gradle.org/` on
first use and caches it in `~/.gradle/wrapper/dists/`.

### Wrapper Checksum Verification

The Gradle Wrapper is configured with the official SHA-256 checksum for the
`gradle-9.5.0-bin.zip` distribution:

```
553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746
```

Gradle verifies this checksum automatically before extracting the distribution.
If the download is corrupted or tampered with, the build fails with a checksum
mismatch error.

The checksum was sourced from the official Gradle release-checksums page:
https://gradle.org/release-checksums/

The wrapper JAR itself (`gradle/wrapper/gradle-wrapper.jar`) can be verified
against the official wrapper JAR checksum for Gradle 9.5.0:

```
497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
```

To verify on Unix/macOS:

```bash
sha256sum gradle/wrapper/gradle-wrapper.jar
```

To verify on Windows (PowerShell):

```powershell
Get-FileHash gradle\wrapper\gradle-wrapper.jar -Algorithm SHA256
```

---

## Gradle and Kotlin Versions

To confirm the active Gradle and Kotlin versions:

```bash
./gradlew --version
```

---

## Build Commands

### List all projects

```bash
./gradlew projects
```

### Build all modules

```bash
./gradlew build
```

### Build with configuration-cache validation

```bash
./gradlew build --configuration-cache
```

The configuration cache is enabled by default in `gradle.properties`. This
command confirms that the build graph is fully configuration-cache compatible.

### Run verification tasks with configuration-cache validation

```bash
./gradlew check --configuration-cache
```

### Run all tests

```bash
./gradlew :dataloom-api:allTests
./gradlew :dataloom-core:allTests
./gradlew :dataloom-runtime:allTests
./gradlew :dataloom-testing:allTests
```

Or all at once:

```bash
./gradlew allTests
```

### Inspect module dependencies

To view the resolved runtime classpath for a specific module:

```bash
./gradlew :dataloom-runtime:dependencies --configuration jvmRuntimeClasspath
```

Replace `:dataloom-runtime` with any module name to inspect its dependency
tree.

---

## Pull request validation in GitHub Actions

DataLoom pull requests targeting `main`, pushes to `main`, and manual workflow
runs are validated in GitHub Actions by the **Pull Request Validation**
workflow.

The workflow uses:

- JDK 17 (Temurin)
- The committed Gradle Wrapper
- `gradle/actions/setup-gradle` with the `basic` cache provider

Validation executes:

- `./gradlew --version`
- `./gradlew projects`
- `./gradlew build --configuration-cache`
- `./gradlew check --configuration-cache`
- `./gradlew :dataloom-api:allTests :dataloom-core:allTests :dataloom-runtime:allTests :dataloom-testing:allTests`
- `./gradlew :dataloom-runtime:dependencies --configuration jvmRuntimeClasspath`

Before requesting review, run the same commands locally to confirm your branch
matches CI expectations.

This workflow validates build and verification only. It does not publish
artifacts, create releases, or deploy services.

---

## Windows Equivalents

Replace `./gradlew` with `.\gradlew.bat` in all commands above:

```powershell
.\gradlew.bat --version
.\gradlew.bat projects
.\gradlew.bat build
.\gradlew.bat :dataloom-api:allTests
```

---

## Common Setup Problems

### Build fails with `Unsupported class file major version`

The JDK version is too old. Java 17 or newer is required. Verify with:

```bash
java -version
```

### `JAVA_HOME` not set

Ensure `JAVA_HOME` points to a JDK 17 installation and that `$JAVA_HOME/bin`
is on your `PATH`.

### Network errors when downloading Gradle

If the Gradle Wrapper cannot download the distribution (for example in an
air-gapped environment), pre-install Gradle 9.5.0 and set
`GRADLE_HOME` or configure the wrapper to point to a local mirror in
`gradle-wrapper.properties`.

### Configuration cache problems

If a task is not configuration-cache compatible, Gradle reports a problem
during the store phase. Check the problems report at:

```
build/reports/problems/problems-report.html
```

Configuration cache is enabled by default. If you need to disable it
temporarily during investigation, run with:

```bash
./gradlew build --no-configuration-cache
```

Do not commit a change that disables the configuration cache without
documenting the reason.
