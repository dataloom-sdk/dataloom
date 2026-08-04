package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConfigurationVersion
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.LocaleTag
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RequestId
import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.UserId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationId
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyExecutionPlanCodec
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.time.DataLoomInstant

/** Strict, deterministic queue snapshot codec used by the Apple provider. */
internal object AppleQueueStateFileCodec {
    private const val LEGACY_HEADER: String = "DATALOOM_QUEUE_STATE\t1"
    private const val VERSION_TWO_HEADER: String = "DATALOOM_QUEUE_STATE\t2"
    private const val VERSION_THREE_HEADER: String = "DATALOOM_QUEUE_STATE\t3"
    private const val CURRENT_HEADER: String = "DATALOOM_QUEUE_STATE\t4"
    private const val ENTRY_PREFIX: String = "E"
    private const val RECEIPT_PREFIX: String = "R"
    private const val LEGACY_ENTRY_FIELD_COUNT: Int = 35
    private const val VERSION_THREE_ENTRY_FIELD_COUNT: Int = 42
    private const val CURRENT_ENTRY_FIELD_COUNT: Int = 43
    private const val RECEIPT_FIELD_COUNT: Int = 13

    /** Encodes the historical version-1 entry-only format for migration evidence. */
    fun encode(entries: Map<String, QueueEntry>): String = encodeLegacy(entries)

    /** Decodes any supported historical or current format and returns queue entries. */
    fun decode(content: String): MutableMap<String, QueueEntry> =
        decodeSnapshot(content).entries

    /** Encodes the current version-4 entry-plus-receipt-plus-plan snapshot. */
    fun encodeSnapshot(snapshot: AppleQueueSnapshot): String {
        validateSnapshotBounds(snapshot)
        val content = buildString {
            append(CURRENT_HEADER)
            append('\n')
            snapshot.entries.entries.sortedBy { it.key }.forEach { mapEntry ->
                val id = mapEntry.key
                val entry = mapEntry.value
                check(id == entry.id.value) {
                    "Queue snapshot map key does not match the entry identifier."
                }
                append(ENTRY_PREFIX)
                append('\t')
                append(
                    encodeEntry(
                        entry = entry,
                        includeStrategyDecision = true,
                        includeStrategyPlan = true,
                    ),
                )
                append('\n')
            }
            snapshot.retryAdministrationReceipts.entries.sortedBy { it.key }
                .forEach { mapEntry ->
                    val commandId = mapEntry.key
                    val receipt = mapEntry.value
                    check(commandId == receipt.command.request.commandId.value) {
                        "Queue receipt map key does not match the command identifier."
                    }
                    append(RECEIPT_PREFIX)
                    append('\t')
                    append(encodeReceipt(receipt))
                    append('\n')
                }
        }
        ensureFileBound(content)
        return content
    }

    /** Decodes versions 1, 2, 3, or 4 and reconstructs the complete snapshot. */
    fun decodeSnapshot(content: String): AppleQueueSnapshot {
        ensureInputBound(content)
        return try {
            val lines = content.split('\n')
            require(lines.isNotEmpty())
            when (lines.first()) {
                LEGACY_HEADER -> decodeLegacyLines(lines)
                VERSION_TWO_HEADER -> decodeVersionTwoLines(lines)
                VERSION_THREE_HEADER -> decodeVersionThreeLines(lines)
                CURRENT_HEADER -> decodeCurrentLines(lines)
                else -> error("Unsupported Apple queue snapshot version.")
            }
        } catch (limit: AppleQueueFileLimitException) {
            throw limit
        } catch (limit: AppleQueueEntryLimitException) {
            throw limit
        } catch (limit: AppleQueueReceiptLimitException) {
            throw limit
        } catch (invalid: Exception) {
            throw AppleQueueMalformedStateException(invalid)
        }
    }

    private fun encodeLegacy(entries: Map<String, QueueEntry>): String {
        if (entries.size > APPLE_QUEUE_MAX_ENTRY_COUNT) {
            throw AppleQueueEntryLimitException()
        }
        val content = buildString {
            append(LEGACY_HEADER)
            append('\n')
            entries.entries.sortedBy { it.key }.forEach { mapEntry ->
                val id = mapEntry.key
                val entry = mapEntry.value
                check(id == entry.id.value) {
                    "Queue snapshot map key does not match the entry identifier."
                }
                check(entry.strategyDecision == null) {
                    "Version-1 queue snapshots cannot encode a strategy decision."
                }
                check(entry.strategyPlan == null) {
                    "Version-1 queue snapshots cannot encode a strategy plan."
                }
                append(
                    encodeEntry(
                        entry = entry,
                        includeStrategyDecision = false,
                        includeStrategyPlan = false,
                    ),
                )
                append('\n')
            }
        }
        ensureFileBound(content)
        return content
    }

    private fun decodeLegacyLines(lines: List<String>): AppleQueueSnapshot {
        val entries = linkedMapOf<String, QueueEntry>()
        forEachDataLine(lines) { line ->
            if (entries.size >= APPLE_QUEUE_MAX_ENTRY_COUNT) {
                throw AppleQueueEntryLimitException()
            }
            val entry = decodeEntry(
                line = line,
                includeStrategyDecision = false,
                includeStrategyPlan = false,
            )
            require(entries.put(entry.id.value, entry) == null)
        }
        return AppleQueueSnapshot(entries = entries)
    }

    private fun decodeVersionTwoLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(
            lines = lines,
            includeStrategyDecision = false,
            includeStrategyPlan = false,
        )

    private fun decodeVersionThreeLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(
            lines = lines,
            includeStrategyDecision = true,
            includeStrategyPlan = false,
        )

    private fun decodeCurrentLines(lines: List<String>): AppleQueueSnapshot =
        decodePrefixedLines(
            lines = lines,
            includeStrategyDecision = true,
            includeStrategyPlan = true,
        )

    private fun decodePrefixedLines(
        lines: List<String>,
        includeStrategyDecision: Boolean,
        includeStrategyPlan: Boolean,
    ): AppleQueueSnapshot {
        val snapshot = AppleQueueSnapshot()
        forEachDataLine(lines) { line ->
            val separator = line.indexOf('\t')
            require(separator == 1)
            val prefix = line.substring(0, separator)
            val payload = line.substring(separator + 1)
            require(payload.isNotEmpty())
            when (prefix) {
                ENTRY_PREFIX -> {
                    if (snapshot.entries.size >= APPLE_QUEUE_MAX_ENTRY_COUNT) {
                        throw AppleQueueEntryLimitException()
                    }
                    val entry = decodeEntry(
                        line = payload,
                        includeStrategyDecision = includeStrategyDecision,
                        includeStrategyPlan = includeStrategyPlan,
                    )
                    require(snapshot.entries.put(entry.id.value, entry) == null)
                }
                RECEIPT_PREFIX -> {
                    if (snapshot.retryAdministrationReceipts.size >=
                        APPLE_QUEUE_MAX_RETRY_ADMINISTRATION_RECEIPT_COUNT
                    ) {
                        throw AppleQueueReceiptLimitException()
                    }
                    val receipt = decodeReceipt(payload)
                    val commandId = receipt.command.request.commandId.value
                    require(
                        snapshot.retryAdministrationReceipts.put(commandId, receipt) == null,
                    )
                }
                else -> error("Unknown Apple queue snapshot record prefix.")
            }
        }
        return snapshot
    }

    private inline fun forEachDataLine(
        lines: List<String>,
        block: (String) -> Unit,
    ) {
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) {
                require(index == lines.lastIndex)
                continue
            }
            block(line)
        }
    }

    private fun validateSnapshotBounds(snapshot: AppleQueueSnapshot) {
        if (snapshot.entries.size > APPLE_QUEUE_MAX_ENTRY_COUNT) {
            throw AppleQueueEntryLimitException()
        }
        if (snapshot.retryAdministrationReceipts.size >
            APPLE_QUEUE_MAX_RETRY_ADMINISTRATION_RECEIPT_COUNT
        ) {
            throw AppleQueueReceiptLimitException()
        }
    }

    private fun ensureInputBound(content: String) {
        if (content.encodeToByteArray().size > APPLE_QUEUE_MAX_STATE_FILE_BYTES) {
            throw AppleQueueFileLimitException()
        }
    }

    private fun ensureFileBound(content: String) = ensureInputBound(content)

    private fun encodeReceipt(receipt: AppleRetryAdministrationReceipt): String {
        val command = receipt.command
        val request = command.request
        val failure = request.originalFailure
        return listOf(
            appleQueueHexEncode(request.commandId.value),
            appleQueueHexEncode(request.queueEntryId.value),
            appleQueueHexEncode(request.principalId.value),
            request.requestedAt.epochMilliseconds.toString(),
            request.action.name,
            appleQueueHexEncode(request.reason.value),
            appleQueueHexEncode(failure.code.value),
            failure.category.name,
            failure.severity.name,
            failure.recoverability.name,
            appleQueueHexEncode(command.authorizationId.value),
            command.effectiveRecoverability.name,
            receipt.appliedAt.epochMilliseconds.toString(),
        ).joinToString("\t")
    }

    private fun decodeReceipt(line: String): AppleRetryAdministrationReceipt {
        val fields = line.split('\t')
        require(fields.size == RECEIPT_FIELD_COUNT)
        val request = RetryAdministrationRequest(
            commandId = RetryAdministrationCommandId(appleQueueHexDecode(fields[0])),
            queueEntryId = QueueEntryId(appleQueueHexDecode(fields[1])),
            principalId = RetryAdministrationPrincipalId(appleQueueHexDecode(fields[2])),
            requestedAt = DataLoomInstant(fields[3].appleQueueToLongStrict()),
            action = RetryAdministrationAction.valueOf(fields[4]),
            reason = RetryAdministrationReason(appleQueueHexDecode(fields[5])),
            originalFailure = RetryFailureSnapshot(
                code = ErrorCode(appleQueueHexDecode(fields[6])),
                category = ErrorCategory.valueOf(fields[7]),
                severity = ErrorSeverity.valueOf(fields[8]),
                recoverability = Recoverability.valueOf(fields[9]),
            ),
        )
        val command = AuthorizedRetryAdministrationCommand(
            request = request,
            authorizationId = RetryAdministrationAuthorizationId(
                appleQueueHexDecode(fields[10]),
            ),
            effectiveRecoverability = Recoverability.valueOf(fields[11]),
        )
        return AppleRetryAdministrationReceipt(
            command = command,
            appliedAt = DataLoomInstant(fields[12].appleQueueToLongStrict()),
        )
    }

    private fun encodeEntry(
        entry: QueueEntry,
        includeStrategyDecision: Boolean,
        includeStrategyPlan: Boolean,
    ): String {
        val request = entry.synchronizationRequest
        val context = request.context
        val lease = entry.lease
        val error = entry.lastError
        val fields = mutableListOf(
            appleQueueHexEncode(entry.id.value),
            appleQueueHexEncode(request.workflowId.value),
            appleQueueHexEncode(request.sessionId.value),
            request.direction.name,
            request.mode.name,
            request.priority.name,
            appleQueueHexEncode(context.executionId.value),
            appleQueueHexEncode(context.correlationId.value),
            appleQueueEncodeNullableString(context.traceId?.value),
            appleQueueEncodeNullableString(context.requestId?.value),
            appleQueueEncodeNullableString(context.tenantId?.value),
            appleQueueEncodeNullableString(context.userId?.value),
            appleQueueEncodeNullableString(context.localeTag?.value),
            appleQueueEncodeNullableString(context.runtimeVersion?.value),
            appleQueueEncodeNullableString(context.configurationVersion?.value),
            appleQueueEncodeMetadata(context.metadata),
            entry.state.name,
            entry.enqueuedAt.epochMilliseconds.toString(),
            entry.availableAt.epochMilliseconds.toString(),
            appleQueueEncodeNullableInt(entry.retryAttempt?.number),
            appleQueueEncodeNullableLong(entry.retryBudgetState?.windowStartedAt?.epochMilliseconds),
            appleQueueEncodeNullableLong(entry.retryBudgetState?.lastEvaluatedAt?.epochMilliseconds),
            appleQueueEncodeNullableLong(entry.retryBudgetState?.cumulativeDelay?.milliseconds),
            appleQueueEncodeNullableLong(entry.workflowTimeoutState?.startedAt?.epochMilliseconds),
            appleQueueEncodeNullableLong(entry.workflowTimeoutState?.deadline?.epochMilliseconds),
            appleQueueEncodeNullableString(lease?.id?.value),
            appleQueueEncodeNullableString(lease?.consumerId?.value),
            appleQueueEncodeNullableLong(lease?.acquiredAt?.epochMilliseconds),
            appleQueueEncodeNullableLong(lease?.expiresAt?.epochMilliseconds),
            appleQueueEncodeNullableString(error?.code?.value),
            error?.category?.name ?: APPLE_QUEUE_NULL_MARKER,
            error?.severity?.name ?: APPLE_QUEUE_NULL_MARKER,
            error?.recoverability?.name ?: APPLE_QUEUE_NULL_MARKER,
            appleQueueEncodeNullableString(error?.message),
            appleQueueEncodeMetadata(entry.metadata),
        )
        if (includeStrategyDecision) {
            val decision = entry.strategyDecision
            fields += listOf(
                appleQueueEncodeNullableString(decision?.decisionId?.value),
                appleQueueEncodeNullableString(decision?.planId?.value),
                appleQueueEncodeNullableString(decision?.requestedStrategy?.name),
                appleQueueEncodeNullableString(decision?.effectiveProfileId?.value),
                appleQueueEncodeNullableString(decision?.effectiveStrategy?.name),
                appleQueueEncodeNullableLong(decision?.configurationVersion?.value),
                appleQueueEncodeNullableString(decision?.disposition?.name),
            )
        }
        if (includeStrategyPlan) {
            fields += appleQueueEncodeNullableString(
                entry.strategyPlan?.let(StrategyExecutionPlanCodec::encode),
            )
        }
        return fields.joinToString("\t")
    }

    private fun decodeEntry(
        line: String,
        includeStrategyDecision: Boolean,
        includeStrategyPlan: Boolean,
    ): QueueEntry {
        val fields = line.split('\t')
        val expectedFieldCount = when {
            includeStrategyPlan -> CURRENT_ENTRY_FIELD_COUNT
            includeStrategyDecision -> VERSION_THREE_ENTRY_FIELD_COUNT
            else -> LEGACY_ENTRY_FIELD_COUNT
        }
        require(fields.size == expectedFieldCount)
        val executionContext = ExecutionContext(
            executionId = ExecutionId(appleQueueHexDecode(fields[6])),
            correlationId = CorrelationId(appleQueueHexDecode(fields[7])),
            traceId = appleQueueDecodeNullableString(fields[8])?.let(::TraceId),
            requestId = appleQueueDecodeNullableString(fields[9])?.let(::RequestId),
            tenantId = appleQueueDecodeNullableString(fields[10])?.let(::TenantId),
            userId = appleQueueDecodeNullableString(fields[11])?.let(::UserId),
            localeTag = appleQueueDecodeNullableString(fields[12])?.let(::LocaleTag),
            runtimeVersion = appleQueueDecodeNullableString(fields[13])?.let(::RuntimeVersion),
            configurationVersion = appleQueueDecodeNullableString(fields[14])
                ?.let(::ConfigurationVersion),
            metadata = appleQueueDecodeMetadata(fields[15]),
        )
        val synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId(appleQueueHexDecode(fields[1])),
            sessionId = SynchronizationSessionId(appleQueueHexDecode(fields[2])),
            direction = SynchronizationDirection.valueOf(fields[3]),
            mode = SynchronizationMode.valueOf(fields[4]),
            priority = WorkflowPriority.valueOf(fields[5]),
            context = executionContext,
        )

        val retryAttempt = fields[19].appleQueueToNullableInt()?.let(::RetryAttempt)
        val retryBudgetColumns = listOf(fields[20], fields[21], fields[22])
            .map { it.appleQueueToNullableLong() }
        check(retryBudgetColumns.all { it == null } || retryBudgetColumns.all { it != null })
        val retryBudgetState = if (retryBudgetColumns.all { it == null }) {
            null
        } else {
            RetryBudgetState(
                windowStartedAt = DataLoomInstant(checkNotNull(retryBudgetColumns[0])),
                lastEvaluatedAt = DataLoomInstant(checkNotNull(retryBudgetColumns[1])),
                cumulativeDelay = SchedulingDelay(checkNotNull(retryBudgetColumns[2])),
            )
        }

        val workflowColumns = listOf(fields[23], fields[24])
            .map { it.appleQueueToNullableLong() }
        check(workflowColumns.all { it == null } || workflowColumns.all { it != null })
        val workflowTimeoutState = if (workflowColumns.all { it == null }) {
            null
        } else {
            WorkflowTimeoutState(
                startedAt = DataLoomInstant(checkNotNull(workflowColumns[0])),
                deadline = DataLoomInstant(checkNotNull(workflowColumns[1])),
            )
        }

        val leaseStrings = listOf(fields[25], fields[26]).map(::appleQueueDecodeNullableString)
        val leaseInstants = listOf(fields[27], fields[28]).map { it.appleQueueToNullableLong() }
        val leaseColumns = leaseStrings + leaseInstants
        check(leaseColumns.all { it == null } || leaseColumns.all { it != null })
        val lease = if (leaseColumns.all { it == null }) {
            null
        } else {
            QueueLease(
                id = QueueLeaseId(checkNotNull(leaseStrings[0])),
                consumerId = QueueConsumerId(checkNotNull(leaseStrings[1])),
                acquiredAt = DataLoomInstant(checkNotNull(leaseInstants[0])),
                expiresAt = DataLoomInstant(checkNotNull(leaseInstants[1])),
            )
        }

        val errorCode = appleQueueDecodeNullableString(fields[29])
        val errorCategory = if (fields[30] == APPLE_QUEUE_NULL_MARKER) null else fields[30]
        val errorSeverity = if (fields[31] == APPLE_QUEUE_NULL_MARKER) null else fields[31]
        val errorRecoverability = if (fields[32] == APPLE_QUEUE_NULL_MARKER) null else fields[32]
        val errorMessage = appleQueueDecodeNullableString(fields[33])
        val errorColumns = listOf(
            errorCode,
            errorCategory,
            errorSeverity,
            errorRecoverability,
            errorMessage,
        )
        check(errorColumns.all { it == null } || errorColumns.all { it != null })
        val lastError = if (errorColumns.all { it == null }) {
            null
        } else {
            ApplePersistedQueueError(
                code = ErrorCode(checkNotNull(errorCode)),
                category = ErrorCategory.valueOf(checkNotNull(errorCategory)),
                severity = ErrorSeverity.valueOf(checkNotNull(errorSeverity)),
                recoverability = Recoverability.valueOf(checkNotNull(errorRecoverability)),
                message = checkNotNull(errorMessage),
            )
        }

        val strategyDecision = if (!includeStrategyDecision) {
            null
        } else {
            val strategyColumns: List<Any?> = listOf(
                appleQueueDecodeNullableString(fields[35]),
                appleQueueDecodeNullableString(fields[36]),
                appleQueueDecodeNullableString(fields[37]),
                appleQueueDecodeNullableString(fields[38]),
                appleQueueDecodeNullableString(fields[39]),
                fields[40].appleQueueToNullableLong(),
                appleQueueDecodeNullableString(fields[41]),
            )
            check(strategyColumns.all { it == null } || strategyColumns.all { it != null })
            if (strategyColumns.all { it == null }) {
                null
            } else {
                PersistedStrategyDecision(
                    decisionId = StrategyDecisionId(checkNotNull(strategyColumns[0]) as String),
                    planId = StrategyPlanId(checkNotNull(strategyColumns[1]) as String),
                    requestedStrategy = BuiltInSynchronizationStrategy.valueOf(
                        checkNotNull(strategyColumns[2]) as String,
                    ),
                    effectiveProfileId = StrategyProfileId(
                        checkNotNull(strategyColumns[3]) as String,
                    ),
                    effectiveStrategy = BuiltInSynchronizationStrategy.valueOf(
                        checkNotNull(strategyColumns[4]) as String,
                    ),
                    configurationVersion = StrategyConfigurationVersion(
                        checkNotNull(strategyColumns[5]) as Long,
                    ),
                    disposition = StrategyDisposition.valueOf(
                        checkNotNull(strategyColumns[6]) as String,
                    ),
                )
            }
        }

        val strategyPlan = if (!includeStrategyPlan) {
            null
        } else {
            appleQueueDecodeNullableString(fields[42])
                ?.let(StrategyExecutionPlanCodec::decode)
        }

        return QueueEntry(
            id = QueueEntryId(appleQueueHexDecode(fields[0])),
            synchronizationRequest = synchronizationRequest,
            state = QueueEntryState.valueOf(fields[16]),
            enqueuedAt = DataLoomInstant(fields[17].appleQueueToLongStrict()),
            availableAt = DataLoomInstant(fields[18].appleQueueToLongStrict()),
            retryAttempt = retryAttempt,
            lease = lease,
            lastError = lastError,
            metadata = appleQueueDecodeMetadata(fields[34]),
            retryBudgetState = retryBudgetState,
            workflowTimeoutState = workflowTimeoutState,
            strategyDecision = strategyDecision,
            strategyPlan = strategyPlan,
        )
    }
}
