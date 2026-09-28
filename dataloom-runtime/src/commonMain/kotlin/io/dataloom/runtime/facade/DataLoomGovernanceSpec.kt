package io.dataloom.runtime.facade

import io.dataloom.api.security.DataLoomHmacCalculator
import io.dataloom.governance.audit.AuditStore
import io.dataloom.governance.rbac.RbacPolicy

/**
 * Immutable configuration for the optional governance capability
 * (`dataloom-governance`, ADR-0005): RBAC evaluation, tamper-evident audit
 * logging, and signed-policy-pack verification.
 *
 * Every piece is independently optional -- a host configures only the
 * capabilities it needs. Supplying none of [rbacPolicy], [auditStore], or
 * [hmacCalculator] is rejected at construction (there would be nothing for
 * [DataLoomBuilder.governanceConfiguration] to enable); supplying this spec
 * at all is itself optional, and omitting [DataLoomBuilder.governanceConfiguration]
 * entirely leaves [DataLoom.governance] `null` and behavior unchanged.
 *
 * ## RBAC
 *
 * When [rbacPolicy] is supplied, [DataLoomGovernance.rbacEvaluator] is a
 * non-null [io.dataloom.governance.rbac.RbacEvaluator] over it. Evaluation is
 * pure and synchronous (see [io.dataloom.governance.rbac.RbacEvaluator]); no
 * I/O occurs at build time or evaluation time.
 *
 * ## Audit
 *
 * When [auditStore] is supplied, [auditKey] must be supplied too (and vice
 * versa), and [hmacCalculator] is required. [DataLoomGovernance.auditLog] is
 * then a non-null [io.dataloom.governance.audit.AuditLog] over [auditStore],
 * using the runtime clock from [DataLoomBuilder.runtimeDependencies] --
 * exactly as every other durable-log-backed capability in this builder
 * sources its clock. [auditStore] is a plain [AuditStore] port: an
 * [io.dataloom.governance.audit.InMemoryAuditStore] today, or any durable
 * implementation a later slice adds, without this wiring changing.
 *
 * ## Signed policy packs (D10)
 *
 * When [hmacCalculator] is supplied, [DataLoomGovernance.policyPackVerifier]
 * is a non-null [io.dataloom.governance.policy.PolicyPackVerifier]. Signing
 * keys are never held by this spec or by [DataLoomBuilder] -- a caller
 * supplies a [io.dataloom.governance.policy.PolicyPackKeyResolver] to each
 * [io.dataloom.governance.policy.PolicyPackVerifier.verify] call.
 *
 * @param rbacPolicy the closed RBAC model to evaluate access requests
 *   against, or `null` to leave RBAC evaluation disabled.
 * @param auditStore the append-only store backing the audit log, or `null`
 *   to leave audit logging disabled. Must be supplied together with
 *   [auditKey].
 * @param auditKey the host-owned HMAC key for the audit chain. Defensively
 *   copied; never rendered or logged. Must be supplied together with
 *   [auditStore].
 * @param hmacCalculator the HMAC-SHA256 implementation used for both audit
 *   logging (when [auditStore] is supplied) and policy-pack verification.
 *   Required whenever either of those is enabled.
 */
public class DataLoomGovernanceSpec(
    public val rbacPolicy: RbacPolicy? = null,
    public val auditStore: AuditStore? = null,
    auditKey: ByteArray? = null,
    public val hmacCalculator: DataLoomHmacCalculator? = null,
) {
    /**
     * Defensive copy of the supplied audit key, or `null` when [auditStore] is not configured.
     * Module-internal on purpose: only [DataLoomBuilder] reads it, and a public getter would
     * hand callers the secret array itself.
     */
    internal val auditKey: ByteArray? = auditKey?.copyOf()

    init {
        require((auditStore == null) == (this.auditKey == null)) {
            "DataLoomGovernanceSpec.auditStore and .auditKey must both be supplied, or neither."
        }
        require(auditStore == null || hmacCalculator != null) {
            "DataLoomGovernanceSpec.hmacCalculator is required when auditStore is supplied."
        }
        require(rbacPolicy != null || auditStore != null || hmacCalculator != null) {
            "DataLoomGovernanceSpec must configure at least one governance capability: " +
                "rbacPolicy, auditStore (with auditKey), or hmacCalculator (for policy-pack verification)."
        }
    }

    /** Avoids rendering key material or policy content in diagnostics. */
    override fun toString(): String =
        "DataLoomGovernanceSpec(hasRbacPolicy=${rbacPolicy != null}, hasAudit=${auditStore != null}, " +
            "hasPolicyPackVerification=${hmacCalculator != null})"
}
