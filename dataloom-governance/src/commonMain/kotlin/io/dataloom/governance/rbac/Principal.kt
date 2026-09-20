package io.dataloom.governance.rbac

import io.dataloom.api.identifier.TenantId
import io.dataloom.governance.MAXIMUM_PRINCIPAL_ID_LENGTH
import kotlin.jvm.JvmInline

/**
 * Identifier of an authenticated actor (a human user, a service account, a
 * device) as asserted by the host's authentication layer.
 *
 * DataLoom does not authenticate; it authorizes an already-authenticated
 * [Principal]. This is the cross-subsystem principal identifier for
 * governance. The per-subsystem administration principal ids
 * (`RetryAdministrationPrincipalId`, `CircuitAdministrationPrincipalId`,
 * `ConflictAdministrationPrincipalId`) are unchanged; a later wiring slice maps
 * them onto this type.
 *
 * Constraints: non-blank, at most 256 characters, no surrounding whitespace,
 * no control characters. Valid input is preserved exactly as supplied.
 */
@JvmInline
public value class PrincipalId(
    /** Underlying principal identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PrincipalId must not be blank." }
        require(value.length <= MAXIMUM_PRINCIPAL_ID_LENGTH) {
            "PrincipalId must not exceed $MAXIMUM_PRINCIPAL_ID_LENGTH characters."
        }
        require(value == value.trim()) { "PrincipalId must not have leading or trailing whitespace." }
        require(value.none { it.isISOControl() }) { "PrincipalId must not contain control characters." }
    }

    override fun toString(): String = value
}

/**
 * An authenticated actor together with the one tenant it belongs to.
 *
 * A principal belongs to exactly one tenant. The same [PrincipalId] under two
 * different [TenantId]s is two distinct principals: role bindings and
 * authorization never cross tenants, and there are no wildcard tenants.
 */
public data class Principal(
    public val id: PrincipalId,
    public val tenantId: TenantId,
)
