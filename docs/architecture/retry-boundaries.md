# DataLoom retry boundaries

This document defines ownership for retry classification, policy evaluation,
backoff, jitter, provider/server hints, queue persistence, scheduling, and platform integration.

> [!IMPORTANT]
> DataLoom enforces a shared fail-closed boundary before invoking custom retry
> policy and ships deterministic immediate, fixed, linear, and exponential
> backoff, full/equal jitter through injected deterministic randomness, attempt
> limits, central durable elapsed/cumulative budgets, and bounded typed hints.
> Circuit breaking, timeout separation, observability, and administration remain
> incomplete, so this is not yet the
> complete V1 retry engine.

```mermaid
flowchart LR
    failure[Canonical failure set]
    guard{Central protection}
    stop[Stop complete batch]
    policy[RetryPolicy]
    base[Bounded base delay]
    jitter[Optional deterministic jitter]
    hint[Bounded typed hint minimum]
    decision{Retry decision}
    budget{Elapsed and cumulative budgets}
    direct[SchedulerProvider]
    queued[QueueProvider reschedule]
    durable[(Persisted attempt)]

    failure --> guard
    guard -->|Protected| stop
    guard -->|Eligible| policy
    policy --> base
    base --> jitter
    jitter --> hint
    hint --> decision
    decision -->|Stop| stop
    decision -->|Retry| budget
    budget -->|Reject| stop
    budget -->|Direct| direct
    budget -->|Queued| queued
    queued --> durable
```

## Canonical producer responsibility

Providers and pipelines own mapping platform-specific failures to sanitized
`DataLoomError` values. They must provide truthful `category` and
`recoverability` fields. A provider with retry timing may additionally implement
`RetryDelayHintCarrier` after normalizing protocol data. The retry engine never
parses exception names, messages, or raw headers.

## Central runtime protection

The runtime scans the complete ordered error set before custom policy
invocation. Automatic retry is blocked by:

- `Recoverability.NON_RECOVERABLE`;
- `Recoverability.UNKNOWN`;
- authentication;
- authorization;
- serialization;
- validation;
- configuration;
- policy;
- conflict; and
- security failures.

When one protected error exists, the whole batch stops. Custom policy, random
source, queue rescheduling, and scheduler are not invoked. The first protected
error remains the primary blocking evidence.

This rule prevents a retryable sibling failure in a partial result from hiding
a protected failure.

## Policy responsibility

`RetryPolicy` is responsible for eligible errors only:

- accept a `RetryEvaluationRequest`;
- return `Retry` or `Stop` deterministically;
- use already available information; and
- remain platform-independent and free of I/O.

A policy must not execute operations, block, sleep, access network or storage,
call providers, refresh credentials, wait for connectivity, schedule work,
mutate queue state, log sensitive context, or translate cancellation.

Application policies cannot bypass central protection through an ordinary
`RetryDecision.Retry`.

## Standard policy ownership

`StandardRetryPolicy` owns configuration-only retry decisions for eligible
errors:

- immediate delay;
- fixed delay;
- linear backoff;
- exponential backoff;
- maximum retry attempts;
- optional full jitter; and
- optional equal jitter.

Linear and exponential calculations clamp before overflow. Attempt one uses the
configured initial delay, and attempt `N` is accepted only when it is within the
configured retry-attempt budget.

The compatibility constructor applies no jitter and preserves the exact base
delay. The jitter-enabled constructor requires an explicit `RetryRandomSource`.
Jitter is applied after base-delay clamping and never raises the configured base
delay or maximum.

The standard policy does not own queue transitions, clocks, central budget state,
scheduler invocation, circuit persistence, provider retry hints, or manual
administrative actions.

## Jitter and randomness responsibility

`RetryJitterStrategy` defines three closed modes:

- `None`: preserve the base delay and consume no sample;
- `Full`: choose from `0..baseDelay`; and
- `Equal`: choose from `ceil(baseDelay / 2)..baseDelay`.

A zero base delay and a zero-width equal-jitter window bypass the random source.
Protected failures and exhausted attempts also bypass it.

`RetryRandomSource` is an injected common-code boundary. It receives a
`RetryRandomRequest` containing only:

- policy ID;
- workflow ID;
- session ID;
- operation;
- canonical error code;
- attempt; and
- inclusive upper bound.

It does not receive payloads, arbitrary metadata, provider instances,
credentials, tokens, or exception messages.

A source must be deterministic for equal requests, bounded, non-blocking,
side-effect free, and thread-safe. DataLoom checks its output and fails policy
evaluation when the source violates the requested range rather than silently
clamping it.

`SeededRetryRandomSource` is the built-in stateless implementation. It derives a
bounded sample from the non-secret seed and stable request identity. The same
seed and request produce the same value across JVM, Android, and Kotlin/Native,
independent of concurrent evaluation order. This makes the result reproducible
after restart when the durable identity and seed are restored.

The seeded source is not a cryptographic random-number generator and must not be
used for key, nonce, credential, or security-token generation.

## Hint normalization responsibility

Protocol and provider adapters own translation from source-specific timing into
`RetryDelayHint(delayMilliseconds, source)`. The model permits zero through
`Long.MAX_VALUE`, but the runtime trusts only the value after clamping it to
`RetryHintConfiguration.maximumHintDelay`.

The bounded hint is visible to policy and is centrally enforced as a minimum.
This makes `max(policyDelay, boundedHint)` the canonical retry delay before
multi-error aggregation and budget evaluation. Stop decisions are never changed.

## Runtime responsibility

The runtime owns:

- protected-failure enforcement;
- bounded hint extraction and policy-request exposure;
- construction of policy requests;
- hint-minimum enforcement and decision aggregation;
- attempt advancement at the queue boundary;
- overflow-safe availability calculation;
- central elapsed/cumulative budget enforcement and state propagation;
- routing accepted retry decisions to queue or scheduler transitions; and
- future circuit state, manual operations, and observability.

The runtime applies no second implicit jitter layer. When hints are enabled,
the canonical delay is `max(policyDelayIncludingJitter, boundedHint)`.

## Queue responsibility

The durable queue owns:

- persisted retry attempts;
- lease-guarded transitions;
- retry availability timestamps containing the final hint-adjusted delay;
- persisted elapsed/cumulative budget state;
- deferral without attempt or budget consumption; and
- expired-lease recovery without resetting retry or budget history.

A `RetryDecision` does not mutate the queue directly. The queue processor
translates a runtime outcome into exactly one provider transition.

Queue schema version 2 persists first-evaluation, last-evaluation, and
cumulative-delay evidence. It does not persist random-source configuration or a
separate jitter sample. Restart determinism therefore also depends on restoring
the same configured source and durable policy/request identity.

## Scheduler responsibility

`SchedulerProvider` accepts a canonical schedule request after retry has been
approved. It does not classify recoverability, calculate backoff, apply jitter,
or evaluate policy.

A requested delay is a minimum intent, not an exact platform execution time.

## Connectivity boundary

Connectivity preflight is an execution constraint. An unmet requirement is a
non-retry deferral:

- retry policy is bypassed;
- random source is bypassed;
- no error is manufactured;
- no attempt is consumed; and
- existing retry and budget history is preserved.

## Cancellation

`SynchronizationResult.Cancelled` is terminal and not retryable. A thrown
`CancellationException` propagates and must not be converted into a retry stop
reason, provider error, or orchestration status.

## Android boundary

The shared engine does not expose WorkManager types. The Android adapter maps
canonical `ScheduleRequest` values to WorkManager. WorkManager does not own
classification, attempt calculation, delay-policy selection, jitter, or hint parsing.

## Kotlin Multiplatform boundary

Retry contracts and runtime rules live in common code. Native Android, KMP
Android, and KMP iOS must expose equivalent observable classification, attempt,
base-delay, jitter, bounded-hint, budgets, circuit, cancellation, and recovery behavior. Platform
limits must be explicit degraded or unsupported results rather than silent
omission.

## Security and privacy

Retry metadata, random requests, typed hints, diagnostics, events, logs, and traces must not
contain payloads, credentials, tokens, keys, authorization headers, checkpoint
values, personal data, full exception messages, or unbounded-cardinality labels.

The deterministic seed is configuration, not secret material. Random-source
implementations must not log stable request identifiers.

## Remaining V1 ownership

The shared retry engine still must add timeout separation, durable
closed/open/half-open circuit state,
controlled probes, manual retry/reclassification, complete observability, and
restart/concurrency/platform qualification.
