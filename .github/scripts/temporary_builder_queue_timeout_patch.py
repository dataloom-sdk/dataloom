from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1))


builder = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt"
replace_once(
    builder,
    "import io.dataloom.runtime.worker.QueueWorkerCoordinator\n",
    "import io.dataloom.runtime.worker.QueueWorkerCoordinator\n"
    "import io.dataloom.runtime.worker.QueueWorkerProviderTimeoutRuntime\n",
)
replace_once(
    builder,
    """        val queueProcessor = DurableQueueExecutionProcessor(
            queueProvider = queueProvider,
            executionHandler = executionHandler,
        )

        val coordinator = QueueWorkerCoordinator(
            queueProvider = queueProvider,
            queueProcessor = queueProcessor,
            schedulerProvider = schedulerProvider,
            clock = deps.clock,
            configuration = spec.configuration,
        )
""",
    """        val queueProviderTimeout = spec.queueProviderTimeout
        val coordinator = if (queueProviderTimeout != null) {
            QueueWorkerProviderTimeoutRuntime.create(
                queueProvider = queueProvider,
                executionHandler = executionHandler,
                schedulerProvider = schedulerProvider,
                clock = deps.clock,
                configuration = spec.configuration,
                queueProviderTimeout = queueProviderTimeout,
            )
        } else {
            val queueProcessor = DurableQueueExecutionProcessor(
                queueProvider = queueProvider,
                executionHandler = executionHandler,
            )
            QueueWorkerCoordinator(
                queueProvider = queueProvider,
                queueProcessor = queueProcessor,
                schedulerProvider = schedulerProvider,
                clock = deps.clock,
                configuration = spec.configuration,
            )
        }
""",
)
replace_once(
    builder,
    """     * The optional [SchedulerProvider] for the coordinator is resolved from
     * the default bindings when a scheduler ID is configured.
     */
""",
    """     * The optional [SchedulerProvider] for the coordinator is resolved from
     * the default bindings when a scheduler ID is configured. When
     * [DataLoomQueueWorkerSpec.queueProviderTimeout] is configured, the same
     * timeout-protected queue-provider instance is used for recovery,
     * acquisition, and every durable transition.
     */
""",
)

test_path = "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/facade/DataLoomBuilderTest.kt"
replace_once(
    test_path,
    """    private fun makeQueueWorkerSpec(
        workResolver: QueuedSynchronizationWorkResolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = makeRequest(),
                    bindings = makeBindings(),
                ),
            )
        },
    ) = DataLoomQueueWorkerSpec(
        workResolver = workResolver,
        retryPolicy = object : RetryPolicy {
            override val id = RetryPolicyId("no-retry")
            override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
                RetryDecision.Stop(io.dataloom.api.retry.RetryStopReason.NON_RECOVERABLE)
        },
        retryOperation = RetryOperation("test.operation"),
        configuration = QueueWorkerConfiguration(
            scheduleId = ScheduleId("worker-001"),
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            continuationDelay = SchedulingDelay.ZERO,
            recoverExpiredLeasesBeforeProcessing = false,
        ),
    )
""",
    """    private fun makeQueueWorkerSpec(
        workResolver: QueuedSynchronizationWorkResolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = makeRequest(),
                    bindings = makeBindings(),
                ),
            )
        },
        queueProviderTimeout: SchedulingDelay? = null,
    ): DataLoomQueueWorkerSpec {
        val retryPolicy = object : RetryPolicy {
            override val id = RetryPolicyId("no-retry")
            override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
                RetryDecision.Stop(io.dataloom.api.retry.RetryStopReason.NON_RECOVERABLE)
        }
        val configuration = QueueWorkerConfiguration(
            scheduleId = ScheduleId("worker-001"),
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            continuationDelay = SchedulingDelay.ZERO,
            recoverExpiredLeasesBeforeProcessing = false,
        )
        return if (queueProviderTimeout == null) {
            DataLoomQueueWorkerSpec(
                workResolver = workResolver,
                retryPolicy = retryPolicy,
                retryOperation = RetryOperation("test.operation"),
                configuration = configuration,
            )
        } else {
            DataLoomQueueWorkerSpec(
                workResolver = workResolver,
                retryPolicy = retryPolicy,
                retryOperation = RetryOperation("test.operation"),
                configuration = configuration,
                queueProviderTimeout = queueProviderTimeout,
            )
        }
    }
""",
)

new_tests = r'''
    @Test
    fun queueWorkerSpec_legacyConstructorPreservesNullQueueProviderTimeout() {
        assertNull(makeQueueWorkerSpec().queueProviderTimeout)
    }

    @Test
    fun queueWorkerSpec_preservesConfiguredQueueProviderTimeout() {
        val timeout = SchedulingDelay(750L)
        assertSame(timeout, makeQueueWorkerSpec(queueProviderTimeout = timeout).queueProviderTimeout)
    }

    @Test
    fun queueWorker_builderAppliesZeroQueueProviderTimeoutBeforeAcquisition() {
        val queue = FakeQueueProvider()
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), queue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueWorkerConfiguration(
                makeQueueWorkerSpec(queueProviderTimeout = SchedulingDelay.ZERO),
            )
            .build()

        runSuspend { dataLoom.initialize() }

        val result = runSuspend {
            dataLoom.queueWorker!!.run(
                QueueWorkerRunRequest(
                    processingRequest = QueueProcessingRequest(
                        acquireRequest = QueueAcquireRequest(
                            consumerId = QueueConsumerId("consumer-timeout"),
                            leaseId = QueueLeaseId("lease-timeout"),
                            acquiredAt = DataLoomInstant(1_000_000L),
                            leaseExpiresAt = DataLoomInstant(2_000_000L),
                            maxEntries = 1,
                        ),
                    ),
                    recoveryRequest = null,
                ),
            )
        }

        val failed = assertIs<QueueWorkerRunResult.ProcessingFailed>(result)
        val processing = assertIs<io.dataloom.runtime.queue.QueueProcessingResult.QueueProviderFailure>(
            failed.processingResult,
        )
        assertEquals(io.dataloom.runtime.queue.QueueProcessingFailureStage.ACQUISITION, processing.stage)
        assertEquals("QUEUE_PROVIDER_TIMEOUT", processing.error.code.value)
        assertEquals(0, queue.acquireCallCount)
    }

    @Test
    fun queueWorker_builderLegacySpecPreservesDirectQueueProviderPath() {
        val queue = FakeQueueProvider()
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), queue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueWorkerConfiguration(makeQueueWorkerSpec())
            .build()

        runSuspend { dataLoom.initialize() }

        val result = runSuspend {
            dataLoom.queueWorker!!.run(
                QueueWorkerRunRequest(
                    processingRequest = QueueProcessingRequest(
                        acquireRequest = QueueAcquireRequest(
                            consumerId = QueueConsumerId("consumer-legacy"),
                            leaseId = QueueLeaseId("lease-legacy"),
                            acquiredAt = DataLoomInstant(1_000_000L),
                            leaseExpiresAt = DataLoomInstant(2_000_000L),
                            maxEntries = 1,
                        ),
                    ),
                    recoveryRequest = null,
                ),
            )
        }

        assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
        assertEquals(1, queue.acquireCallCount)
    }

'''
replace_once(
    test_path,
    """    // =========================================================================
    // Queue submission builder integration
    // =========================================================================
""",
    new_tests + """    // =========================================================================
    // Queue submission builder integration
    // =========================================================================
""",
)

facade_doc = "docs/api/dataloom-facade.md"
replace_once(
    facade_doc,
    """- `configuration: QueueWorkerConfiguration`
""",
    """- `configuration: QueueWorkerConfiguration`
- `queueProviderTimeout: SchedulingDelay?` (optional; null preserves the direct provider path)
""",
)
replace_once(
    facade_doc,
    """`SynchronizationProviderBindings`. `SchedulerProvider` is optional; when absent,
the queue worker follows DL-032 scheduler-absent behavior.

Build fails deterministically when queue-worker configuration is requested but
""",
    """`SynchronizationProviderBindings`. `SchedulerProvider` is optional; when absent,
the queue worker follows DL-032 scheduler-absent behavior.

When `queueProviderTimeout` is configured, the builder automatically uses one
timeout-protected queue-provider instance for expired-lease recovery, atomic
acquisition, and every durable transition. A zero timeout rejects before the
delegate operation. Timed-out mutations are never replayed automatically.

Build fails deterministically when queue-worker configuration is requested but
""",
)

timeout_doc = "docs/api/queue-provider-timeouts.md"
replace_once(
    timeout_doc,
    """> **Status:** Partial V1 runtime slice. Cooperative provider-timeout enforcement
> exists for the queue-provider contract and an additive fully protected
> queue-worker assembly. Automatic `DataLoomBuilder` adoption, circuit assembly,
> platform hard-interruption adapters, and complete end-to-end qualification
> remain open.
""",
    """> **Status:** Partial V1 runtime slice. Cooperative provider-timeout enforcement
> exists for the queue-provider contract, the protected queue-worker runtime,
> and automatic `DataLoomBuilder` adoption. Circuit assembly, platform
> hard-interruption adapters, and complete end-to-end qualification remain open.
""",
)
builder_section = r'''
## DataLoomBuilder automatic assembly

`DataLoomQueueWorkerSpec` accepts an optional `queueProviderTimeout`:

```kotlin
val workerSpec = DataLoomQueueWorkerSpec(
    workResolver = workResolver,
    retryPolicy = retryPolicy,
    retryOperation = retryOperation,
    configuration = workerConfiguration,
    queueProviderTimeout = SchedulingDelay(5_000L),
)
```

When present, `DataLoomBuilder` automatically selects
`QueueWorkerProviderTimeoutRuntime` and uses one protected provider for recovery,
acquisition, and every transition. The original four-argument constructor
remains available and sets `queueProviderTimeout = null`, preserving historical
direct provider behavior.

Builder assembly performs no provider operation, clock read, queue mutation,
scheduler call, or coroutine launch.

'''
replace_once(
    timeout_doc,
    "## Result mapping\n",
    builder_section + "## Result mapping\n",
)
replace_once(
    timeout_doc,
    """- automatic queue-worker assembly through `DataLoomBuilder`;
- separately governed queue-submission timeout behavior;
""",
    """- separately governed queue-submission timeout behavior;
""",
)
