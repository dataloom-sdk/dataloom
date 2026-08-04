package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode

/**
 * Deterministic bounded V1 codec for an immutable [StrategyExecutionPlan].
 *
 * The format contains only plan identity, enum names, ordered operations,
 * capability names, and the finite fallback/continuation projections. It never
 * contains payloads, credentials, provider values, exception text, arbitrary
 * metadata, or runtime evidence beyond the accepted cache-state enum.
 */
public object StrategyExecutionPlanCodec {
    private const val HEADER: String = "DATALOOM_STRATEGY_PLAN"
    private const val VERSION: String = "1"
    private const val NULL: String = "-"
    private const val FIELD_COUNT: Int = 27
    private const val MAX_ENCODED_LENGTH: Int = 32_768

    /** Encodes one plan into the stable V1 text frame. */
    public fun encode(plan: StrategyExecutionPlan): String {
        val fallback = encodeFallback(plan.fallbackPlan)
        val continuation = plan.durableContinuation
        val continuationFallback = encodeFallback(continuation?.fallbackPlan)
        val fields = listOf(
            HEADER,
            VERSION,
            hexEncode(plan.id.value),
            plan.requestedStrategy.name,
            hexEncode(plan.effectiveProfileId.value),
            plan.effectiveStrategy.name,
            plan.configurationVersion.value.toString(),
            plan.direction.name,
            plan.mode.name,
            plan.disposition.name,
            encodeEnums(plan.operations),
            encodeEnums(plan.requiredCapabilities.sortedBy { it.name }),
            plan.dataOrigin.name,
            plan.consistency.name,
            plan.deferralReason?.name ?: NULL,
            plan.rejectionReason?.name ?: NULL,
            fallback.first,
            fallback.second,
            fallback.third,
            continuation?.let { encodeEnums(it.operations) } ?: NULL,
            continuation?.let {
                encodeEnums(it.requiredCapabilities.sortedBy { capability -> capability.name })
            } ?: NULL,
            continuation?.dataOrigin?.name ?: NULL,
            continuation?.consistency?.name ?: NULL,
            continuation?.evaluatedCacheState?.name ?: NULL,
            continuationFallback.first,
            continuationFallback.second,
            continuationFallback.third,
        )
        val encoded = fields.joinToString("|")
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded strategy execution plan exceeds the bounded V1 limit."
        }
        return encoded
    }

    /** Decodes one strict V1 frame or fails closed with a sanitized error. */
    public fun decode(encoded: String): StrategyExecutionPlan {
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded strategy execution plan exceeds the bounded V1 limit."
        }
        return try {
            val fields = encoded.split('|')
            require(fields.size == FIELD_COUNT)
            require(fields[0] == HEADER)
            require(fields[1] == VERSION)
            val fallback = decodeFallback(fields[16], fields[17], fields[18])
            val continuation = decodeContinuation(
                operations = fields[19],
                capabilities = fields[20],
                dataOrigin = fields[21],
                consistency = fields[22],
                cacheState = fields[23],
                fallbackOutcomes = fields[24],
                fallbackOperations = fields[25],
                fallbackOrigin = fields[26],
            )
            StrategyExecutionPlan(
                id = StrategyPlanId(hexDecode(fields[2])),
                requestedStrategy = enumValueOf(fields[3]),
                effectiveProfileId = StrategyProfileId(hexDecode(fields[4])),
                effectiveStrategy = enumValueOf(fields[5]),
                configurationVersion = StrategyConfigurationVersion(fields[6].toLong()),
                direction = enumValueOf<SynchronizationDirection>(fields[7]),
                mode = enumValueOf<SynchronizationMode>(fields[8]),
                disposition = enumValueOf(fields[9]),
                operations = decodeEnums(fields[10]),
                requiredCapabilities = decodeEnums<StrategyProviderCapability>(fields[11]).toSet(),
                dataOrigin = enumValueOf(fields[12]),
                consistency = enumValueOf(fields[13]),
                deferralReason = decodeNullableEnum(fields[14]),
                rejectionReason = decodeNullableEnum(fields[15]),
                fallbackPlan = fallback,
                durableContinuation = continuation,
            )
        } catch (_: Exception) {
            throw IllegalArgumentException("Malformed strategy execution plan frame.")
        }
    }

    private fun encodeFallback(
        fallback: StrategyFallbackPlan?,
    ): Triple<String, String, String> = if (fallback == null) {
        Triple(NULL, NULL, NULL)
    } else {
        Triple(
            encodeEnums(fallback.remoteOutcomes.sortedBy { it.name }),
            encodeEnums(fallback.operations),
            fallback.dataOrigin.name,
        )
    }

    private fun decodeFallback(
        outcomes: String,
        operations: String,
        origin: String,
    ): StrategyFallbackPlan? {
        val values = listOf(outcomes, operations, origin)
        require(values.all { it == NULL } || values.none { it == NULL })
        if (values.all { it == NULL }) return null
        return StrategyFallbackPlan(
            remoteOutcomes = decodeEnums<StrategyRemoteOutcome>(outcomes).toSet(),
            operations = decodeEnums(operations),
            dataOrigin = enumValueOf(origin),
        )
    }

    private fun decodeContinuation(
        operations: String,
        capabilities: String,
        dataOrigin: String,
        consistency: String,
        cacheState: String,
        fallbackOutcomes: String,
        fallbackOperations: String,
        fallbackOrigin: String,
    ): StrategyDurableContinuationPlan? {
        val required = listOf(operations, capabilities, dataOrigin, consistency)
        val absent = required.all { it == NULL }
        require(absent || required.none { it == NULL })
        if (absent) {
            require(cacheState == NULL)
            require(listOf(fallbackOutcomes, fallbackOperations, fallbackOrigin).all { it == NULL })
            return null
        }
        return StrategyDurableContinuationPlan(
            operations = decodeEnums(operations),
            requiredCapabilities =
                decodeEnums<StrategyProviderCapability>(capabilities).toSet(),
            dataOrigin = enumValueOf(dataOrigin),
            consistency = enumValueOf(consistency),
            evaluatedCacheState = decodeNullableEnum(cacheState),
            fallbackPlan = decodeFallback(
                fallbackOutcomes,
                fallbackOperations,
                fallbackOrigin,
            ),
        )
    }

    private fun <T : Enum<T>> encodeEnums(values: Collection<T>): String =
        values.joinToString(",") { it.name }

    private inline fun <reified T : Enum<T>> decodeEnums(value: String): List<T> =
        if (value.isEmpty()) emptyList() else value.split(',').map(::enumValueOf)

    private inline fun <reified T : Enum<T>> decodeNullableEnum(value: String): T? =
        if (value == NULL) null else enumValueOf(value)

    private fun hexEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }

    private fun hexDecode(value: String): String {
        require(value.length % 2 == 0)
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = value[index * 2].hexValue()
            val low = value[(index * 2) + 1].hexValue()
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        else -> throw IllegalArgumentException("Malformed strategy execution plan frame.")
    }

    private const val HEX: String = "0123456789abcdef"
}
