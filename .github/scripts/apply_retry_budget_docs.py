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


def insert_before_once(path: str, marker: str, content: str) -> None:
    text = read(path)
    if content.strip() in text:
        return
    count = text.count(marker)
    if count != 1:
        raise RuntimeError(f"{path}: expected one marker, found {count}")
    write(path, text.replace(marker, content + marker, 1))


replace_once(
    "README.md",
    "Fail-closed classification, deterministic standard backoff and full/equal jitter, seeded random source, attempt budget, queue/scheduler orchestration, and restart-safe attempt history implemented; broader engine partial",
    "Fail-closed classification, deterministic backoff/full/equal jitter, seeded randomness, attempt plus durable elapsed/cumulative budgets, queue/scheduler orchestration, and restart-safe history implemented; broader engine partial",
)
replace_once(
    "README.md",
    "Elapsed/delay budgets, hints, timeout separation, durable circuit state, operations, and full qualification",
    "Hints, timeout separation, durable circuit state, operations, and full qualification",
)

api_index = "docs/api/README.md"
replace_once(
    api_index,
    "complete durable retry/circuit policy state, migration evidence,\ncross-platform persistence, and end-to-end qualification.",
    "durable retry budget state and migration evidence now exist. Circuit policy\nstate, cross-platform persistence, and end-to-end qualification remain.",
)
replace_once(
    api_index,
    "Fail-closed classification, custom policy contracts, deterministic standard backoff, full/equal jitter, seeded randomness, and an attempt budget.",
    "Fail-closed classification, deterministic backoff/jitter, seeded randomness, attempt limits, and durable elapsed/cumulative budgets.",
)
replace_once(
    api_index,
    "Protected-failure handling, policy evaluation, final-delay aggregation, scheduling, and queue integration boundaries.",
    "Protected-failure handling, final-delay aggregation, central budgets, scheduling, and queue integration boundaries.",
)
replace_once(
    api_index,
    "V1 retry work still requires elapsed and aggregate delay budgets, bounded server\nhints, timeout separation, durable circuit-breaker and half-open recovery,\nrestart-safe policy state, manual retry/reclassification, complete\nobservability, and platform qualification.",
    "V1 retry work still requires bounded server hints, timeout separation, durable\ncircuit-breaker and half-open recovery, manual retry/reclassification, complete\nobservability, and platform qualification.",
)

retry_doc = "docs/api/retry-policy.md"
replace_once(
    retry_doc,
    "> protection, deterministic immediate/fixed/linear/exponential backoff,\n> configurable full/equal jitter, an injected deterministic random source, and\n> an attempt budget are implemented. Elapsed-time and aggregate-delay budgets,\n> server hints, timeout separation, durable circuit breaking, manual retry, and\n> complete observability remain V1 blockers.",
    "> protection, deterministic immediate/fixed/linear/exponential backoff,\n> configurable full/equal jitter, injected deterministic randomness, attempt\n> limits, and durable elapsed-time/cumulative-delay budgets are implemented.\n> Server hints, timeout separation, durable circuit breaking, manual retry, and\n> complete observability remain V1 blockers.",
)
insert_before_once(
    retry_doc,
    "## Runtime integration\n",
    """## Durable elapsed-time and cumulative-delay budgets

`RetryBudgetConfiguration` independently limits the wall-clock retry window and
the sum of delays accepted for retry. Exact boundaries are allowed. A proposed
retry is stopped—not shortened—when its final jittered delay would exceed either
limit.

`RetryBudgetState` records the first genuine retry evaluation, the most recent
accepted evaluation, and cumulative accepted delay. Clock regression against
persisted evidence stops fail-closed with a stable reason.

Queue rescheduling persists attempt, availability, error, and budget state in one
lease-guarded transition. Connectivity deferral and expired-lease recovery
preserve the state unchanged. Schema migration 1→2 retains existing retry attempt
and availability values and initializes historical budget columns to null.

Scheduler-backed orchestration returns the next state only after scheduling is
accepted. Missing or failed scheduling never consumes budget. Direct callers own
persistence of the returned state before supplying it to the next request.

""",
)
replace_once(
    retry_doc,
    """5. deterministic jitter is applied when configured;
6. the maximum requested delay is selected across errors;
7. availability time is calculated with overflow-safe timestamp addition; and
8. successful queue rescheduling persists the exact attempt and error.

Connectivity deferral bypasses this flow and preserves retry history.""",
    """5. deterministic jitter is applied when configured;
6. the maximum requested delay is selected across errors;
7. elapsed and cumulative budgets evaluate the final delay;
8. availability time is calculated with overflow-safe timestamp addition; and
9. successful queue rescheduling persists attempt, error, and budget state.

Connectivity deferral bypasses this flow and preserves retry and budget history.""",
)
replace_once(
    retry_doc,
    """The orchestrator treats the policy's final delay—including jitter—as an opaque
minimum delay. It does not apply a second jitter layer.""",
    """The orchestrator treats the policy's final delay—including jitter—as an opaque
minimum delay. It applies no second jitter layer. When budgets are configured,
it returns next budget state only after scheduler acceptance.""",
)
replace_once(
    retry_doc,
    "- maximum elapsed-time and aggregate-delay budgets;\n",
    "",
)
replace_once(
    retry_doc,
    "- restart recovery for elapsed windows and circuit state;",
    "- restart recovery for durable circuit state;",
)

orchestration_doc = "docs/api/retry-orchestration.md"
replace_once(
    orchestration_doc,
    "> protected-failure handling, deterministic standard backoff, full/equal\n> jitter, and an attempt budget are implemented. Durable circuit state, elapsed\n> and aggregate budgets, hints, manual retry, and full observability remain.",
    "> protected-failure handling, deterministic backoff/jitter, attempt limits,\n> and central elapsed/cumulative budgets are implemented. Durable circuit state,\n> hints, timeout separation, manual retry, and full observability remain.",
)
replace_once(
    orchestration_doc,
    """    Decision -->|Yes| Delay[Select maximum final delay]
    Delay --> Scheduler{Scheduler configured?}""",
    """    Decision -->|Yes| Delay[Select maximum final delay]
    Delay --> Budget{Within retry budgets?}
    Budget -->|No| Stopped
    Budget -->|Yes| Scheduler{Scheduler configured?}""",
)
replace_once(
    orchestration_doc,
    """The orchestrator does not execute synchronization, process queue entries, check
connectivity, initialize providers, calculate standard backoff, apply jitter,
own a coroutine scope, or select a dispatcher.""",
    """The orchestrator does not execute synchronization, process queue entries, check
connectivity, initialize providers, calculate standard backoff, apply jitter,
own a coroutine scope, or select a dispatcher. It reads the injected clock only
when central budgets are enabled.""",
)
insert_before_once(
    orchestration_doc,
    "## Maximum-delay selection\n",
    """## Budget state

`SynchronizationRetryRequest` may carry `RetryBudgetState` from the previously
accepted cycle. After final-delay selection, the orchestrator evaluates elapsed
and cumulative limits. Budget rejection returns `STOPPED` before the scheduler.

A `SCHEDULED` result may carry the exact next state for caller persistence.
`SCHEDULER_NOT_CONFIGURED` and `SCHEDULER_FAILED` never return advanced state.

""",
)
replace_once(
    orchestration_doc,
    "Standard backoff policies, deterministic jitter, attempt and elapsed budgets,\nretry hints, timeout separation, durable circuit state, half-open probes,",
    "Retry hints, timeout separation, durable circuit state, half-open probes,",
)

boundaries = "docs/architecture/retry-boundaries.md"
replace_once(
    boundaries,
    "> backoff, full/equal jitter through an injected deterministic random source, and\n> an attempt budget. Durable circuit breaking and the remaining time, hint,\n> observability, and administration gates are incomplete, so this is not yet the",
    "> backoff, full/equal jitter through injected deterministic randomness, attempt\n> limits, and central durable elapsed/cumulative budgets. Circuit breaking, hints,\n> timeout separation, observability, and administration remain incomplete, so this is not yet the",
)
replace_once(
    boundaries,
    """    jitter[Optional deterministic jitter]
    decision{Retry decision}
    direct[SchedulerProvider]""",
    """    jitter[Optional deterministic jitter]
    decision{Retry decision}
    budget{Elapsed and cumulative budgets}
    direct[SchedulerProvider]""",
)
replace_once(
    boundaries,
    """    jitter --> decision
    decision -->|Stop| stop
    decision -->|Direct| direct
    decision -->|Queued| queued""",
    """    jitter --> decision
    decision -->|Stop| stop
    decision -->|Retry| budget
    budget -->|Reject| stop
    budget -->|Direct| direct
    budget -->|Queued| queued""",
)
replace_once(
    boundaries,
    "The standard policy does not own queue transitions, clocks, elapsed windows,\nscheduler invocation, circuit persistence, provider retry hints, or manual",
    "The standard policy does not own queue transitions, clocks, central budget state,\nscheduler invocation, circuit persistence, provider retry hints, or manual",
)
replace_once(
    boundaries,
    "- routing accepted retry decisions to queue or scheduler transitions; and\n- future elapsed and aggregate budgets, circuit state, hints, manual operations,\n  and observability.",
    "- central elapsed/cumulative budget enforcement and state propagation;\n- routing accepted retry decisions to queue or scheduler transitions; and\n- future circuit state, hints, manual operations, and observability.",
)
replace_once(
    boundaries,
    """- retry availability timestamps;
- deferral without attempt consumption; and
- expired-lease recovery without resetting genuine retry history.""",
    """- retry availability timestamps;
- persisted elapsed/cumulative budget state;
- deferral without attempt or budget consumption; and
- expired-lease recovery without resetting retry or budget history.""",
)
replace_once(
    boundaries,
    """The current queue schema does not persist random-source configuration or a
separate jitter record. Restart determinism therefore depends on restoring the
same configured source and the same durable policy/request identity. General
versioned retry-policy configuration persistence remains part of the wider V1
foundation work.""",
    """Queue schema version 2 persists first-evaluation, last-evaluation, and
cumulative-delay evidence. It does not persist random-source configuration or a
separate jitter sample. Restart determinism therefore also depends on restoring
the same configured source and durable policy/request identity.""",
)
replace_once(
    boundaries,
    "- existing retry history is preserved.",
    "- existing retry and budget history is preserved.",
)
replace_once(
    boundaries,
    "base-delay, jitter, circuit, cancellation, and recovery behavior.",
    "base-delay, jitter, budgets, circuit, cancellation, and recovery behavior.",
)
replace_once(
    boundaries,
    "The shared retry engine still must add elapsed-time and aggregate-delay budgets,\nserver hints, timeout separation, durable closed/open/half-open circuit state,",
    "The shared retry engine still must add server hints, timeout separation,\ndurable closed/open/half-open circuit state,",
)

room_doc = "docs/android/room-queue-provider.md"
if "## Retry budget persistence and migration" not in read(room_doc):
    write(
        room_doc,
        read(room_doc) + """

## Retry budget persistence and migration

Schema version 2 adds nullable first-evaluation, last-evaluation, and cumulative-
delay columns. `MIGRATION_1_2` is non-destructive: existing retry attempt and
availability values are preserved, while historical budget fields remain null.
A successful retry reschedule writes attempt, availability, error, and budget
state in one lease-guarded update. Connectivity deferral and expired-lease
recovery preserve budget state unchanged.
""",
    )

for path, marker in (
    ("docs/api/queue-model.md", "## Retry budget state"),
    ("docs/api/queue-provider.md", "## Retry budget persistence"),
    ("docs/api/queued-synchronization-execution.md", "## Retry budget propagation"),
):
    if marker not in read(path):
        write(
            path,
            read(path) + f"""

{marker}

Accepted retries may carry `RetryBudgetState`, containing only bounded timing
evidence. Queue rescheduling persists that state atomically with attempt,
availability, and error. Constraint deferral and lease recovery preserve it
without consuming budget. Initial enqueue never accepts retry budget state.
""",
        )
