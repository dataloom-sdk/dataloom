# DL-040 Storage and Transport Provider Timeout Checkpoint

## Decision

Storage and transport providers may be protected by explicit cooperative
provider timeouts without changing completed provider results or presenting an
ambiguous mutation as automatically retryable.

This checkpoint advances FR-RETRY-006 and the provider-integration portion of
FR-RETRY-010. It does not complete DL-040 or DataLoom V1.

## Implemented boundary

The runtime adds:

- `TimeoutEnforcingStorageProvider`;
- `StorageProviderTimeoutRuntime`;
- `TimeoutEnforcingTransportProvider`; and
- `TransportProviderTimeoutRuntime`.

The decorators cover provider lifecycle and all current storage/transport
synchronization operations.

## Ambiguity rule

A timeout means the caller did not obtain a confirmed result. It does not prove
that the underlying operation rolled back.

Storage health, outbound reads, and checkpoint reads are classified as
recoverable read-only timeouts. Storage apply, acknowledgement, checkpoint
write, initialization, and close use `Recoverability.UNKNOWN`.

Transport health is classified as recoverable. Push, pull, initialization, and
close use `Recoverability.UNKNOWN`; the remote participant may have processed a
request before cancellation or response loss was observed.

No timeout path performs an automatic second invocation.

## Stable errors

Storage:

- `STORAGE_PROVIDER_TIMEOUT`
- `STORAGE_WORKFLOW_DEADLINE_EXCEEDED`
- `STORAGE_TIMEOUT_CLOCK_REGRESSION`

Transport:

- `TRANSPORT_PROVIDER_TIMEOUT`
- `TRANSPORT_WORKFLOW_DEADLINE_EXCEEDED`
- `TRANSPORT_TIMEOUT_CLOCK_REGRESSION`

Messages contain bounded operation names only and exclude payloads, credentials,
headers, SQL, paths, provider instances, exception text, and metadata.

## Construction and cancellation

Runtime assembly performs no provider operation, clock read, timeout execution,
I/O, identifier generation, or coroutine launch.

Caller cancellation and unexpected programming exceptions propagate. The common
executor is cooperative; blocking implementations require a platform-specific
cancellation boundary.

## Required qualification evidence

The review branch must prove:

- exact descriptor and completed result preservation;
- read-only storage timeout classification;
- unknown storage-mutation timeout classification;
- unknown transport push and pull timeout classification;
- recoverable transport health timeout classification;
- zero timeout prevents delegate invocation;
- positive timeout performs cooperative delegate cleanup;
- caller cancellation propagates;
- runtime factories are side-effect free;
- external consumers compile for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI baselines contain the public surface;
- Apple XCFramework assembly and public-boundary validation pass; and
- permanent Pull Request, Android, and Apple checks pass on one final head.

## Remaining work

- DataLoomBuilder adoption;
- workflow deadline propagation;
- connection, request, and idle timeout adapters;
- storage and transport circuit adapters;
- enriched pipeline execution evidence;
- KMP iOS production integration;
- observability and administration; and
- multi-process, restart, failure-injection, and AC-FUNC-004 qualification.
