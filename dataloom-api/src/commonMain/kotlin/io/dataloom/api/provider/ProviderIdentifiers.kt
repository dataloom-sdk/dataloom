package io.dataloom.api.provider

/**
 * Stable machine-readable provider identifier.
 *
 * Values are validated as non-blank and preserved exactly as supplied.
 */
@JvmInline
public value class ProviderId(
    /** Underlying provider identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ProviderId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Stable human-readable provider name.
 *
 * Values are validated as non-blank and preserved exactly as supplied.
 */
@JvmInline
public value class ProviderName(
    /** Underlying provider name value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ProviderName must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Stable provider version label.
 *
 * Values are validated as non-blank and preserved exactly as supplied.
 */
@JvmInline
public value class ProviderVersion(
    /** Underlying provider version value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ProviderVersion must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Stable provider capability label.
 *
 * Values are validated as non-blank and preserved exactly as supplied.
 */
@JvmInline
public value class ProviderCapability(
    /** Underlying provider capability value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ProviderCapability must not be blank." }
    }

    override fun toString(): String = value
}
