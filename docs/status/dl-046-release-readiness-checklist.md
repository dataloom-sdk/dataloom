# DL-046 (`#100`) release-readiness checklist: investigated, no bounded slice available yet

[Market-readiness dashboard](./market-readiness.md)

## Status

**Investigated (2026-08-24). No genuinely bounded, decision-free implementation
slice found for `#100` itself.** This document is the scoping deliverable in
its place: a precise, evidence-based checklist cross-referencing every
acceptance-criteria item and required-scope item named in GitHub issue `#100`
against what actually exists on `main` today, following
[`docs/architecture/artifact-graph-bom-gap-analysis.md`](../architecture/artifact-graph-bom-gap-analysis.md)'s
(`#93`, `#354`) gap-table structure and rigor.

`#100`'s row in `docs/status/market-readiness.md` is **unchanged at 10%** by
this document — a scoping/checklist document alone is not shipped progress,
per this session's own established precedent (the artifact-graph/BOM gap
analysis above, `docs/apple/process-termination-investigation.md`,
`docs/api/configuration-resolver-caller-investigation.md`).

## Why `#100` is structurally different from every other open gate

`#100` (DL-046) is the release rollup gate. Its own issue text is explicit
that it is "not permission to publish before the implementation/security/
legal/evidence gates pass," and its first acceptance-criteria line is
literally "`#93`, `#94`, `#95`, `#96`, `#97`, `#98`, and `#99` are closed with
linked evidence; `#91` has no unresolved release blocker" — plus `#101` and
`#102`, added as release blockers by later issue comments. Every one of those
nine gates is open today (0 of 10 gates accepted per the dashboard header).
Structurally, almost everything `#100` requires either (a) depends on one or
more of those nine gates closing first, or (b) depends on three human
business/legal decisions this session has already correctly escalated and
deferred rather than deciding unilaterally: **namespace/group-ID ownership,
exact license text, and signing-identity/key custody** (see
`docs/architecture/artifact-graph-bom-gap-analysis.md`'s "Why even the
narrowest possible slice is blocked, not just unattempted" section for the
same finding applied to `#93`'s publication wiring).

## Part 1 — verifying the "foundations exist" claim precisely

`#100`'s dashboard row currently reads: *"Continuous JVM, Android, Apple,
ABI, XCFramework, header, Swift-smoke, schema, and migration validation
foundations exist."* Rather than trust that at face value, each item was
checked directly against `.github/workflows/` and the build.

| Named foundation | Verified? | Evidence |
|---|---|---|
| Continuous JVM | Confirmed | `.github/workflows/pr-validation.yml` runs `./gradlew :build-logic:test build :runtime-external-consumer:checkRuntimeExternalConsumer` on every PR/push to `main` (`ubuntu-latest`), compiling and testing the JVM target across the whole build. |
| Continuous Android | Confirmed | `.github/workflows/android-validation.yml` (45 min budget) assembles/tests/lints all four Android integration modules, runs a real Gradle Managed Device (`pixel2Api35`) instrumented-test pass, and verifies committed Room schema against KSP-generated output. |
| Continuous Apple | Confirmed | `.github/workflows/apple-validation.yml` (`macos-15`, 60 min budget) compiles all three iOS Kotlin/Native targets, assembles the XCFramework, and runs a Swift smoke test via `xcodebuild`. |
| ABI | Confirmed, narrower than "baseline compatibility" implies | `build-logic`'s `DataLoomKotlinMultiplatformLibraryPlugin` calls Kotlin's built-in `kotlin.abiValidation()` for every module except `runtime-external-consumer`, comparing generated dumps against committed `*/api/*.api` / `*.klib.api` files on every `check`/`build`. A second, custom `PublicAbiBoundaryCheckTask` additionally rejects `io.dataloom.core`/`io.dataloom.testing` markers leaking into `dataloom-runtime`'s public ABI. This is real, enforced drift protection for the *current* source tree — not yet an API/ABI baseline frozen against a previously *released* version, since no version has ever been released (`#100`'s own "public API/ABI baseline" acceptance item is about freezing against the immutable candidate, a step that has no candidate to freeze against yet). |
| XCFramework | Confirmed | `apple-validation.yml` assembles `DataLoom.xcframework` and explicitly verifies both the physical-device (`ios-arm64`) and merged-simulator (`ios-arm64_x86_64-simulator`) slices exist. |
| Header | Confirmed | The same workflow's "Audit exported Apple headers" step greps generated `DataLoom.h` files for forbidden `dataloom_core`/`dataloom_testing` markers and diffs the device/simulator headers for byte-identical export shape. |
| Swift-smoke | Confirmed | `apple-validation.yml` compiles a real `DataLoomSwiftSmoke` Xcode scheme against the freshly assembled XCFramework with `xcodebuild build`. |
| Schema | Confirmed, Room-only | `android-validation.yml`'s "Verify committed Room schema" step re-derives the KSP-generated Room database identity hash and fails the build if it does not match the committed schema JSON, for both `dataloom-queue-room` and `dataloom-storage-room`. This is schema-drift detection, not full durable-schema-migration qualification across every storage provider — SQLDelight, file-based, and DataStore have no equivalent generated-schema check (they have no code-generated schema to diff against). |
| Migration | Confirmed, one storage provider only | `dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/DataLoomRoomMigrationTest.kt` exists and runs as part of the Android instrumented-test suite. `#100`'s own required scope asks for "durable schema migrations, restart/recovery, concurrency, fault injection... qualification" as a release-candidate-level acceptance item across the whole product — this is the first-tier CI foundation the row's wording accurately calls it, not that broader qualification. |

**Conclusion: the row's "foundations exist" claim is accurate as written** —
every named item has real, executing CI evidence, not just documentation.
None of the nine items constitutes release-candidate-level qualification by
itself, and the row's own "Still pending" column already says so.

## Part 2 — acceptance-criteria cross-reference

Each checkbox from `#100`'s "Release acceptance criteria" section, checked
against current evidence.

| # | Acceptance criterion | Status | Evidence / blocked by |
|---|---|---|---|
| 1 | `#93`, `#94`, `#95`, `#96`, `#97`, `#98`, `#99` closed with linked evidence; `#91` has no unresolved release blocker | **Blocked** | All seven open today: `#93` 87%, `#94` 70% (QUALIFICATION BLOCKED), `#95` 55%, `#96` 47%, `#97` 5%, `#98` 15% (NOT STARTED), `#99` 10% (NOT STARTED) — per `docs/status/market-readiness.md`'s full gate table. |
| 2 | All mandatory functional/NFR/security/acceptance/compatibility/platform/migration/performance/failure-injection gates pass on the same commit | **Blocked** | Depends on item 1 and on `#101` (70%) — same-commit, cross-gate qualification cannot exist while the gates themselves are still open. |
| 3 | External consumers resolve and run against staged artifacts without composite-build/project substitution | **Blocked, confirmed by direct inspection** | `runtime-external-consumer/build.gradle.kts` depends on `project(":dataloom-model")`, `project(":dataloom-api")`, `project(":dataloom-runtime")`, etc. — exactly the project-substitution shape this criterion forbids for release qualification. There is no staged Maven repository to resolve from instead, because nothing is published (see Part 3). |
| 4 | Artifact contents, POM/module metadata, BOM constraints, checksums, signatures, SBOM, provenance, and licenses verified | **Blocked** | Zero of `dataloom-bom`'s constraints exist because `dataloom-bom` itself is not built (`docs/architecture/artifact-graph-bom-gap-analysis.md`, Gap table 1). No module has POM/module metadata because no module has `maven-publish` wiring (same document, "Publication mechanism: confirmed absent everywhere"). |
| 5 | No public API leaks internal implementation types; API/ABI reports reviewed | **Partially evidenced, ongoing** | `PublicAbiBoundaryCheckTask` and the Apple header audit already enforce this continuously (Part 1). "Reviewed" as a release-candidate sign-off step has no candidate to review yet. |
| 6 | Documentation and samples match the candidate exactly | **Not started** | No candidate exists to match against. |
| 7 | Owner/legal/security/release approvals recorded | **Not started — human decision, not engineering** | Per `#100`'s own 2026-07-31 audit comment, this issue is "the single owner of the remaining human/publication prerequisites transferred from completed scope issue `#92`: exact license text, namespace ownership, developer/organization/SCM metadata, release approver, protected publication environment, signing identity/key custody, and publication credentials." None of these has an owner-side answer recorded in the repository yet (`README.md`'s License section: "to be finalized before V1 publication"; no `LICENSE` file exists). |
| 8 | Release tag and GitHub release created only after staging evidence accepted | **Not started** | Depends on items 3–4. |
| 9 | Production promotion uses the exact immutable candidate; no rebuild between qualification and promotion | **Not started** | No candidate exists yet to promote. |
| 10 | Post-publish resolution/smoke verification succeeds; rollback/revocation instructions ready | **Not started** | Depends on items 3, 8, 9. |

**Zero of the ten acceptance criteria can be marked complete today**, and
nine of the ten are blocked on work this document cannot itself perform
(closing other gates, or human legal/business decisions). Only items 5 and 6
have any partial foundation, and both are continuously-enforced *process*,
not a one-time release-candidate sign-off — because there is no candidate.

## Part 3 — investigated candidates for a bounded first slice today

The task that produced this document named three candidates to verify
concretely rather than assume. All three were investigated; none is
genuinely bounded and decision-free.

### (a) A formal release-readiness checklist cross-referencing evidence

**This is what Parts 1 and 2 of this document are.** Real scoping value,
zero code — the same category of contribution
`docs/architecture/artifact-graph-bom-gap-analysis.md` made for `#93`. This
is the deliverable this investigation actually produces.

### (b) Defining the qualification PROCESS/checklist separately from attempting a release

Investigated whether "qualify one immutable candidate" could mean something
narrower and achievable now — defining the process a future candidate must
pass, without attempting to build or publish one. This document's Part 2
table already *is* that process definition: it names, for each acceptance
criterion, exactly what evidence a future candidate will need to produce.
Writing a second, separate "process document" restating the same ten items
in prose would duplicate this table without adding new information — the
cross-reference table itself is the bounded, decision-free artifact this
candidate points at, not a distinct deliverable.

### (c) Wiring SBOM-generation tooling now, independent of namespace/license/signing

This was the most promising candidate to check concretely, since a
CycloneDX- or SPDX-style Gradle plugin can technically run without a
`group`/`version`/license/signing decision. Investigated and found it is
**not** a genuinely decision-free `#100` slice, for two independent reasons:

1. **`#100`'s own acceptance criteria scope SBOM/provenance/signature
   generation to "from the immutable candidate"** (issue body: *"Generate
   checksums, cryptographic signatures, SBOM, provenance/attestations,
   dependency inventory, vulnerability evidence, and license/compliance
   evidence from the immutable candidate"*). An SBOM generated today would
   describe source modules with no `group`, no released `version`, and zero
   of the twelve `io.dataloom` published coordinates it should eventually
   describe (`docs/architecture/artifact-graph-bom-gap-analysis.md` confirms
   zero of 12 are publication-ready). That is exactly the "artifact name
   without owned behavior and compatibility evidence" shape ADR-0002's own
   "Rejected alternatives" section already rejects for empty artifact
   wrappers — an SBOM with no real candidate behind it is the SBOM
   equivalent of that same anti-pattern, not genuine release evidence.
2. **The third-party dependency-inventory half of this concern is already
   separately covered, by `#93`, not `#100`.**
   `docs/development/supply-chain-verification.md` documents
   `gradle/verification-metadata.xml` — Gradle's own dependency-verification
   feature, already tracking SHA-256 checksums for ~770 resolved
   dependencies — and states explicitly: *"It does **not** defend against...
   anything about DataLoom's own published artifacts (that is
   signing/publication evidence, a separate, still-open `#100` release-gate
   concern)."* That document's own "Path to strict mode" section is itself
   an already-identified, still-open, independently-schedulable slice — but
   it belongs to `#93`'s supply-chain-evidence primitive, not `#100`, and
   this investigation must not fold another gate's already-scoped pending
   work into `#100`'s row just because it is adjacent.

Adding a new, unverified Gradle plugin dependency to a ~30-module build also
carries independent risk today specifically: `gradle/verification-metadata.xml`
is lenient-mode and generated from a single Windows host, not yet reconciled
against the macOS/Linux CI runners (per the same supply-chain-verification
doc) — a new plugin's own transitive dependencies would need checksums added
across three platforms before this repository's own stated path to strict
verification could even consider them, work with its own prerequisites this
document is not positioned to take on as a side effect of `#100`.

**Conclusion: no genuinely bounded, decision-free implementation slice
exists for `#100` today**, beyond the checklist itself. This matches the
same structural finding this session already recorded for `#93`'s
publication wiring (`docs/architecture/artifact-graph-bom-gap-analysis.md`)
and the KMP Android target
(`docs/android/kmp-android-target-blocker.md`) — investigated and confirmed
blocked, not merely unattempted.

## Ordered checklist for future rounds

Independently schedulable except where a dependency is named. None of these
may be started as a silent side effect of another gate's slice.

1. **Resolve the three human/business decisions `#100` owns**: namespace/
   group-ID ownership and release authority for `io.dataloom`, exact V1
   license text, and signing-identity/key custody. These block almost
   everything below and are explicitly out of scope for an engineering
   session to decide unilaterally (already correctly escalated).
2. **Close `#93`–`#99`, `#101`, `#102`** — each gate's own dashboard row
   names its independently-schedulable next slices; `#100` cannot progress
   materially until they do.
3. **Once 1–2 are far enough along, build the `maven-publish` wiring and
   `dataloom-bom`** per `docs/architecture/artifact-graph-bom-gap-analysis.md`'s
   own ordered checklist (its items 1–10), which this document defers to
   rather than repeats.
4. **Stage one qualification pipeline run**: publish to a staging repository,
   point `runtime-external-consumer` (and a native Android / KMP Android /
   KMP iOS staged consumer per `#101`) at the staged coordinates instead of
   `project(...)` substitution, and confirm resolution succeeds without
   composite builds.
5. **Generate SBOM/provenance/checksums/signatures from that staged
   candidate specifically** — not before one exists — once 1, 3, and 4 are
   done. Reconcile `gradle/verification-metadata.xml` to strict mode across
   all three CI hosts first or in parallel, as `#93`'s own already-scoped
   next step (`docs/development/supply-chain-verification.md`, "Path to
   strict mode").
6. **Complete documentation set**: README, API reference, integration
   guide, migration guide, operations/runbook, support/LTS policy, security
   guidance, release notes — matched against the exact staged candidate from
   step 4, not written speculatively ahead of it.
7. **Obtain recorded owner/legal/security/release approvals** referencing
   the specific staged candidate and the decisions from step 1.
8. **Rehearse staging publication and rollback/revocation** before any
   production promotion is attempted.
9. **Promote the exact same candidate qualified in steps 4–8** — no rebuild
   between qualification and promotion, per `#100`'s own no-go rule.
10. **Post-publish resolution/smoke verification** against the promoted
    artifacts, with rollback instructions ready and rehearsed from step 8.

## What is not in question

- Every CI foundation named in the dashboard row genuinely exists and
  executes on every PR (Part 1) — this document found no gap in what is
  already claimed there, only precision about what each item does and does
  not cover.
- This finding does not change `#100`'s percentage (10%, unchanged) or
  status (`BLOCKED / NO-GO`, unchanged) — a checklist is scoping work, not
  shipped release progress, per this session's established precedent.
- This document does not attempt to resolve the namespace, license, or
  signing decisions it names as blocking. Those remain the user's own
  business decisions, already correctly escalated earlier this session.

## References

- GitHub issue `#100` — DL-046 immutable V1 release (full required scope,
  acceptance criteria, and no-go rule)
- GitHub issue `#91` — full implementation and release-readiness audit
- GitHub issue `#92` — full V1 scope decision (source of the transferred
  license/namespace/signing prerequisites)
- [ADR-0002: V1 artifact and foundation architecture](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)
- [Artifact graph/BOM gap analysis](../architecture/artifact-graph-bom-gap-analysis.md) — `#93`'s
  own investigation this document builds on and defers its publication
  checklist to, rather than duplicating
- [Supply-chain dependency verification](../development/supply-chain-verification.md) —
  `#93`'s already-scoped, separate dependency-inventory evidence and its own
  "Path to strict mode" next step
- `docs/android/kmp-android-target-blocker.md` — the same "investigated and
  confirmed blocked" pattern this document follows
- `.github/workflows/pr-validation.yml`, `android-validation.yml`,
  `apple-validation.yml` — source of every Part 1 verification
- `docs/status/market-readiness.md` — full V1 gate table and current
  per-gate percentages cited in Part 2
