# DL-040 retry-administration facade checkpoint

## Scope

This checkpoint records stable `DataLoom` facade and builder assembly for the
already-qualified common retry-administration coordinator and production
Android/Apple stores and queue executors. It advances FR-RETRY-011 and the
administrative reclassification portion of FR-RETRY-012 without declaring
DL-040 or V1 complete.

## Implemented boundary

- `DataLoomRetryAdministrationSpec` explicitly retains the host authorizer,
  durable command-state store, idempotent platform queue executor, and positive
  compare-and-set attempt bound;
- `DataLoomBuilder.retryAdministrationConfiguration` opts into assembly and
  reuses the injected runtime clock;
- `DataLoom.retryAdministration` is a stable optional capability with a
  source-compatible default getter for custom pre-V1 facade implementations;
- `DataLoomRetryAdministration.execute` returns the coordinator's exact typed
  result without exposing collaborators or remapping evidence; and
- external JVM and Apple consumer code compiles the public specification,
  builder method, optional capability, request, and result boundary.

## Safety invariants

1. Builder construction invokes no authorizer, state-store, executor, provider,
   clock, identifier generator, queue mutation, or coroutine.
2. Omitted configuration leaves the capability `null`.
3. Automatic retry classification and the historical synchronization path are
   unchanged.
4. The coordinator remains authoritative for authorization, immutable input,
   fail-closed reclassification, durable admission, replay, contention, and
   terminal recording.
5. The facade propagates cancellation and unexpected exceptions unchanged.
6. Specification diagnostics never render collaborator implementation state.
7. The slice adds no dependency, permission, network access, background work,
   plaintext fallback, or platform-only common API.

## Focused evidence

Common tests cover absent and configured capability assembly, zero collaborator
invocations during build, exact request forwarding, exact successful result,
terminal replay without repeated authorization or queue mutation, positive
contention-bound validation, and redacted diagnostics.

The external consumer probe covers common public API use from outside the
runtime implementation module. Exact JVM and Kotlin/Native ABI declarations,
runtime common/JVM/iOS tests, public-boundary checks, and release XCFramework
assembly are mandatory merge evidence for the final pull-request head.

## Remaining DL-040 work

- authorized and durably audited circuit administration;
- complete retry/circuit administration events, bounded metrics, structured
  logs, traces, health, exporters, and an operational read model;
- executable process-loss/relaunch and higher-contention fault injection; and
- full Book 2 `AC-FUNC-004` qualification across native Android, KMP Android,
  and KMP iOS consumer paths.
