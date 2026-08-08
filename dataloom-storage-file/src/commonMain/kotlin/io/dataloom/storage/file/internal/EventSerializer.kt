package io.dataloom.storage.file.internal

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.payload.PayloadContentType
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Serialization and deserialization helpers for [ChangeEvent] to and from
 * the `DATALOOM_EVT_V1` text format.
 *
 * Format overview:
 * ```
 * DATALOOM_EVT_V1
 * changeSetId=<changeSetId>
 * eventId=<eventId>
 * entityType=<type>
 * entityId=<id>
 * entityVersion=<version or empty>
 * operation=<OPERATION>
 * payloadContentType=<contentType or empty>
 * payloadBase64=<base64 encoded bytes or empty>
 * meta.<key>=<value>      (zero or more; key may not contain '=')
 * ```
 *
 * All values are on a single line. The payload is base64-encoded so the entire
 * record is plain ASCII. Metadata entries are stored as `meta.<key>=<value>`
 * lines; metadata keys must not contain `=` or newlines.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object EventSerializer {

    private const val HEADER = "DATALOOM_EVT_V1"
    private const val KEY_CHANGE_SET_ID = "changeSetId"
    private const val KEY_EVENT_ID = "eventId"
    private const val KEY_ENTITY_TYPE = "entityType"
    private const val KEY_ENTITY_ID = "entityId"
    private const val KEY_ENTITY_VERSION = "entityVersion"
    private const val KEY_OPERATION = "operation"
    private const val KEY_PAYLOAD_CONTENT_TYPE = "payloadContentType"
    private const val KEY_PAYLOAD_BASE64 = "payloadBase64"
    private const val META_PREFIX = "meta."

    /**
     * Serializes a [ChangeEvent] together with its [changeSetId] into a
     * multi-line text record.
     */
    fun serialize(changeSetId: String, event: ChangeEvent): String = buildString {
        appendLine(HEADER)
        appendLine("$KEY_CHANGE_SET_ID=${changeSetId}")
        appendLine("$KEY_EVENT_ID=${event.id.value}")
        appendLine("$KEY_ENTITY_TYPE=${event.entity.type.value}")
        appendLine("$KEY_ENTITY_ID=${event.entity.id.value}")
        appendLine("$KEY_ENTITY_VERSION=${event.entity.version?.value.orEmpty()}")
        appendLine("$KEY_OPERATION=${event.operation.name}")
        val payload = event.payload
        if (payload != null) {
            appendLine("$KEY_PAYLOAD_CONTENT_TYPE=${payload.contentType.value}")
            appendLine("$KEY_PAYLOAD_BASE64=${Base64.encode(payload.copyBytes())}")
        } else {
            appendLine("$KEY_PAYLOAD_CONTENT_TYPE=")
            appendLine("$KEY_PAYLOAD_BASE64=")
        }
        for ((k, v) in event.metadata.entries) {
            requireNoLineBreaks(k, "metadata key")
            requireNoLineBreaks(v, "metadata value")
            appendLine("$META_PREFIX$k=$v")
        }
    }

    /**
     * Deserializes a [ChangeEvent] from a text record produced by [serialize].
     *
     * Returns `null` when the record is malformed or the header is unrecognized.
     */
    fun deserialize(text: String): Pair<String, ChangeEvent>? {
        val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || lines[0] != HEADER) return null

        val props = mutableMapOf<String, String>()
        val metaEntries = mutableMapOf<String, String>()

        for (line in lines.drop(1)) {
            val eqIndex = line.indexOf('=')
            if (eqIndex < 0) continue
            val key = line.substring(0, eqIndex)
            val value = line.substring(eqIndex + 1)
            if (key.startsWith(META_PREFIX)) {
                metaEntries[key.removePrefix(META_PREFIX)] = value
            } else {
                props[key] = value
            }
        }

        val changeSetId = props[KEY_CHANGE_SET_ID] ?: return null
        val eventId = props[KEY_EVENT_ID]?.takeIf { it.isNotBlank() } ?: return null
        val entityType = props[KEY_ENTITY_TYPE]?.takeIf { it.isNotBlank() } ?: return null
        val entityId = props[KEY_ENTITY_ID]?.takeIf { it.isNotBlank() } ?: return null
        val entityVersionRaw = props[KEY_ENTITY_VERSION].orEmpty()
        val operationName = props[KEY_OPERATION]?.takeIf { it.isNotBlank() } ?: return null
        val operation = ChangeOperation.entries.firstOrNull { it.name == operationName } ?: return null

        val contentTypeRaw = props[KEY_PAYLOAD_CONTENT_TYPE].orEmpty()
        val payloadBase64Raw = props[KEY_PAYLOAD_BASE64].orEmpty()
        val payload: DataLoomPayload? = if (contentTypeRaw.isNotBlank() && payloadBase64Raw.isNotBlank()) {
            DataLoomPayload(
                contentType = PayloadContentType(contentTypeRaw),
                bytes = Base64.decode(payloadBase64Raw),
            )
        } else {
            null
        }

        val metadata = if (metaEntries.isEmpty()) {
            DataLoomMetadata.Empty
        } else {
            DataLoomMetadata.of(metaEntries)
        }

        val event = ChangeEvent(
            id = ChangeEventId(eventId),
            entity = EntityReference(
                type = EntityType(entityType),
                id = EntityId(entityId),
                version = if (entityVersionRaw.isNotBlank()) EntityVersion(entityVersionRaw) else null,
            ),
            operation = operation,
            payload = payload,
            metadata = metadata,
        )
        return changeSetId to event
    }

    private fun requireNoLineBreaks(value: String, fieldDescription: String) {
        require(value.none { it == '\n' || it == '\r' }) {
            "FileStorageProvider: $fieldDescription must not contain line-break characters."
        }
    }
}
