package io.dataloom.governance.rbac

/**
 * Immutable, validated RBAC configuration: the closed set of [Role]s and the
 * [RoleBinding]s that attach them to principals.
 *
 * Validation is fail-fast at construction so a misconfigured policy can never
 * be evaluated:
 * - role ids are unique (two different definitions of one id are a conflict,
 *   not something to merge);
 * - every binding references a defined role (a dangling binding is a
 *   configuration error, not an implicit "no permissions");
 * - identical duplicate bindings are collapsed;
 * - the policy is bounded by [MAXIMUM_ROLES] and [MAXIMUM_BINDINGS].
 *
 * An empty policy is valid and denies everything.
 */
public class RbacPolicy(
    roles: Collection<Role>,
    bindings: Collection<RoleBinding>,
) {
    /** Roles in the order supplied. */
    public val roles: List<Role> = roles.toList()

    /** Distinct bindings in the order first supplied. */
    public val bindings: List<RoleBinding> = bindings.distinct()

    private val rolesByPrincipal: Map<Principal, List<Role>>

    init {
        require(this.roles.size <= MAXIMUM_ROLES) { "RbacPolicy must not define more than $MAXIMUM_ROLES roles." }
        require(this.bindings.size <= MAXIMUM_BINDINGS) {
            "RbacPolicy must not contain more than $MAXIMUM_BINDINGS bindings."
        }

        val byId = LinkedHashMap<RoleId, Role>()
        for (role in this.roles) {
            require(byId.put(role.id, role) == null) { "RbacPolicy defines role ${role.id} more than once." }
        }

        val byPrincipal = LinkedHashMap<Principal, MutableList<Role>>()
        for (binding in this.bindings) {
            val role = requireNotNull(byId[binding.roleId]) {
                "RbacPolicy binding for principal ${binding.principalId} references undefined role ${binding.roleId}."
            }
            byPrincipal.getOrPut(Principal(binding.principalId, binding.tenantId)) { mutableListOf() }.add(role)
        }
        rolesByPrincipal = byPrincipal.mapValues { (_, bound) -> bound.sortedBy { it.id.value } }
    }

    /**
     * Roles bound to exactly [principal] (same id and same tenant), ordered by
     * role id for deterministic evidence. Empty when the principal has no
     * binding.
     */
    internal fun rolesBoundTo(principal: Principal): List<Role> = rolesByPrincipal[principal].orEmpty()

    override fun toString(): String = "RbacPolicy(roleCount=${roles.size}, bindingCount=${bindings.size})"

    public companion object {
        /** Upper bound on the number of roles in one policy. */
        public const val MAXIMUM_ROLES: Int = 1_000

        /** Upper bound on the number of bindings in one policy. */
        public const val MAXIMUM_BINDINGS: Int = 100_000
    }
}
