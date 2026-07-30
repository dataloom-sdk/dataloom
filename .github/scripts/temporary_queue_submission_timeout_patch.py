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
    "import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder\n",
    "import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder\n"
    "import io.dataloom.runtime.submission.QueueSubmissionProviderTimeoutRuntime\n",
)
replace_once(
    builder,
    "    private var queueSubmissionEncoderValue: QueuedSynchronizationWorkEncoder? = null\n",
    "    private var queueSubmissionSpecValue: DataLoomQueueSubmissionSpec? = null\n",
)
replace_once(
    builder,
    """    public fun queueSubmissionEncoder(
        encoder: QueuedSynchronizationWorkEncoder,
    ): DataLoomBuilder = apply {
        queueSubmissionEncoderValue = encoder
    }

""",
    """    public fun queueSubmissionEncoder(
        encoder: QueuedSynchronizationWorkEncoder,
    ): DataLoomBuilder = apply {
        queueSubmissionSpecValue = DataLoomQueueSubmissionSpec(encoder)
    }

    /**
     * Configures queue submission with an explicit immutable [spec].
     *
     * The optional queue-provider timeout applies only to the single enqueue
     * operation. It is independent from queue-worker and scheduler timeouts.
     * When both queue-submission setters are called, the most recent call is the
     * effective configuration.
     */
    public fun queueSubmissionConfiguration(
        spec: DataLoomQueueSubmissionSpec,
    ): DataLoomBuilder = apply {
        queueSubmissionSpecValue = spec
    }

""",
)
replace_once(
    builder,
    """        val queueSubmission = queueSubmissionEncoderValue?.let { encoder ->
            val legacyBindings = bindings
                ?: throw DataLoomBuildException(
                    "DataLoomBuilder queueSubmissionEncoder currently requires " +
                        "defaultProviderBindings.",
                )
            buildQueueSubmission(
                encoder = encoder,
                registry = registry,
                bindings = legacyBindings,
            )
        }
""",
    """        val queueSubmission = queueSubmissionSpecValue?.let { spec ->
            val legacyBindings = bindings
                ?: throw DataLoomBuildException(
                    "DataLoomBuilder queue submission currently requires " +
                        "defaultProviderBindings.",
                )
            buildQueueSubmission(
                spec = spec,
                registry = registry,
                bindings = legacyBindings,
                deps = deps,
            )
        }
""",
)
replace_once(
    builder,
    """    private fun buildQueueSubmission(
        encoder: QueuedSynchronizationWorkEncoder,
        registry: ProviderRegistry,
        bindings: SynchronizationProviderBindings,
    ): DataLoomQueueSubmission {
""",
    """    private fun buildQueueSubmission(
        spec: DataLoomQueueSubmissionSpec,
        registry: ProviderRegistry,
        bindings: SynchronizationProviderBindings,
        deps: RuntimeDependencies,
    ): DataLoomQueueSubmission {
""",
)
replace_once(
    builder,
    """        return DefaultDataLoomQueueSubmission(
            queueProvider = queueProvider,
            encoder = encoder,
        )
""",
    """        val queueProviderTimeout = spec.queueProviderTimeout
        return if (queueProviderTimeout != null) {
            QueueSubmissionProviderTimeoutRuntime.create(
                queueProvider = queueProvider,
                encoder = spec.encoder,
                clock = deps.clock,
                queueProviderTimeout = queueProviderTimeout,
            )
        } else {
            DefaultDataLoomQueueSubmission(
                queueProvider = queueProvider,
                encoder = spec.encoder,
            )
        }
""",
)
replace_once(
    builder,
    """     * Build performs no encoding, no enqueue operation, no clock read, and no
     * identifier generation.
     */
""",
    """     * Build performs no encoding, enqueue operation, timeout execution, clock
     * read, or identifier generation. A configured submission timeout is
     * assembled structurally and applies only when `submit` invokes enqueue.
     */
""",
)

submission_api = "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/submission/DataLoomQueueSubmission.kt"
replace_once(
    submission_api,
    """ * [io.dataloom.runtime.facade.DataLoom.queueSubmission] is `null` when
 * [io.dataloom.runtime.facade.DataLoomBuilder.queueSubmissionEncoder] was not
 * supplied or a valid [io.dataloom.api.queue.QueueProvider] binding was
 * not present. Non-null when all required queue dependencies are present.
""",
    """ * [io.dataloom.runtime.facade.DataLoom.queueSubmission] is `null` when
 * neither [io.dataloom.runtime.facade.DataLoomBuilder.queueSubmissionEncoder]
 * nor [io.dataloom.runtime.facade.DataLoomBuilder.queueSubmissionConfiguration]
 * was supplied, or when a valid [io.dataloom.api.queue.QueueProvider] binding
 * was not present. Non-null when all required queue dependencies are present.
""",
)
replace_once(
    submission_api,
    """ * use a stable [io.dataloom.api.identifier.QueueEntryId]
 * for retry attempts on the same logical submission.
""",
    """ * use a stable [io.dataloom.api.identifier.QueueEntryId]
 * for retry attempts on the same logical submission. A configured provider
 * timeout is also an unknown external outcome and does not prove rollback.
""",
)

test_path = "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/facade/DataLoomBuilderTest.kt"
replace_once(
    test_path,
    """    private class FakeQueueProvider(id: String = "queue-primary") : QueueProvider {
        var initializeCallCount: Int = 0
        var closeCallCount: Int = 0
        var acquireCallCount: Int = 0
""",
    """    private class FakeQueueProvider(
        id: String = "queue-primary",
        var enqueueResult: ProviderOperationResult<Unit> = ProviderOperationResult.Failure(FakeError()),
    ) : QueueProvider {
        var initializeCallCount: Int = 0
        var closeCallCount: Int = 0
        var enqueueCallCount: Int = 0
        var acquireCallCount: Int = 0
""",
)
replace_once(
    test_path,
    """        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())
""",
    """        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> {
            enqueueCallCount++
            return enqueueResult
        }
""",
)
replace_once(
    test_path,
    "import io.dataloom.api.identifier.ProviderId\n",
    "import io.dataloom.api.provider.ProviderId\n",
) if "import io.dataloom.api.identifier.ProviderId\n" in Path(test_path).read_text() else None

runtime_test = "dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/submission/QueueSubmissionProviderTimeoutRuntimeTest.kt"
replace_once(
    runtime_test,
    "import io.dataloom.api.identifier.ProviderId\n",
    "import io.dataloom.api.provider.ProviderId\n",
)

submission_helper = r'''

    private fun makeQueueSubmission(
        id: QueueEntryId = QueueEntryId("submission-001"),
    ): io.dataloom.runtime.submission.QueuedSynchronizationSubmission =
        io.dataloom.runtime.submission.QueuedSynchronizationSubmission(
            queueEntryId = id,
            work = QueuedSynchronizationWork(
                request = makeRequest(),
                bindings = makeBindings(),
            ),
            availableAt = DataLoomInstant(1_000_000L),
        )
'''
replace_once(
    test_path,
    """    // =========================================================================
    // Builder requirements — missing mandatory fields
    // =========================================================================
""",
    submission_helper + """
    // =========================================================================
    // Builder requirements — missing mandatory fields
    // =========================================================================
""",
)

new_tests = r'''

    @Test
    fun queueSubmissionSpec_directConstructorPreservesNullTimeout() {
        val spec = DataLoomQueueSubmissionSpec(makeQueueSubmissionEncoder())
        assertNull(spec.queueProviderTimeout)
    }

    @Test
    fun queueSubmissionSpec_preservesConfiguredTimeout() {
        val timeout = SchedulingDelay(600L)
        val spec = DataLoomQueueSubmissionSpec(
            encoder = makeQueueSubmissionEncoder(),
            queueProviderTimeout = timeout,
        )
        assertSame(timeout, spec.queueProviderTimeout)
    }

    @Test
    fun queueSubmission_builderZeroTimeoutRejectsBeforeEnqueue() {
        val queue = FakeQueueProvider()
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), queue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueSubmissionConfiguration(
                DataLoomQueueSubmissionSpec(
                    encoder = makeQueueSubmissionEncoder(),
                    queueProviderTimeout = SchedulingDelay.ZERO,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val submission = makeQueueSubmission()
        val result = runSuspend { dataLoom.queueSubmission!!.submit(submission) }
        val failure = assertIs<io.dataloom.runtime.submission.QueueSubmissionResult.QueueProviderFailure>(
            result,
        )

        assertEquals(0, queue.enqueueCallCount)
        assertEquals(submission.queueEntryId, failure.queueEntryId)
        assertEquals(
            io.dataloom.runtime.submission.QueueSubmissionFailureStage.QUEUE_PROVIDER_ENQUEUE,
            failure.failureStage,
        )
        assertEquals("QUEUE_PROVIDER_TIMEOUT", failure.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
    }

    @Test
    fun queueSubmission_legacyEncoderPreservesDirectEnqueuePath() {
        val queue = FakeQueueProvider(
            enqueueResult = ProviderOperationResult.Success(Unit),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), queue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueSubmissionEncoder(makeQueueSubmissionEncoder())
            .build()

        runSuspend { dataLoom.initialize() }
        val submission = makeQueueSubmission()
        val result = runSuspend { dataLoom.queueSubmission!!.submit(submission) }

        val enqueued = assertIs<io.dataloom.runtime.submission.QueueSubmissionResult.Enqueued>(result)
        assertEquals(1, queue.enqueueCallCount)
        assertEquals(submission.queueEntryId, enqueued.queueEntryId)
    }
'''
replace_once(
    test_path,
    """    // =========================================================================
    // Strategy-aware network-only execution
    // =========================================================================
""",
    new_tests + """
    // =========================================================================
    // Strategy-aware network-only execution
    // =========================================================================
""",
)

facade_doc = "docs/api/dataloom-facade.md"
replace_once(
    facade_doc,
    """| `queueWorkerConfiguration(spec)` | Configures the optional queue-worker capability. |
""",
    """| `queueWorkerConfiguration(spec)` | Configures the optional queue-worker capability. |
| `queueSubmissionEncoder(e)` | Configures direct queue submission with no provider timeout. |
| `queueSubmissionConfiguration(spec)` | Configures queue submission with an optional enqueue timeout. |
""",
)
replace_once(
    facade_doc,
    """`DataLoomQueueSubmission` is exposed through `DataLoom.queueSubmission` when
`DataLoomBuilder.queueSubmissionEncoder` is supplied with a valid
`QueueProvider` binding.
""",
    """`DataLoomQueueSubmission` is exposed through `DataLoom.queueSubmission` when
`DataLoomBuilder.queueSubmissionEncoder` or
`DataLoomBuilder.queueSubmissionConfiguration` is supplied with a valid
`QueueProvider` binding.
""",
)
replace_once(
    facade_doc,
    """No encoding or enqueue operation is performed during `build()`.
""",
    """A `DataLoomQueueSubmissionSpec` may configure a timeout for the single enqueue
operation. A null timeout preserves direct enqueue; zero rejects before provider
invocation. Timed-out enqueue remains durably ambiguous and is never replayed
automatically.

No encoding, enqueue, timeout execution, or clock read is performed during
`build()`.
""",
)

queue_doc = "docs/api/queue-submission.md"
replace_once(
    queue_doc,
    """> **Status:** Available queue-submission foundation. Applications still own
> work encoding; publication and complete consumer qualification remain open.
""",
    """> **Status:** Available queue-submission foundation with separately governed
> enqueue timeout assembly. Applications still own work encoding; publication
> and complete consumer qualification remain open.
""",
)
replace_once(
    queue_doc,
    """- `DataLoomQueueSubmission` — narrow public submission capability
""",
    """- `DataLoomQueueSubmission` — narrow public submission capability
- `DataLoomQueueSubmissionSpec` — builder configuration with optional enqueue timeout
- `QueueSubmissionProviderTimeoutRuntime` — standalone protected assembly
""",
)
replace_once(
    queue_doc,
    """- `null` when `DataLoomBuilder.queueSubmissionEncoder` was not supplied or
  when a valid `QueueProvider` binding was absent.
- Non-null when a `QueuedSynchronizationWorkEncoder` and a valid queue
  provider binding are both configured.
""",
    """- `null` when neither queue-submission builder method was supplied or when
  a valid `QueueProvider` binding was absent.
- Non-null when an encoder or `DataLoomQueueSubmissionSpec` and a valid queue
  provider binding are configured.
""",
)
replace_once(
    queue_doc,
    """    .queueSubmissionEncoder(myEncoder)
    .build()
```

Build rules:
- `queueSubmissionEncoder` is optional.
- When no encoder is supplied, `queueSubmission` is `null`.
- When encoder is supplied, a valid `QueueProvider` binding must be present.
""",
    """    .queueSubmissionConfiguration(
        DataLoomQueueSubmissionSpec(
            encoder = myEncoder,
            queueProviderTimeout = SchedulingDelay(5_000L),
        ),
    )
    .build()
```

The historical `.queueSubmissionEncoder(myEncoder)` method remains available and
selects a null timeout.

Build rules:
- both queue-submission methods are optional;
- when neither is supplied, `queueSubmission` is `null`;
- when both are called, the most recent call is effective;
- when configured, a valid `QueueProvider` binding must be present;
""",
)
replace_once(
    queue_doc,
    """- Build performs no encoding, no enqueue, and no clock read.
""",
    """- Build performs no encoding, enqueue, timeout execution, or clock read.
""",
)
replace_once(
    queue_doc,
    """## Idempotency boundary

- `QueueEntryId` should remain stable when the application retries the same
""",
    """## Enqueue timeout boundary

A configured timeout is applied after encoding and structural validation, and
only to the single `QueueProvider.enqueue` invocation. Zero rejects before
provider invocation. Positive timeouts use cooperative cancellation.

Because enqueue may commit before cancellation is observed, timeout returns
`QueueProviderFailure` with code `QUEUE_PROVIDER_TIMEOUT`, failure stage
`QUEUE_PROVIDER_ENQUEUE`, and `Recoverability.UNKNOWN`. The exact stable
`QueueEntryId` is preserved and no automatic replay occurs.

## Idempotency boundary

- `QueueEntryId` should remain stable when the application retries the same
""",
)

timeout_doc = "docs/api/queue-provider-timeouts.md"
replace_once(
    timeout_doc,
    """> exists for the queue-provider contract, the protected queue-worker runtime,
> and automatic `DataLoomBuilder` adoption. Circuit assembly, platform
""",
    """> exists for the queue-provider contract, protected queue-worker and
> queue-submission runtimes, and automatic `DataLoomBuilder` adoption. Circuit assembly, platform
""",
)
submission_section = r'''
## Queue-submission timeout assembly

`QueueSubmissionProviderTimeoutRuntime.create(...)` protects only the single
enqueue call made after encoding and structural validation:

```kotlin
val submission = QueueSubmissionProviderTimeoutRuntime.create(
    queueProvider = queueProvider,
    encoder = encoder,
    clock = clock,
    queueProviderTimeout = SchedulingDelay(5_000L),
)
```

`DataLoomBuilder.queueSubmissionConfiguration(...)` accepts a
`DataLoomQueueSubmissionSpec` and selects the same protected runtime when its
timeout is non-null. The historical `queueSubmissionEncoder(...)` method remains
direct and source compatible.

A timed-out enqueue returns `QueueProviderFailure` with the stable queue-entry ID,
`QUEUE_PROVIDER_ENQUEUE`, and `Recoverability.UNKNOWN`. It is never replayed
automatically.

'''
replace_once(
    timeout_doc,
    "## Result mapping\n",
    submission_section + "## Result mapping\n",
)
replace_once(
    timeout_doc,
    """- separately governed queue-submission timeout behavior;
""",
    "",
)
