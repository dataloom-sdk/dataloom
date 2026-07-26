package io.dataloom.api.payload

import kotlin.jvm.JvmInline

/**
 * Immutable value type representing an application-defined version of an entity.
 *
 * The version is an opaque string supplied by the host application or a remote
 * system. It may represent a revision number, ETag, sequence counter, content
 * hash, or any other version identifier meaningful to the host application.
 *
 * DataLoom treats this value as opaque and does not interpret, compare, or
 * order version semantics. Version ordering and conflict detection are the
 * responsibility of the host application or a configured conflict-resolution
 * provider.
 *
 * Ownership: host application or remote system.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization, numeric parsing, or locale-sensitive behavior is applied.
 * - No automatic version generation is performed.
 * - `toString()` returns the underlying value.
 */
@JvmInline
public value class EntityVersion(
    /** Underlying version string value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "EntityVersion must not be blank." }
    }

    override fun toString(): String = value
}
