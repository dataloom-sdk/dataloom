from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    Path(path).write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one replacement, found {count}")
    write(path, text.replace(old, new, 1))


def insert_before_once(path: str, anchor: str, insertion: str) -> None:
    text = read(path)
    if insertion in text:
        return
    count = text.count(anchor)
    if count != 1:
        raise RuntimeError(f"{path}: expected one insertion anchor, found {count}")
    write(path, text.replace(anchor, insertion + anchor, 1))


# Repository capability snapshot.
replace_once(
    "README.md",
    "| Retry and circuit breaking | Fail-closed classification, deterministic backoff/full/equal jitter, seeded randomness, attempt plus durable elapsed/cumulative budgets, queue/scheduler orchestration, and restart-safe history implemented; broader engine partial | Hints, timeout separation, durable circuit state, operations, and full qualification |",
    "| Retry and circuit breaking | Fail-closed classification, deterministic backoff/full/equal jitter, seeded randomness, attempt plus durable elapsed/cumulative budgets, bounded provider/server hints, queue/scheduler orchestration, and restart-safe history implemented; broader engine partial | Timeout separation, durable circuit state, operations, and full qualification |",
)

# API index.
replace_once(
    "docs/api/README.md",
    "| [Retry policy](./retry-policy.md) | Partial V1 subsystem | Fail-closed classification, deterministic backoff/jitter, seeded randomness, attempt limits, and durable elapsed/cumulative budgets. |",
    "| [Retry policy](./retry-policy.md) | Partial V1 subsystem | Fail-closed classification, deterministic backoff/jitter, seeded randomness, attempt/time/delay limits, and bounded provider/server hints. |",
)
replace_once(
    "docs/api/README.md",
    "| [Retry orchestration](./retry-orchestration.md) | Partial V1 subsystem | Protected-failure handling, final-delay aggregation, central budgets, scheduling, and queue integration boundaries. |",
    "| [Retry orchestration](./retry-orchestration.md) | Partial V1 subsystem | Protected-failure handling, bounded hint minimums, final-delay aggregation, central budgets, scheduling, and queue integration boundaries. |",
)
replace_once(
    "docs/api/README.md",
    "V1 retry work still requires bounded server hints, timeout separation, durable\ncircuit-breaker and half-open recovery, manual retry/reclassification, complete",
    "V1 retry work still requires timeout separation, durable circuit-breaker and\nhalf-open recovery, manual retry/reclassification, complete",
)

# Error-model opt-in capability.
insert_before_once(
    "docs/api/error-model.md",
    "## Sensitive-data restrictions\n",
    """## Optional retry timing guidance

A recoverable error may additionally implement `RetryDelayHintCarrier`. The
carrier exposes a typed `RetryDelayHint` containing only a non-negative delay in
milliseconds and a stable `SERVER` or `PROVIDER` source.

Protocol adapters own parsing raw values such as HTTP `Retry-After`. They must
normalize absolute dates or protocol units before creating the hint. The shared
runtime never parses raw headers, exception messages, or provider-specific text.
A hint remains untrusted until bounded by `RetryHintConfiguration`.

""",
)

# Retry policy reference.
replace_once(
    "docs/api/retry-policy.md",
    "> limits, and durable elapsed-time/cumulative-delay budgets are implemented.\n> Server hints, timeout separation, durable circuit breaking, manual retry, and",
    "> limits, durable elapsed-time/cumulative-delay budgets, and bounded\n> provider/server hints are implemented. Timeout separation, durable circuit\n> breaking, manual retry, and",
)
replace_once(
    "docs/api/retry-policy.md",
    "                    backoff → optional jitter → Retry or Stop",
    "          backoff → optional jitter → bounded hint minimum → Retry or Stop",
)
replace_once(
    "docs/api/retry-policy.md",
    "| `metadata` | Optional bounded, non-sensitive context |",
    "| `metadata` | Optional bounded, non-sensitive context |\n| `retryDelayHint` | Optional normalized hint after runtime clamping |",
)
replace_once(
    "docs/api/retry-policy.md",
    "The current queue-backed and scheduler-backed runtime paths pass\n`previousDelay = null` and `provider = null`. These fields remain available for\nfuture hint, budget, and application-policy integration.",
    "The current queue-backed and scheduler-backed runtime paths pass\n`previousDelay = null` and `provider = null`. When central hint handling is\nconfigured, `retryDelayHint` contains only the typed value clamped to the\nconfigured maximum; otherwise it is `null`.",
)
replace_once(
    "docs/api/retry-policy.md",
    "| `ATTEMPT_LIMIT_REACHED` | A configured retry budget is exhausted |\n| `POLICY_REJECTED` | Policy or central protection rejected automatic retry |",
    "| `ATTEMPT_LIMIT_REACHED` | The configured retry-attempt limit is exhausted |\n| `ELAPSED_TIME_LIMIT_REACHED` | The next retry would exceed the elapsed window |\n| `CUMULATIVE_DELAY_LIMIT_REACHED` | Accepted delays would exceed the cumulative limit |\n| `CLOCK_REGRESSION_DETECTED` | Persisted time evidence moved backwards |\n| `POLICY_REJECTED` | Policy or central protection rejected automatic retry |",
)
insert_before_once(
    "docs/api/retry-policy.md",
    "## Evaluation order\n",
    """## Bounded provider/server retry hints

A provider that has protocol-specific retry timing may return a canonical error
implementing `RetryDelayHintCarrier`. The attached `RetryDelayHint` has two stable
sources: `SERVER` and `PROVIDER`. It contains a normalized non-negative delay in
milliseconds, never a raw header or absolute date.

Hint handling is opt-in through:

```kotlin
val hintConfiguration = RetryHintConfiguration(
    maximumHintDelay = SchedulingDelay(60_000L),
)
```

For every eligible error, the central runtime:

1. extracts the typed hint only when hint handling is configured;
2. clamps it to `maximumHintDelay`;
3. exposes only the bounded value to `RetryPolicy`;
4. preserves a policy `Stop` decision;
5. enforces `max(policyDelay, boundedHint)` for a retry decision; and
6. evaluates elapsed/cumulative budgets against that final delay.

A policy may choose a longer delay or stop. It cannot make an enabled hinted
retry earlier than the bounded hint. A hint of zero has no effect. A hint larger
than the configured maximum is clamped rather than trusted or rejected.

This behavior is deliberately central so custom policies cannot accidentally
schedule earlier than a bounded server minimum. Omitting `RetryHintConfiguration`
preserves pre-hint behavior and supplies `retryDelayHint = null` to runtime-created
policy requests.

""",
)
replace_once(
    "docs/api/retry-policy.md",
    "retry is stopped—not shortened—when its final jittered delay would exceed either",
    "retry is stopped—not shortened—when its final policy/hint-adjusted delay would exceed either",
)
replace_once(
    "docs/api/retry-policy.md",
    "5. deterministic jitter is applied when configured;\n6. the maximum requested delay is selected across errors;\n7. elapsed and cumulative budgets evaluate the final delay;\n8. availability time is calculated with overflow-safe timestamp addition; and\n9. successful queue rescheduling persists attempt, error, and budget state.",
    "5. deterministic jitter is applied when configured;\n6. a normalized provider/server hint is clamped and enforced as a minimum;\n7. the maximum final delay is selected across errors;\n8. elapsed and cumulative budgets evaluate that final delay;\n9. availability time is calculated with overflow-safe timestamp addition; and\n10. successful queue rescheduling persists attempt, error, and budget state.",
)
replace_once(
    "docs/api/retry-policy.md",
    "The orchestrator treats the policy's final delay—including jitter—as an opaque\nminimum delay. It applies no second jitter layer. When budgets are configured,\nit returns next budget state only after scheduler acceptance.",
    "The orchestrator applies no second jitter layer. When hint handling is\nconfigured, it clamps the typed hint and enforces it as a minimum before final\ndelay aggregation. When budgets are configured, it returns next budget state\nonly after scheduler acceptance.",
)
replace_once(
    "docs/api/retry-policy.md",
    "- A protected batch does not invoke custom policy or random source.",
    "- A protected batch does not invoke custom policy, random source, or hint handling.\n- Raw `Retry-After` or exception-message parsing is never performed by the core.",
)
replace_once(
    "docs/api/retry-policy.md",
    "Retry decisions and random requests must not contain payloads, credentials,\ntokens, keys, authorization headers, checkpoint values, personal data, complete\nexception messages, or unbounded-cardinality labels.",
    "Retry decisions, random requests, and hint contracts must not contain payloads,\ncredentials, tokens, keys, authorization headers, checkpoint values, personal\ndata, raw headers, complete exception messages, or unbounded-cardinality labels.",
)
replace_once(
    "docs/api/retry-policy.md",
    "- maximum elapsed-time and aggregate-delay budgets;\n- bounded provider/server retry hints;\n- separated timeout semantics;",
    "- separated timeout semantics;",
)

# Scheduler orchestration reference.
replace_once(
    "docs/api/retry-orchestration.md",
    "> and central elapsed/cumulative budgets are implemented. Durable circuit state,\n> hints, timeout separation, manual retry, and full observability remain.",
    "> central elapsed/cumulative budgets, and bounded provider/server hints are\n> implemented. Durable circuit state, timeout separation, manual retry, and full\n> observability remain.",
)
replace_once(
    "docs/api/retry-orchestration.md",
    "    Protected -->|No| Policy[Evaluate RetryPolicy per error]\n    Policy --> Decision{Any retry decision?}",
    "    Protected -->|No| Hint[Clamp optional typed hint]\n    Hint --> Policy[Evaluate RetryPolicy per error]\n    Policy --> Minimum[Enforce bounded hint minimum]\n    Minimum --> Decision{Any retry decision?}",
)
replace_once(
    "docs/api/retry-orchestration.md",
    "4. When protected, return `STOPPED` without invoking custom policy, random\n   source, or scheduler.\n5. Otherwise evaluate the configured policy once per error in original order.\n6. Return `STOPPED` when no decision requests retry.\n7. Select the maximum final `SchedulingDelay` across retry decisions.\n8. Return `SCHEDULER_NOT_CONFIGURED` when the scheduler is absent.\n9. Build one `ScheduleRequest` and call `schedule` exactly once.\n10. Return `SCHEDULED` with the exact receipt or `SCHEDULER_FAILED` with the\n    exact canonical error.\n11. Emit `RetryScheduled` only after scheduler acceptance when an event emitter\n    is configured.",
    "4. When protected, return `STOPPED` without invoking custom policy, random\n   source, hint handling, or scheduler.\n5. Otherwise clamp each typed hint when hint handling is configured.\n6. Evaluate the configured policy once per error with the bounded hint.\n7. Preserve policy stops and enforce bounded hints as minimum retry delays.\n8. Return `STOPPED` when no decision requests retry.\n9. Select the maximum final `SchedulingDelay` across retry decisions.\n10. Evaluate elapsed/cumulative budgets against that final delay.\n11. Return `SCHEDULER_NOT_CONFIGURED` when the scheduler is absent.\n12. Build one `ScheduleRequest` and call `schedule` exactly once.\n13. Return `SCHEDULED` with the exact receipt or `SCHEDULER_FAILED` with the\n    exact canonical error.\n14. Emit `RetryScheduled` only after scheduler acceptance when configured.",
)
replace_once(
    "docs/api/retry-orchestration.md",
    "- `previousDelay = null`; and\n- `provider = null`.",
    "- `previousDelay = null`;\n- `provider = null`; and\n- `retryDelayHint = bounded typed hint` when configured, otherwise `null`.",
)
insert_before_once(
    "docs/api/retry-orchestration.md",
    "## Budget state\n",
    """## Bounded retry hints

`RetryHintConfiguration.maximumHintDelay` is the central trust boundary. Only
errors implementing `RetryDelayHintCarrier` participate. The hint is clamped
before policy invocation and then enforced as a minimum on a policy retry.
Policy stops remain stops, and a longer policy delay remains unchanged.

The orchestrator never parses protocol headers or exception messages. Providers
normalize source-specific values into milliseconds. The final hint-adjusted delay
is the value sent to `SchedulerProvider`, emitted in `RetryScheduled`, and checked
against retry budgets.

""",
)
replace_once(
    "docs/api/retry-orchestration.md",
    "used. “Final” means the delay after any policy-owned backoff, clamp, budget, and\njitter processing.",
    "used. “Final” means the delay after policy-owned backoff/jitter and central\nbounded-hint minimum enforcement.",
)
replace_once(
    "docs/api/retry-orchestration.md",
    "encryption keys, personal data, deterministic seeds, random-source inputs,\nstack traces, or provider internal state.",
    "encryption keys, personal data, deterministic seeds, random-source inputs,\nraw retry headers, stack traces, or provider internal state.",
)
replace_once(
    "docs/api/retry-orchestration.md",
    "decisions for the same policy, seed, request identity, and attempt. Full",
    "decisions for the same policy, seed, request identity, attempt, and bounded\nhint. Full",
)
replace_once(
    "docs/api/retry-orchestration.md",
    "Bounded retry hints, timeout separation, durable circuit state, half-open\nprobes, manual\nretry/reclassification, complete observability, restart/concurrency",
    "Timeout separation, durable circuit state, half-open probes, manual\nretry/reclassification, complete observability, restart/concurrency",
)

# Architecture ownership.
replace_once(
    "docs/architecture/retry-boundaries.md",
    "backoff, jitter, queue persistence, scheduling, and platform integration.",
    "backoff, jitter, provider/server hints, queue persistence, scheduling, and platform integration.",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "> limits, and central durable elapsed/cumulative budgets. Circuit breaking, hints,\n> timeout separation, observability, and administration remain incomplete, so this is not yet the",
    "> limits, central durable elapsed/cumulative budgets, and bounded typed hints.\n> Circuit breaking, timeout separation, observability, and administration remain\n> incomplete, so this is not yet the",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "    jitter[Optional deterministic jitter]\n    decision{Retry decision}",
    "    jitter[Optional deterministic jitter]\n    hint[Bounded typed hint minimum]\n    decision{Retry decision}",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "    base --> jitter\n    jitter --> decision",
    "    base --> jitter\n    jitter --> hint\n    hint --> decision",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "`DataLoomError` values. They must provide truthful `category` and\n`recoverability` fields and must not rely on exception-name or message parsing\ninside the retry engine.",
    "`DataLoomError` values. They must provide truthful `category` and\n`recoverability` fields. A provider with retry timing may additionally implement\n`RetryDelayHintCarrier` after normalizing protocol data. The retry engine never\nparses exception names, messages, or raw headers.",
)
insert_before_once(
    "docs/architecture/retry-boundaries.md",
    "## Runtime responsibility\n",
    """## Hint normalization responsibility

Protocol and provider adapters own translation from source-specific timing into
`RetryDelayHint(delayMilliseconds, source)`. The model permits zero through
`Long.MAX_VALUE`, but the runtime trusts only the value after clamping it to
`RetryHintConfiguration.maximumHintDelay`.

The bounded hint is visible to policy and is centrally enforced as a minimum.
This makes `max(policyDelay, boundedHint)` the canonical retry delay before
multi-error aggregation and budget evaluation. Stop decisions are never changed.

""",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "- construction of policy requests;\n- decision aggregation;",
    "- bounded hint extraction and policy-request exposure;\n- construction of policy requests;\n- hint-minimum enforcement and decision aggregation;",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "- routing accepted retry decisions to queue or scheduler transitions; and\n- future circuit state, hints, manual operations, and observability.",
    "- routing accepted retry decisions to queue or scheduler transitions; and\n- future circuit state, manual operations, and observability.",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "The runtime treats a policy's final delay, including jitter, as one canonical\nminimum delay. It must not apply a second implicit jitter layer.",
    "The runtime applies no second implicit jitter layer. When hints are enabled,\nthe canonical delay is `max(policyDelayIncludingJitter, boundedHint)`.",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "retry availability timestamps;\n- persisted elapsed/cumulative budget state;",
    "retry availability timestamps containing the final hint-adjusted delay;\n- persisted elapsed/cumulative budget state;",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "classification, attempt calculation, delay-policy selection, or jitter.",
    "classification, attempt calculation, delay-policy selection, jitter, or hint parsing.",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "base-delay, jitter, budgets, circuit, cancellation, and recovery behavior.",
    "base-delay, jitter, bounded-hint, budgets, circuit, cancellation, and recovery behavior.",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "Retry metadata, random requests, diagnostics, events, logs, and traces must not",
    "Retry metadata, random requests, typed hints, diagnostics, events, logs, and traces must not",
)
replace_once(
    "docs/architecture/retry-boundaries.md",
    "The shared retry engine still must add server hints, timeout separation,\ndurable closed/open/half-open circuit state,",
    "The shared retry engine still must add timeout separation, durable\nclosed/open/half-open circuit state,",
)
