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
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant

/** Strict, deterministic queue snapshot codec used by the Apple provider. */
internal object AppleQueueStateFileCodec {
    private const val HEADER: String = "DATALOOM_QUEUE_STATE\t1"
    private const val FIELD_COUNT: Int = 35

    fun encode(entries: Map<String, QueueEntry>): String {
        if (entries.size > APPLE_QUEUE_MAX_ENTRY_COUNT) {
            throw AppleQueueEntryLimitException()
        }
        val content = buildString {
            append(HEADER)
            append('\n')
            entries.entries.sortedBy { it.key }.forEach { mapEntry ->
                val id = mapEntry.key
                val entry = mapEntry.value
                check(id == entry.id.value) {
                    "Queue snapshot map key does not match the entry identifier."
                }
                append(encodeEntry(entry))
                append('\n')
            }
        }
        if (content.encodeToByteArray().size > APPLE_QUEUE_MAX_STATE_FILE_BYTES) {
            throw AppleQueueFileLimitException()
        }
        return content
    }

    fun decode(content: String): MutableMap<String, QueueEntry> {
        if (content.encodeToByteArray().size > APPLE_QUEUE_MAX_STATE_FILE_BYTES) {
            throw AppleQueueFileLimitException()
        }
        return try {
            val lines = content.split('\n')
            require(lines.isNotEmpty() && lines.first() == HEADER)
            val entries = linkedMapOf<String, QueueEntry>()
            for (index in 1 until lines.size) {
                val line = lines[index]
                if (line.isEmpty()) {
                    require(index == lines.lastIndex)
                    continue
                }
                if (entries.size >= APPLE_QUEUE_MAX_ENTRY_COUNT) {
                    throw AppleQueueEntryLimitException()
                }
                val entry = decodeEntry(line)
                require(entries.put(entry.id.value, entry) == null)
            }
            entries
        } catch (limit: AppleQueueFileLimitException) {
            throw limit
        } catch (limit: AppleQueueEntryLimitException) {
            throw limit
        } catch (invalid: Exception) {
            throw AppleQueueMalformedStateException(invalid)
        }
    }

    private fun encodeEntry(entry: QueueEntry): String {
        val request = entry.synchronizationRequest
        val context = request.context
        val lease = entry.lease
        val error = entry.lastError
        return listOf(
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
        ).joinToString("\t")
    }

    private fun decodeEntry(line: String): QueueEntry {
        val fields = line.split('\t')
        require(fields.size == FIELD_COUNT)
        val executionContext = ExecutionContext(
            executionId = ExecutionId(appleQueueHexDecode(fields[6])),
            correlationId = CorrelationId(appleQueueHexDecode(fields[7])),
            traceId = appleQueueDecodeNullableString(fields[8])?.let(::TraceId),
            requestId = appleQueueDecodeNullableString(fields[9])?.let(::RequestId),
            tenantId = appleQueueDecodeNullableString(fields[10])?.let(::TenantId),
            userId = appleQueueDecodeNullableString(fields[11])?.let(::UserId),
            localeTag = appleQueueDecodeNullableString(fields[12])?.let(::LocaleTag),
            runtimeVersion = appleQueueDecodeNullableString(fields[13])?.let(::RuntimeVersion),
            configurationVersion = appleQueueDecodeNullableString(fields[14])?.let(::ConfigurationVersion),
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

        val workflowColumns = listOf(fields[23], fields[24]).map { it.appleQueueToNullableLong() }
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
        )
    }
}

