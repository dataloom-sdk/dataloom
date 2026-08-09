package io.dataloom.api.configuration

import io.dataloom.api.security.KeyReference

/**
 * Closed set of value shapes a [ConfigurationValue] can hold.
 *
 * Closed rather than open because [DataLoomConfigurationResolver] type-checks
 * every source entry against its [ConfigurationEntrySchema]-declared type
 * before admission — an arbitrary open type would defeat that check and
 * defer a real failure from resolution time to point-of-use.
 */
public enum class ConfigurationValueType {
    STRING,
    LONG,
    DOUBLE,
    BOOLEAN,

    /**
     * A reference to secret material the value names but does not carry.
     * See [ConfigurationValue.SecretReferenceValue].
     */
    SECRET_REFERENCE,
}

/**
 * One typed configuration value.
 *
 * ## Secret references, never secret values
 *
 * There is deliberately no [ConfigurationValue] variant that carries a raw
 * secret (a password, API key, or token) directly. A configuration entry
 * that names secret material must use [SecretReferenceValue], which wraps a
 * [KeyReference] — an opaque label the host application resolves through its
 * own key/secret store. DataLoom never holds, logs, or exports the
 * underlying secret through this type. This is the same reference-not-value
 * pattern [KeyReference] already established for HMAC key material in
 * `io.dataloom.api.security`.
 */
public sealed class ConfigurationValue {

    /** The [ConfigurationValueType] this value's shape corresponds to. */
    public abstract val type: ConfigurationValueType

    public data class StringValue(public val value: String) : ConfigurationValue() {
        override val type: ConfigurationValueType get() = ConfigurationValueType.STRING
    }

    public data class LongValue(public val value: Long) : ConfigurationValue() {
        override val type: ConfigurationValueType get() = ConfigurationValueType.LONG
    }

    public data class DoubleValue(public val value: Double) : ConfigurationValue() {
        override val type: ConfigurationValueType get() = ConfigurationValueType.DOUBLE
    }

    public data class BooleanValue(public val value: Boolean) : ConfigurationValue() {
        override val type: ConfigurationValueType get() = ConfigurationValueType.BOOLEAN
    }

    /**
     * A reference to secret material, never the material itself.
     *
     * @param reference opaque label the host application resolves through
     *   its own key/secret store (see [KeyReference]).
     */
    public data class SecretReferenceValue(public val reference: KeyReference) : ConfigurationValue() {
        override val type: ConfigurationValueType get() = ConfigurationValueType.SECRET_REFERENCE
    }
}
