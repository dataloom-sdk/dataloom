# DL-040 DataLoomBuilder Circuit Queue Worker Checkpoint

## Decision

DataLoomBuilder may explicitly assemble and expose the circuit-aware queue
worker without replacing or down-mapping the existing direct worker result
model.

This checkpoint advances the runtime-assembly portion of FR-RETRY-007,
FR-RETRY-009, and FR-RETRY-010. It does not complete DL-040 or DataLoom V1.

## Public boundary

The additive facade surface is:

- `DataLoomCircuitQueueWorker`;
- `DataLoomCircuitQueueWorkerSpec`;
- `DataLoomBuilder.circuitQueueWorkerConfiguration(...)`; and
- `DataLoom.circuitQueueWorker`.

The existing direct queue worker remains source compatible.

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

## Required evidence

The review branch must prove:

- both facade capabilities are absent without configuration;
- direct configuration exposes only the direct worker;
- circuit configuration exposes only the circuit worker;
- the last worker configuration method wins;
- queue submission remains independently configurable;
- missing, mistyped, and contract-invalid queue bindings fail during build;
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
- final JVM, Android, and Apple validation pass on one clean head.

## Remaining work

- scheduler circuit policy and enriched scheduling evidence;
- transport and storage timeout/circuit assembly;
- protocol-specific connection/request/idle timeout adapters;
- KMP iOS durable retry/circuit state;
- manual retry/reclassification and circuit administration;
- complete observability; and
- multi-process, restart, contention, and `AC-FUNC-004` qualification.
