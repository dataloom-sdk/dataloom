# DL-040 DataLoomBuilder Circuit Queue Worker Checkpoint

## Decision

DataLoomBuilder may explicitly assemble and expose the circuit-aware queue
worker without replacing or down-mapping the existing direct worker result
model.

This checkpoint advances the runtime-assembly portion of FR-RETRY-007,
FR-RETRY-009, and FR-RETRY-010. It does not complete DL-040 or DataLoom V1.

## Evidence identity

- Review branch: `codex/dl-retry-029-builder-circuit-queue-worker`
- Baseline: merged PR #128 at `38dc73328647c788484aecc57595ca2bea521790`
- Focused evidence head: `2cb34309d40004b295cc23cb92b19ea39034fd8b`
- Permanent validation: required on the final review commit after this evidence record

## Focused qualification completed

The pull-request-only macOS evidence lane completed:

- the reviewed builder and facade integration;
- runtime JVM tests;
- `iosSimulatorArm64Test`;
- external-consumer compilation for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI generation;
- runtime and Apple ABI checks;
- public ABI-boundary validation;
- Apple release XCFramework assembly;
- facade, worker, circuit, and API-index documentation updates; and
- removal of the temporary workflow and patch helper from the final diff.

The initial ordinary PR lane ran against the intentionally ungenerated review
branch and failed on the expected missing builder methods and ABI baseline. The
focused lane applied those changes and passed source compilation, tests,
consumers, ABI, and framework qualification before committing the clean head.

## Public boundary

The additive facade surface is:

- `DataLoomCircuitQueueWorker`;
- `DataLoomCircuitQueueWorkerSpec`;
- `DataLoomBuilder.circuitQueueWorkerConfiguration(...)`; and
- `DataLoom.circuitQueueWorker`.

The existing direct queue worker remains source compatible. `DataLoom` supplies
a default-null getter for custom pre-V1 implementations.

## Exclusivity rule

A DataLoom instance exposes at most one queue-worker execution model.

- Direct configuration clears circuit-aware configuration.
- Circuit-aware configuration clears direct configuration.
- The most recent call wins deterministically.

This avoids two capabilities concurrently acquiring from the same queue through
one built runtime.

## Explicit dependencies

The application supplies:

- ordinary queue-worker dependencies;
- circuit thresholds and windows;
- a durable circuit state store;
- the exact recovery scope;
- the exact acquisition and transition scopes; and
- an optional custom queue failure classifier.

No in-memory state store, broad global scope, provider scope, operation scope,
tenant scope, or workflow scope is inferred.

## Build-time validation

The builder validates before state or provider access:

- the bound provider exists;
- it is typed as a queue provider;
- it implements `QueueProvider`;
- every provider-bearing scope identifies the exact bound queue provider; and
- every operation-bearing scope identifies the exact queue operation.

Failures are sanitized `DataLoomBuildException` results. They do not expose
provider instances, payloads, credentials, metadata, or state-store contents.

## Timeout composition

The existing optional queue-provider timeout is assembled before the shared
queue circuit adapter. Recovery, acquisition, and transitions therefore use the
same timeout-protected provider and the same circuit gate.

Scheduler timeout remains independent. Scheduler circuit policy is not inferred
from queue circuit configuration.

## Side-effect boundary

Configuration and build perform no:

- circuit load or compare-and-set;
- provider operation;
- queue operation;
- retry evaluation;
- timeout execution;
- clock read;
- identifier generation;
- scheduling;
- synchronization; or
- coroutine launch.

## Qualification evidence

The review branch proves:

- both facade capabilities are absent without configuration;
- direct configuration exposes only the direct worker;
- circuit configuration exposes only the circuit worker;
- the last worker configuration method wins;
- provider and operation scope mismatch fail before state/provider access;
- valid build performs no state/provider/clock work;
- the built circuit worker executes through the supplied state store and bound
  queue provider;
- zero queue-provider timeout prevents queue invocation and contributes to
  circuit state through queue timeout classification;
- custom failure classification is preserved;
- external consumers compile for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI baselines include the additive facade; and
- permanent JVM, Android, and Apple validation must pass on this final review head.

## Remaining work

- scheduler circuit policy and enriched scheduling evidence;
- transport and storage timeout/circuit assembly;
- protocol-specific connection/request/idle timeout adapters;
- KMP iOS durable retry/circuit state;
- manual retry/reclassification and circuit administration;
- complete observability; and
- multi-process, restart, contention, and `AC-FUNC-004` qualification.
