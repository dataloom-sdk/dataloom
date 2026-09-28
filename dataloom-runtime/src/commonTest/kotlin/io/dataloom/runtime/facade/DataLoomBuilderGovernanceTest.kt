package io.dataloom.runtime.facade

import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.policy.PolicyCheckOutcome
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.security.DataLoomHmacCalculator
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.security.KeyReference
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.governance.audit.AuditEvent
import io.dataloom.governance.audit.AuditEventType
import io.dataloom.governance.audit.AuditRecord
import io.dataloom.governance.audit.AuditStore
import io.dataloom.governance.audit.InMemoryAuditStore
import io.dataloom.governance.policy.PolicyPackKeyResolver
import io.dataloom.governance.policy.PolicyPackManifest
import io.dataloom.governance.policy.PolicyPackVerificationResult
import io.dataloom.governance.policy.SignedPolicyPack
import io.dataloom.governance.rbac.AccessRequest
import io.dataloom.governance.rbac.Action
import io.dataloom.governance.rbac.Permission
import io.dataloom.governance.rbac.Principal
import io.dataloom.governance.rbac.PrincipalId
import io.dataloom.governance.rbac.RbacPolicy
import io.dataloom.governance.rbac.ResourceRef
import io.dataloom.governance.rbac.ResourceType
import io.dataloom.governance.rbac.Role
import io.dataloom.governance.rbac.RoleBinding
import io.dataloom.governance.rbac.RoleId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomGovernanceSpec]/`governanceConfiguration` wires
 * `dataloom-governance` into [DataLoom] as an opt-in capability: absent when
 * not configured (and then inert), each of its three pieces independently
 * present only when its own configuration is supplied, performing no I/O
 * during [DataLoomBuilder.build], and working end to end when used.
 */
class DataLoomBuilderGovernanceTest {

    // -- absence is inert ------------------------------------------------------

    @Test
    fun governanceIsNullWhenNotConfigured() {
        assertNull(builder().build().governance)
    }

    @Test
    fun omittingGovernanceConfigurationDoesNotChangeProviderLifecycleBehavior() = runTest {
        val without = builder().build()
        val with = builder().governanceConfiguration(DataLoomGovernanceSpec(rbacPolicy = policy())).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(without.initialize())
        assertIs<ProviderLifecycleResult.InitializeSuccess>(with.initialize())
        assertNull(without.queueWorker)
        assertNull(with.queueWorker)
        assertNull(without.governance)
        assertNotNull(with.governance)
    }

    // -- each piece is independently optional -----------------------------------

    @Test
    fun onlyRbacConfiguredLeavesAuditAndPolicyPackVerificationNull() {
        val governance = assertNotNull(
            builder().governanceConfiguration(DataLoomGovernanceSpec(rbacPolicy = policy())).build().governance,
        )
        assertNotNull(governance.rbacEvaluator)
        assertNull(governance.auditLog)
        assertNull(governance.policyPackVerifier)
    }

    @Test
    fun onlyAuditConfiguredLeavesRbacAndPolicyPackVerificationNull() {
        val store = RecordingAuditStore()
        val governance = assertNotNull(
            builder()
                .governanceConfiguration(
                    DataLoomGovernanceSpec(auditStore = store, auditKey = "key-bytes".encodeToByteArray(), hmacCalculator = FakeHmacCalculator()),
                )
                .build()
                .governance,
        )
        assertNull(governance.rbacEvaluator)
        assertNotNull(governance.auditLog)
        assertNull(governance.policyPackVerifier)
    }

    @Test
    fun onlyHmacCalculatorConfiguredEnablesPolicyPackVerificationAloneLeavesRbacAndAuditNull() {
        val governance = assertNotNull(
            builder()
                .governanceConfiguration(DataLoomGovernanceSpec(hmacCalculator = FakeHmacCalculator()))
                .build()
                .governance,
        )
        assertNull(governance.rbacEvaluator)
        assertNull(governance.auditLog)
        assertNotNull(governance.policyPackVerifier)
    }

    @Test
    fun allThreePiecesCanBeConfiguredTogether() {
        val governance = assertNotNull(
            builder()
                .governanceConfiguration(
                    DataLoomGovernanceSpec(
                        rbacPolicy = policy(),
                        auditStore = RecordingAuditStore(),
                        auditKey = "key-bytes".encodeToByteArray(),
                        hmacCalculator = FakeHmacCalculator(),
                    ),
                )
                .build()
                .governance,
        )
        assertNotNull(governance.rbacEvaluator)
        assertNotNull(governance.auditLog)
        assertNotNull(governance.policyPackVerifier)
    }

    // -- no I/O during build -----------------------------------------------------

    @Test
    fun buildingWithTheSpecPerformsNoAuditStoreIo() {
        val store = RecordingAuditStore()
        val dataLoom = builder()
            .governanceConfiguration(
                DataLoomGovernanceSpec(auditStore = store, auditKey = "key-bytes".encodeToByteArray(), hmacCalculator = FakeHmacCalculator()),
            )
            .build()
        assertNotNull(dataLoom.governance?.auditLog)
        assertTrue(store.calls == 0, "build and property access must not touch the audit store")
    }

    // -- RBAC works end to end -----------------------------------------------------

    @Test
    fun theConfiguredRbacEvaluatorAnswersUsingTheSuppliedPolicy() {
        val governance = assertNotNull(
            builder().governanceConfiguration(DataLoomGovernanceSpec(rbacPolicy = policy())).build().governance,
        )
        val evaluator = assertNotNull(governance.rbacEvaluator)
        val allowed = evaluator.evaluate(
            AccessRequest(
                principal = Principal(PrincipalId("alice"), TenantId("acme")),
                action = Action.EXECUTE,
                resource = ResourceRef(TenantId("acme"), ResourceType("retry.command")),
            ),
        )
        assertIs<PolicyCheckOutcome.Allow>(allowed)

        val denied = evaluator.evaluate(
            AccessRequest(
                principal = Principal(PrincipalId("mallory"), TenantId("acme")),
                action = Action.EXECUTE,
                resource = ResourceRef(TenantId("acme"), ResourceType("retry.command")),
            ),
        )
        assertIs<PolicyCheckOutcome.Deny>(denied)
    }

    // -- audit log works end to end using the runtime clock -----------------------

    @Test
    fun theConfiguredAuditLogAppendsUsingTheBuildersRuntimeClock() = runTest {
        val store = InMemoryAuditStore()
        val governance = assertNotNull(
            builder()
                .governanceConfiguration(
                    DataLoomGovernanceSpec(auditStore = store, auditKey = "key-bytes".encodeToByteArray(), hmacCalculator = FakeHmacCalculator()),
                )
                .build()
                .governance,
        )
        val auditLog = assertNotNull(governance.auditLog)
        val record = auditLog.append(
            AuditEvent(TenantId("acme"), PrincipalId("alice"), AuditEventType("access.denied")),
        )
        assertEquals(DataLoomInstant(epochMilliseconds = 9_000L), record.recordedAt)
        assertEquals(1, store.readAll().size)
    }

    // -- policy-pack verification works end to end ---------------------------------

    @Test
    fun theConfiguredPolicyPackVerifierVerifiesAGenuinelySignedPack() {
        val hmacCalculator = FakeHmacCalculator()
        val governance = assertNotNull(
            builder().governanceConfiguration(DataLoomGovernanceSpec(hmacCalculator = hmacCalculator)).build().governance,
        )
        val verifier = assertNotNull(governance.policyPackVerifier)

        val key = "signing-key".encodeToByteArray()
        val manifest = PolicyPackManifest(PolicySetId("set"), 1L, KeyReference("key-1"), listOf(PolicyCheckId("c1")))
        val pack = SignedPolicyPack.sign(manifest, hmacCalculator, key)

        val result = verifier.verify(pack, PolicyPackKeyResolver { keyId -> if (keyId == KeyReference("key-1")) key else null })
        assertEquals(PolicyPackVerificationResult.Valid(manifest), result)
    }

    // -- spec validation -----------------------------------------------------------

    @Test
    fun theSpecRejectsAnAuditStoreWithoutAnAuditKey() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomGovernanceSpec(auditStore = RecordingAuditStore(), hmacCalculator = FakeHmacCalculator())
        }
    }

    @Test
    fun theSpecRejectsAnAuditStoreWithoutAnHmacCalculator() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomGovernanceSpec(auditStore = RecordingAuditStore(), auditKey = "k".encodeToByteArray())
        }
    }

    @Test
    fun theSpecRejectsConfiguringNothing() {
        assertFailsWith<IllegalArgumentException> { DataLoomGovernanceSpec() }
    }

    @Test
    fun theSpecRendersWithoutKeyMaterialOrPolicyContent() {
        val text = DataLoomGovernanceSpec(rbacPolicy = policy()).toString()
        assertEquals("DataLoomGovernanceSpec(hasRbacPolicy=true, hasAudit=false, hasPolicyPackVerification=false)", text)
    }

    // ---------------------------------------------------------------- fixtures

    private fun policy(): RbacPolicy = RbacPolicy(
        roles = listOf(Role(RoleId("operator"), allow = setOf(Permission(Action.EXECUTE, ResourceType("retry.command"))))),
        bindings = listOf(RoleBinding(PrincipalId("alice"), TenantId("acme"), RoleId("operator"))),
    )

    /** A fake, non-cryptographic keyed hash: deterministic and key-sensitive, which is all these tests need. */
    private class FakeHmacCalculator : DataLoomHmacCalculator {
        override fun hmac(algorithm: HmacAlgorithm, key: ByteArray, input: ByteArray): DataLoomMac {
            require(key.isNotEmpty()) { "key must not be empty." }
            val size = if (algorithm == HmacAlgorithm.HMAC_SHA_256) 32 else 64
            var state = 1_469_598_103_934_665_603UL
            for (byte in key) {
                state = (state xor byte.toUByte().toULong()) * 1_099_511_628_211UL
            }
            for (byte in input) {
                state = (state xor byte.toUByte().toULong()) * 1_099_511_628_211UL
            }
            val bytes = ByteArray(size)
            for (i in bytes.indices) {
                bytes[i] = ((state shr ((i % 8) * 8)) and 0xFFUL).toByte()
                state *= 1_099_511_628_211UL
            }
            return DataLoomMac(algorithm, bytes)
        }

        override fun verify(key: ByteArray, input: ByteArray, expected: DataLoomMac): Boolean =
            hmac(expected.algorithm, key, input) == expected
    }

    private class RecordingAuditStore : AuditStore {
        var calls = 0
        override suspend fun head(): AuditRecord? {
            calls++
            return null
        }

        override suspend fun readAll(): List<AuditRecord> {
            calls++
            return emptyList()
        }

        override suspend fun append(record: AuditRecord) {
            calls++
        }
    }

    private fun builder(): DataLoomBuilder {
        val transport = StubTransportProvider()
        return DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(StrategyProviderBindings(transportProviderId = transport.descriptor.id))
    }

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("governance-event") },
            queueEntryIds = generator { QueueEntryId("governance-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("governance-queue-lease") },
            conflictIds = generator { ConflictId("governance-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class StubTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("governance-transport"),
            name = ProviderName("Governance Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            error("unused")

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            error("unused")
    }
}
