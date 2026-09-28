package io.dataloom.runtime.facade

import io.dataloom.governance.audit.AuditLog
import io.dataloom.governance.policy.PolicyPackVerifier
import io.dataloom.governance.rbac.RbacEvaluator

/**
 * Public operations capability over the configured governance pieces
 * (`dataloom-governance`, ADR-0005): RBAC evaluation, tamper-evident audit
 * logging, and signed-policy-pack verification.
 *
 * Each property is independently nullable, mirroring which pieces
 * [DataLoomGovernanceSpec] configured -- absence of one piece does not affect
 * the others. Every non-null property is the engine's own type, returned
 * unchanged; this facade adds no translation layer.
 */
public interface DataLoomGovernance {

    /**
     * The configured RBAC evaluator, or `null` unless
     * [DataLoomGovernanceSpec.rbacPolicy] was supplied.
     */
    public val rbacEvaluator: RbacEvaluator?

    /**
     * The configured tamper-evident audit log, or `null` unless
     * [DataLoomGovernanceSpec.auditStore] (with `auditKey`) was supplied.
     */
    public val auditLog: AuditLog?

    /**
     * The configured signed-policy-pack verifier, or `null` unless
     * [DataLoomGovernanceSpec.hmacCalculator] was supplied.
     */
    public val policyPackVerifier: PolicyPackVerifier?
}
