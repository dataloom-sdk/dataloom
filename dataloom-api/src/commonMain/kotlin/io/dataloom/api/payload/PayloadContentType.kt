package io.dataloom.api.payload

import kotlin.jvm.JvmInline

/**
 * Immutable value type representing the content type of a [DataLoomPayload].
 *
 * The value is an opaque string identifier supplied by the payload producer.
 * DataLoom does not parse, validate, or normalize the content-type string.
 * Host applications and configured serializer providers are responsible for
 * defining and interpreting content-type values.
 *
 * Example values may include `application/json`, `application/octet-stream`,
 * `image/jpeg`, or any application-defined type label. These are examples only.
 * Complete MIME-type parsing or validation is not performed.
 *
 * Ownership: payload producer.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or locale-sensitive behavior is applied.
 * - `toString()` returns the underlying value.
 */
@JvmInline
public value class PayloadContentType(
    /** Underlying content-type string value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PayloadContentType must not be blank." }
    }

    override fun toString(): String = value
}
