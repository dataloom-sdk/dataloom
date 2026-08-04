from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.rstrip() + "\n")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise SystemExit(
            f"Expected exactly one match in {path}, found {count}: {old[:120]!r}",
        )
    write(path, content.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Production handlers: correspondence before timeout/clock/coordinator/provider.
# ---------------------------------------------------------------------------
direct_handler = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/"
    "QueuedSynchronizationExecutionHandler.kt"
)
replace_once(
    direct_handler,
    """        val work = (resolution as QueuedSynchronizationWorkResolution.Resolved).work

        // Step 3–4: Execute synchronization; map coordinator rejections.
""",
    """        val work = (resolution as QueuedSynchronizationWorkResolution.Resolved).work
        QueuedStrategyDecisionCorrespondence.validate(entry, work)?.let { error ->
            return QueueEntryExecutionOutcome.Failed(
                error = error,
                disposition = QueueFailureDisposition.FAILED,
            )
        }

        // Step 3–4: Execute synchronization; map coordinator rejections.
""",
)
replace_once(
    direct_handler,
    """ * 3. Invoke [executionCoordinator] with the exact [QueuedSynchronizationWork]
 *    request and bindings.
""",
    """ * 3. Require the resolved work to contain the exact strategy decision stored
 *    on the durable entry. Mismatch fails before timeout, clock, coordinator,
 *    provider, or retry activity.
 * 4. Invoke [executionCoordinator] with the exact [QueuedSynchronizationWork]
 *    request and bindings.
""",
)
replace_once(
    direct_handler,
    """ * 4. If [SynchronizationExecutionResult.Rejected], map the structural
""",
    """ * 5. If [SynchronizationExecutionResult.Rejected], map the structural
""",
)
replace_once(
    direct_handler,
    """ * 5. If [SynchronizationExecutionResult.Executed]:
""",
    """ * 6. If [SynchronizationExecutionResult.Executed]:
""",
)
replace_once(
    direct_handler,
    """ * 6. Retry evaluation:
""",
    """ * 7. Retry evaluation:
""",
)
replace_once(
    direct_handler,
    """        // Step 3–4: Execute synchronization; map coordinator rejections.
""",
    """        // Step 4–5: Execute synchronization; map coordinator rejections.
""",
)
replace_once(
    direct_handler,
    """        // Step 5: Map the pipeline result to a queue outcome.
""",
    """        // Step 6: Map the pipeline result to a queue outcome.
""",
)

protected_handler = (
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/queue/"
    "ProviderProtectedQueuedSynchronizationExecutionHandler.kt"
)
replace_once(
    protected_handler,
    """        val work = (resolution as QueuedSynchronizationWorkResolution.Resolved).work

        val protectedExecution = when (val timedExecution = executeQueuedWorkflowWithTimeout(
""",
    """        val work = (resolution as QueuedSynchronizationWorkResolution.Resolved).work
        QueuedStrategyDecisionCorrespondence.validate(entry, work)?.let { error ->
            return localFailure(entry, error)
        }

        val protectedExecution = when (val timedExecution = executeQueuedWorkflowWithTimeout(
""",
)
replace_once(
    protected_handler,
    """ * Persisted workflow timeout evidence is enforced before protected
 * synchronization. Local resolver rejection and deadline rejection therefore
 * produce no provider evidence and invoke no protected provider operation.
""",
    """ * The resolved strategy decision is compared with durable queue state before
 * persisted workflow timeout enforcement or protected synchronization. Local
 * resolver rejection, strategy mismatch, and deadline rejection therefore
 * produce no provider evidence and invoke no protected provider operation.
""",
)

# ---------------------------------------------------------------------------
# Direct-handler integration test.
# ---------------------------------------------------------------------------
direct_test = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/queue/"
    "QueuedSynchronizationExecutionHandlerTest.kt"
)
replace_once(
    direct_test,
    "import io.dataloom.api.retry.RetryStopReason\n",
    """import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
""",
)
replace_once(
    direct_test,
    """    private fun makeLeasedEntry(
        request: SynchronizationRequest = sampleRequest,
        retryAttempt: RetryAttempt? = null,
    ): QueueEntry {
""",
    """    private fun makeLeasedEntry(
        request: SynchronizationRequest = sampleRequest,
        retryAttempt: RetryAttempt? = null,
        strategyDecision: PersistedStrategyDecision? = null,
    ): QueueEntry {
""",
)
replace_once(
    direct_test,
    """            lease = lease,
            retryAttempt = retryAttempt,
        )
    }

    private fun makeSucceededResult() = SynchronizationResult.Succeeded(
""",
    """            lease = lease,
            retryAttempt = retryAttempt,
            strategyDecision = strategyDecision,
        )
    }

    private fun strategyDecision(
        version: Long,
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-queued-1"),
        planId = StrategyPlanId("plan-queued-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(version),
        disposition = StrategyDisposition.DEFER,
    )

    private fun makeSucceededResult() = SynchronizationResult.Succeeded(
""",
)
replace_once(
    direct_test,
    """    // =========================================================================
    // Coordinator structural rejection → Failed outcome
    // =========================================================================
""",
    """    @Test
    fun `changed resolved strategy decision fails before pipeline invocation`() {
        val pipeline = ControlledPipeline { makeSucceededResult() }
        val (coordinator, bindings) = makeCoordinatorWithPipeline(pipeline)
        val resolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = entry.synchronizationRequest,
                    bindings = bindings,
                    strategyDecision = strategyDecision(version = 4L),
                ),
            )
        }
        val handler = QueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            executionCoordinator = coordinator,
            retryEvaluator = makeRetryEvaluator(),
            retryOperation = retryOp,
        )

        val outcome = runSuspend {
            handler.execute(
                makeLeasedEntry(strategyDecision = strategyDecision(version = 3L)),
            )
        }

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals("DL-Q-STRATEGY-DECISION-MISMATCH", failed.error.code.value)
        assertEquals(ErrorCategory.CONFIGURATION, failed.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failed.error.recoverability)
        assertEquals(0, pipeline.executeCallCount)
    }

    // =========================================================================
    // Coordinator structural rejection → Failed outcome
    // =========================================================================
""",
)

# ---------------------------------------------------------------------------
# Protected-handler integration tests and fixtures.
# ---------------------------------------------------------------------------
protected_test = (
    "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/queue/"
    "ProviderProtectedQueuedSynchronizationExecutionHandlerTest.kt"
)
replace_once(
    protected_test,
    "import io.dataloom.api.retry.WorkflowTimeoutState\n",
    """import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
""",
)
replace_once(
    protected_test,
    """    @Test
    fun `expired persisted workflow deadline prevents protected execution`() = runTest {
""",
    """    @Test
    fun `changed resolved strategy decision prevents protected execution`() = runTest {
        val request = request()
        val facade = RecordingProtectedSynchronization(
            ProviderProtectedSynchronizationExecutionResult.Executed(
                ProviderProtectedSynchronizationResult(
                    synchronizationResult = succeeded(request),
                    operationEvidence = emptyList(),
                ),
            ),
        )
        val handler = handler(
            resolver = resolved(
                request = request,
                bindings = bindings(),
                strategyDecision = strategyDecision(version = 4L),
            ),
            facade = facade,
        )

        val result = handler.execute(
            entry(
                request = request,
                strategyDecision = strategyDecision(version = 3L),
            ),
        )

        val outcome = assertIs<QueueEntryExecutionOutcome.Failed>(result.outcome)
        assertEquals("DL-Q-STRATEGY-DECISION-MISMATCH", outcome.error.code.value)
        assertEquals(ErrorCategory.CONFIGURATION, outcome.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, outcome.error.recoverability)
        assertEquals(0, facade.calls)
        assertEquals(null, result.executionResult)
        assertEquals(emptyList(), result.operationEvidence)
    }

    @Test
    fun `matching resolved strategy decision reaches protected execution`() = runTest {
        val request = request()
        val decision = strategyDecision(version = 3L)
        val facade = RecordingProtectedSynchronization(
            ProviderProtectedSynchronizationExecutionResult.Executed(
                ProviderProtectedSynchronizationResult(
                    synchronizationResult = succeeded(request),
                    operationEvidence = emptyList(),
                ),
            ),
        )
        val handler = handler(
            resolver = resolved(request, bindings(), decision),
            facade = facade,
        )

        val result = handler.execute(entry(request, decision))

        assertIs<QueueEntryExecutionOutcome.Completed>(result.outcome)
        assertEquals(1, facade.calls)
    }

    @Test
    fun `expired persisted workflow deadline prevents protected execution`() = runTest {
""",
)
replace_once(
    protected_test,
    """    private fun resolved(
        request: SynchronizationRequest,
        bindings: SynchronizationProviderBindings,
    ): QueuedSynchronizationWorkResolver = QueuedSynchronizationWorkResolver {
        QueuedSynchronizationWorkResolution.Resolved(
            QueuedSynchronizationWork(request, bindings),
        )
    }
""",
    """    private fun resolved(
        request: SynchronizationRequest,
        bindings: SynchronizationProviderBindings,
        strategyDecision: PersistedStrategyDecision? = null,
    ): QueuedSynchronizationWorkResolver = QueuedSynchronizationWorkResolver {
        QueuedSynchronizationWorkResolution.Resolved(
            QueuedSynchronizationWork(
                request = request,
                bindings = bindings,
                strategyDecision = strategyDecision,
            ),
        )
    }
""",
)
replace_once(
    protected_test,
    """    private fun successfulEvidence(): ProviderProtectionOperationEvidence =
""",
    """    private fun strategyDecision(
        version: Long,
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-protected-1"),
        planId = StrategyPlanId("plan-protected-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(version),
        disposition = StrategyDisposition.DEFER,
    )

    private fun successfulEvidence(): ProviderProtectionOperationEvidence =
""",
)
replace_once(
    protected_test,
    """    private fun entry(request: SynchronizationRequest): QueueEntry = QueueEntry(
""",
    """    private fun entry(
        request: SynchronizationRequest,
        strategyDecision: PersistedStrategyDecision? = null,
    ): QueueEntry = QueueEntry(
""",
)
replace_once(
    protected_test,
    """            expiresAt = DataLoomInstant(10_000L),
        ),
    )
""",
    """            expiresAt = DataLoomInstant(10_000L),
        ),
        strategyDecision = strategyDecision,
    )
""",
)

# ---------------------------------------------------------------------------
# Current documentation and acceptance checkpoint.
# ---------------------------------------------------------------------------
strategy_doc = "docs/api/synchronization-strategy.md"
replace_once(
    strategy_doc,
    """Apple file-backed queues preserve the exact identity through retry, non-retry
deferral, lease recovery, reopen, and migration. Legacy work remains explicitly
unplanned (`null`) rather than receiving current configuration.

The next execution gate must reconstruct or load the immutable accepted plan
""",
    """Apple file-backed queues preserve the exact identity through retry, non-retry
deferral, lease recovery, reopen, and migration. Legacy work remains explicitly
unplanned (`null`) rather than receiving current configuration. Both direct and
provider-protected queued handlers require the application resolver to return
the exact durable decision before timeout, clock, coordinator, provider, or
retry activity.

The next execution gate must reconstruct or load the immutable accepted plan
""",
)
replace_once(
    strategy_doc,
    """protected remote-first execution, and bounded strategy-decision queue persistence
are implemented in common Kotlin. Room and Apple stores preserve the same
""",
    """protected remote-first execution, bounded strategy-decision queue persistence,
and fail-closed queued resolver correspondence are implemented in common
Kotlin. Room and Apple stores preserve the same
""",
)

readme = "README.md"
replace_once(
    readme,
    """direct network-only and remote-first vertical slices; fail-closed queue admission and durable strategy-decision identity across in-memory, Room, and Apple queues | Complete offline-first, cache-first, hybrid, and adaptive runtimes; persist/replay the immutable accepted execution plan without current-policy re-evaluation; qualify the full connectivity/cache/fallback/retry/conflict/restart matrix without silent strategy changes |
""",
    """direct network-only and remote-first vertical slices; fail-closed queue admission, durable strategy-decision identity across in-memory, Room, and Apple queues, and queued resolver correspondence before execution | Complete offline-first, cache-first, hybrid, and adaptive runtimes; persist/replay the immutable accepted execution plan without current-policy re-evaluation; qualify the full connectivity/cache/fallback/retry/conflict/restart matrix without silent strategy changes |
""",
)

write(
    "docs/audits/DL-039B-queued-strategy-decision-correspondence-checkpoint.md",
    """# DL-039B queued strategy-decision correspondence checkpoint

## Scope

This slice closes the resolver-contract gap after durable strategy-decision
persistence. Application-owned queued-work resolvers must return the exact
`PersistedStrategyDecision` stored on the acquired `QueueEntry`.

## Accepted invariants

- Legacy queue entry plus legacy resolved work (`null` / `null`) remains valid.
- An exact non-null decision is forwarded unchanged.
- A changed, dropped, or invented decision returns
  `DL-Q-STRATEGY-DECISION-MISMATCH` as a non-recoverable configuration failure.
- Validation occurs before workflow timeout enforcement, clock access,
  coordinator execution, provider resolution, protected facade invocation,
  retry evaluation, or a queue transition.
- Both direct and provider-protected queued handlers use the same correspondence
  boundary and canonical error.
- Failure diagnostics contain no dynamic decision, plan, or profile identifiers.
- No public API, ABI, durable schema, Apple file format, dependency, permission,
  or platform capability changes in this slice.

## Evidence

Focused common tests cover null/null compatibility, exact match, changed,
dropped, invented, and redacted failure cases. Direct-handler integration proves
that a mismatch invokes no synchronization pipeline. Protected-handler
integration proves that a mismatch invokes no protected facade and returns no
provider/circuit evidence; exact non-null correspondence continues normally.
Permanent PR, Android, and Apple workflows provide unchanged platform regression
coverage on the final immutable head.

## Remaining DL-039B work

The persisted decision remains bounded identity, not a complete immutable
`StrategyExecutionPlan`. The next architecture slice must load or reconstruct
the accepted ordered operations, required capabilities, origin, consistency,
and fallback branch without current-policy re-evaluation. Complete offline-
first, cache-first, hybrid, adaptive, conflict/event integration, process-loss
proof, and native Android/KMP Android/KMP iOS reference matrices remain open.
""",
)

print("Applied queued strategy-decision correspondence integration.")
