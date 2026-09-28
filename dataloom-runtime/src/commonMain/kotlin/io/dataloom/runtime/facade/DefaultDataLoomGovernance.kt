package io.dataloom.runtime.facade

import io.dataloom.governance.audit.AuditLog
import io.dataloom.governance.policy.PolicyPackVerifier
import io.dataloom.governance.rbac.RbacEvaluator

/**
 * Immutable holder assembled by [DataLoomBuilder] from [DataLoomGovernanceSpec].
 *
 * Purely a bag of already-constructed, independently optional collaborators;
 * it delegates nothing and adds no behavior of its own.
 */
internal class DefaultDataLoomGovernance(
    override val rbacEvaluator: RbacEvaluator?,
    override val auditLog: AuditLog?,
    override val policyPackVerifier: PolicyPackVerifier?,
) : DataLoomGovernance
