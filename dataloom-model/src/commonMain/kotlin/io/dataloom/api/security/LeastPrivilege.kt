package io.dataloom.api.security

import kotlin.jvm.JvmInline

/**
 * Stable label for one grantable capability or permission, for example
 * `storage.read` or `network.push`.
 *
 * [Capability] is deliberately just a comparable identity — it carries no
 * grant, no scope, and no expiry. Whether one is held, by whom, and under
 * what conditions is decided by [GrantedCapabilities] and its callers, never
 * by this type itself. Values are validated as non-blank and preserved
 * exactly as supplied, the same identity-only shape already used by this
 * module's other stable labels (for example [KeyReference]).
 */
@JvmInline
public value class Capability(
    /** Underlying capability label. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "Capability must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Immutable, deny-by-default set of capabilities held by one actor.
 *
 * There is no implicit grant and no wildcard: an actor holds exactly the
 * capabilities passed to [GrantedCapabilities.of] and nothing else. This is
 * the shared least-privilege primitive `#93` requires — one reusable "does
 * this actor hold X" check that plugin permission enforcement (`#98`),
 * administrative overrides, provider capability restriction, or any other
 * subsystem needing capability-gated access can build on, instead of each
 * subsystem re-implementing its own ad-hoc allow-list check. Establishing
 * this primitive does not itself grant, revoke, persist, or distribute
 * capabilities — that is deliberately left to each subsystem's own runtime,
 * the same contract-versus-engine split
 * [io.dataloom.api.plugin.PluginManifest] already documents for plugin
 * permissions.
 *
 * Bounded at [MAX_CAPABILITIES] entries and defensively copied — the same
 * bounded-cardinality discipline `PluginManifest`/`PolicySet` already apply,
 * and a direct instance of `#93`'s own "no ... unbounded-cardinality
 * values" acceptance criterion.
 */
public class GrantedCapabilities private constructor(
    private val values: Set<Capability>,
) {
    /** Number of distinct capabilities held. */
    public val size: Int
        get() = values.size

    /** `true` when this grant holds no capabilities at all. */
    public fun isEmpty(): Boolean = values.isEmpty()

    /** `true` only when [capability] is explicitly present in this grant. */
    public fun holds(capability: Capability): Boolean = values.contains(capability)

    /** `true` only when every entry of [capabilities] is explicitly present in this grant. */
    public fun holdsAll(capabilities: Set<Capability>): Boolean = values.containsAll(capabilities)

    override fun equals(other: Any?): Boolean =
        this === other || other is GrantedCapabilities && values == other.values

    override fun hashCode(): Int = values.hashCode()

    /** Never renders individual capability labels — a count only. */
    override fun toString(): String = "GrantedCapabilities(size=${values.size})"

    public companion object {
        /** The empty grant — holds nothing, authorizes nothing but an empty request. */
        public val None: GrantedCapabilities = GrantedCapabilities(emptySet())

        /**
         * Creates a grant holding exactly [capabilities].
         *
         * @throws IllegalArgumentException if [capabilities] exceeds [MAX_CAPABILITIES] entries.
         */
        public fun of(capabilities: Set<Capability>): GrantedCapabilities {
            if (capabilities.isEmpty()) {
                return None
            }
            require(capabilities.size <= MAX_CAPABILITIES) {
                "GrantedCapabilities must not exceed $MAX_CAPABILITIES entries."
            }
            return GrantedCapabilities(capabilities.toSet())
        }

        private const val MAX_CAPABILITIES: Int = 64
    }
}

/**
 * Central deny-by-default least-privilege check.
 *
 * [requested] is authorized only when every requested capability is present
 * in [granted] — there is no default-allow path and no partial match on a
 * non-empty request. An empty [requested] set is trivially authorized:
 * asking for nothing cannot be denied.
 */
public fun isAuthorized(requested: Set<Capability>, granted: GrantedCapabilities): Boolean =
    granted.holdsAll(requested)
