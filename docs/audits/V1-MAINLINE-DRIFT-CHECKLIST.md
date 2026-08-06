# V1 mainline drift and integration safety checklist

Status: **active Audit-02 control record**  
Initial audited `main`: `c7e16b71d711cb7c82406c84aca76428f44e0328`  
Owner: `Audit-02` — Mainline Drift and Quality Sentinel  
Program control: issue `#188`; audit work item: issue `#190`

## Purpose

This checklist is the independent integration-safety gate for every production
candidate and every accepted merge. It complements the
[V1 requirements and evidence matrix](./V1-REQUIREMENTS-EVIDENCE-MATRIX.md):

- Audit-01 asks whether the complete V1 requirement is implemented and
  qualified.
- Audit-02 asks whether a candidate preserves correctness, compatibility,
  security, platform parity, validation integrity, and truthful documentation.

A green workflow is necessary but insufficient. Audit-02 reviews the exact
source diff and executable evidence on one immutable SHA before a production
candidate enters the merge queue.

## Audit checkpoints

Audit-02 runs at four points:

1. before a builder opens a pull request;
2. on the final immutable pull-request SHA;
3. after the accepted merge reaches `main`; and
4. before an immutable release candidate is staged or promoted.

For a pull request, compare:

```text
audited-main-SHA..final-PR-SHA
```

After merge, compare:

```text
previous-audited-main-SHA..new-main-SHA
```

The post-merge audit must prove that squash/rebase/merge processing did not
change the reviewed behavior or omit generated evidence.

## Immediate no-go conditions

Any of the following produces `REQUEST_CHANGES` or `REJECT`:

- workflow code applies a patch, generates implementation source, or constructs
  a candidate that is not reviewable in the pull-request diff;
- a required Pull Request, Android, or Apple lane is missing, skipped,
  cancelled, superseded, or green on a different source SHA;
- a strategy plan declares operations that do not correspond exactly to
  provider operations performed;
- protected execution silently invokes an unprotected required provider;
- durable acceptance is reported before the provider-owned atomic boundary
  confirms it;
- admitted work is deleted or reset after scheduler, transport, cancellation,
  or process failure;
- retry, conflict, fallback, cancellation, partial effect, or terminal state is
  misclassified;
- a public API or ABI change is absent from reviewed baselines;
- a Room schema, migration, Apple durable format, or publication change is
  accidental or lacks recovery evidence;
- an internal engine type leaks through public metadata, ABI, Apple headers, or
  external consumers;
- a test is removed, weakened, renamed, or narrowed to hide a regression;
- a workflow is modified to skip a platform or reduce validation;
- a new dependency, SDK, hosted service, repository, plugin, database wrapper,
  networking wrapper, analytics integration, or vendor coupling appears without
  explicit approval;
- credentials, keys, headers, payloads, customer data, or unbounded metadata
  enter logs, events, traces, errors, fixtures, examples, or diagnostics;
- documentation or the Market-readiness dashboard claims more than current
  source and evidence prove.

## 1. Scope and branch integrity

| Check | Required evidence | Verdict |
|---|---|---|
| Approved primary issue | PR links one approved bounded issue and states parent-gate limits | Pending |
| Isolated branch/worktree | Branch name, base SHA, and zero unrelated user changes | Pending |
| One PR per builder | No second active PR for the same agent contract | Pending |
| Current base | Candidate is based on or deliberately synchronized with audited `main` | Pending |
| Final source in diff | Production source, tests, ABI/schema evidence, and docs are committed | Pending |
| No temporary helpers | No patch staging files, generated-source helpers, temporary workflows, caches, or secrets | Pending |
| Diff hygiene | `git diff --check`, newline, encoding, generated-file and binary review | Pending |
| Scope ownership | Changed paths match the agent contract; shared-file exceptions are recorded in #188 | Pending |

## 2. Strategy and execution correctness

| Check | Required evidence | Verdict |
|---|---|---|
| Immutable decision | Effective profile, configuration version, decision ID, plan ID, and accepted plan remain stable | Pending |
| Plan/provider correspondence | Every ordered plan operation maps one-to-one to a provider/runtime operation | Pending |
| Capability resolution | All required providers resolve before the first irreversible side effect | Pending |
| Forbidden providers | Network-only and rejected paths prove zero storage/queue/scheduler calls as applicable | Pending |
| No silent strategy switch | Fallback, refresh, defer, adaptive selection, and degraded results remain explicit | Pending |
| Direction/mode/trigger | PUSH/PULL/BIDIRECTIONAL, FULL/DELTA, and trigger support or rejection is explicit | Pending |
| Partial effects | Completed remote/local operations survive later failures without unsafe replay | Pending |
| Acknowledgement ordering | Remote acknowledgement never erases newer local work; apply/checkpoint order is explicit | Pending |
| Accepted-plan replay | Retry/restart/lease recovery executes the frozen continuation without current-policy evaluation | Pending |
| Protected execution | Every required provider has independent configured protection or the path fails closed | Pending |

## 3. Durable ordering and recovery

| Check | Required evidence | Verdict |
|---|---|---|
| Atomic admission | Local intent plus durable work is one provider-owned transaction where promised | Pending |
| Queue before scheduler | Durable queue state commits before scheduler invocation | Pending |
| Stable identity | First/already/conflict behavior is typed and not inferred from error text | Pending |
| Scheduler failure | Accepted work remains present and can be reconciled by the same stable identity | Pending |
| Cancellation | Cancellation propagates and does not erase already accepted work | Pending |
| Lease safety | Acquisition and lease assignment are atomic; stale lease transitions fail | Pending |
| Retry history | Attempts, elapsed budget, next time, deadlines, and errors survive deferral/restart | Pending |
| Process loss | Android/Apple termination and relaunch use persisted state, not object recreation | Pending |
| Duplicate delivery | Repeated callbacks, schedules, queue acquisition, and commands remain idempotent | Pending |
| Migration | Old records read safely; malformed/partial state fails closed; no state is invented | Pending |

## 4. Retry, circuit, conflict, and event integration

| Check | Required evidence | Verdict |
|---|---|---|
| Failure classification | Authentication, validation, integrity, conflict, cancellation, policy and unknown failures are not retried/fallbacked incorrectly | Pending |
| Timeout separation | Connection/request/idle/workflow/provider/policy boundaries stay independent | Pending |
| Circuit scope | Provider/operation/tenant/workflow scope is explicit and not inferred | Pending |
| Half-open ownership | One allowed probe, competitor rejection, generation fencing and stale completion safety | Pending |
| Conflict application | Detection, decision, apply, checkpoint, audit and event boundaries are atomic/idempotent | Pending |
| Non-convergence | Fingerprint, limits, quarantine and loop prevention are bounded | Pending |
| Event delivery | Ordering, duplicate, replay, acknowledgement, retention and overflow semantics are explicit | Pending |
| Observer/exporter isolation | Slow/failing telemetry cannot fail or indefinitely block synchronization | Pending |
| Cardinality/redaction | Metrics labels and operational evidence remain bounded and payload-free | Pending |

## 5. API, ABI, modules, and publication compatibility

| Check | Required evidence | Verdict |
|---|---|---|
| Public API intent | New/changed public symbols are justified, documented and minimal | Pending |
| JVM ABI | Generated baseline matches the final source and was reviewed | Pending |
| Kotlin/Native ABI | KLib baseline generated on an authoritative native host and reviewed | Pending |
| Apple surface | Objective-C/Swift headers expose only approved public artifacts | Pending |
| Module direction | Shared runtime remains platform-neutral; production modules do not depend on testing | Pending |
| Variant metadata | JVM, explicit KMP Android and all iOS variants resolve as intended | Pending |
| External consumers | Staged/published-style consumers compile/run without project substitution | Pending |
| Android packaging | AAR metadata, resources, manifest, consumer rules, R8 and AGP matrix are qualified | Pending |
| Publication metadata | POM/module/BOM coordinates and dependency constraints match the candidate | Pending |
| Deprecation/compatibility | Source/binary/behavior changes follow the pre-V1 or published compatibility policy | Pending |

## 6. Platform parity

| Check | Native Android | KMP Android | KMP iOS | Verdict |
|---|---|---|---|---|
| Foreground synchronization | Pending | Pending | Pending | Pending |
| Background scheduling | Pending | Pending | Pending | Pending |
| Connectivity/degraded states | Pending | Pending | Pending | Pending |
| Durable queue/restart | Pending | Pending | Pending | Pending |
| Retry/circuit recovery | Pending | Pending | Pending | Pending |
| Conflict persistence | Pending | Pending | Pending | Pending |
| Durable events/replay | Pending | Pending | Pending | Pending |
| Assets/files/security | Pending | Pending | Pending | Pending |
| Cancellation/expiration | Pending | Pending | Pending | Pending |
| Staged reference consumer | Pending | Pending | Pending | Pending |

Compilation or XCFramework assembly alone is not parity evidence. Unsupported
platform behavior must return an explicit typed unsupported/degraded result.

## 7. Security, privacy, and SDK boundary

| Check | Required evidence | Verdict |
|---|---|---|
| Provider-neutral SDK | Domain repositories, backend contracts, credentials, authorization and UI state remain application-owned | Pending |
| Secret minimization | No keys, credentials, auth headers, payload bytes, checkpoints or personal/customer data in operational output | Pending |
| Diagnostic bounding | Error strings, metadata, events, logs, traces and support snapshots are bounded and redacted | Pending |
| Permission boundary | Plugins/administration/enterprise actions are deny-by-default and audited where applicable | Pending |
| Tenant isolation | Tenant-scoped state/configuration/policy/telemetry cannot be substituted cross-tenant | Pending |
| File safety | Private unpredictable temporary files, atomic promotion, data protection, quota and cleanup where applicable | Pending |
| Dependency audit | No unapproved third-party or vendor coupling; approved dependencies stay in their owned modules | Pending |
| Abuse cases | Malformed state, identity collision, replay, tamper, injection and resource exhaustion fail safely | Pending |

## 8. Test and CI integrity

| Check | Required evidence | Verdict |
|---|---|---|
| Focused pre-commit tests | Success, failure, cancellation, duplicate, concurrency and forbidden-call cases pass locally | Pending |
| Full shared validation | Build, tests, ABI and external consumers pass on final source | Pending |
| Android validation | Assembly, unit, lint, schema, migration, KMP regression and managed device pass | Pending |
| Apple validation | Native tests, all iOS targets, KLib ABI, XCFramework, headers and Swift smoke pass | Pending |
| Same immutable SHA | All required lanes refer to the same final source candidate | Pending |
| No blind reruns | Every rerun is tied to a source correction or proven transient infrastructure failure | Pending |
| No skipped lane | Required jobs are not conditionally bypassed by branch name, path or workflow change | Pending |
| Post-merge checks | New `main` passes permanent validation and matches the reviewed candidate | Pending |

### CI failure classification

Use one of these categories:

| Category | Meaning | Required action |
|---|---|---|
| Source defect | Compilation, test, ABI, schema, lint or behavior failure caused by candidate | Correct source locally, re-run affected preflight, push one deliberate commit |
| Environment defect | Runner image, action download, service outage, unavailable device/tool | Record logs and retry only the failed job after the environment is healthy |
| Dispatch defect | Required workflow was never created for the final SHA | Keep draft/blocked; do not accept stale checks or manufacture validation |
| Superseded evidence | Run targets an earlier head or merge SHA | Ignore it and require all lanes on the current immutable source |
| Policy defect | Workflow skipped/weakened or candidate modifies validation to pass | Reject and restore permanent validation |

## 9. Documentation and readiness truth

| Check | Required evidence | Verdict |
|---|---|---|
| Current behavior | Docs describe only behavior present on the candidate | Pending |
| Explicit limitations | Remaining parent-gate blockers are listed precisely | Pending |
| Historical integrity | Older audits are preserved; newer reconciliations explicitly supersede scope | Pending |
| Dashboard accuracy | Gate status and percentage change only after Audit-01 acceptance | Pending |
| Samples | Public examples use actual API and placeholder, non-sensitive data | Pending |
| Operational docs | Migration, recovery, security and unsupported behavior match the candidate | Pending |

## Audit blocker format

```text
Label: audit:blocker
Severity: release-blocking | wave-blocking | local
Introduced by SHA:
Affected requirements:
Evidence:
Required correction:
Owning agent:
Dependent work to pause:
State: OPEN | RESOLVED | SUPERSEDED
```

## Audit verdict format

```text
AUDITED BASE SHA:
AUDITED HEAD SHA:
PRIMARY ISSUE / PR:
FILES REVIEWED:
BEHAVIORAL DRIFT:
PLAN/PROVIDER CORRESPONDENCE:
DURABLE ORDERING:
RETRY/CONFLICT/EVENT INTEGRATION:
API/ABI:
SCHEMA/FORMAT:
PLATFORM PARITY:
SECURITY/REDACTION:
DEPENDENCIES:
TEST/CI INTEGRITY:
DOCUMENTATION ACCURACY:
BLOCKERS:
VERDICT: ACCEPT | REQUEST_CHANGES | REJECT
NEXT AUDITED MAIN SHA:
NEXT OWNERS:
```

## Initial Audit-02 baseline verdict

Baseline `c7e16b71d711cb7c82406c84aca76428f44e0328` contains the accepted governance
operating model only; it does not change production behavior or release-gate
completion. The V1 verdict remains **NO-GO**, with **1 of 10 gates (10%)**
accepted.

The first active production application of this checklist is the audited durable
cache-refresh candidate that supersedes PR `#185`. It must remain draft until its
source corrections, committed tests, permanent shared/Android/Apple validation,
and final Audit-01/Audit-02 verdict all refer to one immutable SHA.
