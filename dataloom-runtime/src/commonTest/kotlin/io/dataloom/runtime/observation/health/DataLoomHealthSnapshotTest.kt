package io.dataloom.runtime.observation.health

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.runtime.observation.retry.RetryCircuitExporterHealth
import io.dataloom.runtime.observation.retry.RetryCircuitExporterSnapshot
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetryExporterId
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataLoomHealthSnapshotTest {

    @Test
    fun defaultCallWithNoCollaboratorsProducesEmptyAbsentSectionsNotACrash() {
        val snapshot = dataLoomHealthSnapshot()

        assertNull(snapshot.providerLifecycleState)
        assertNull(snapshot.retryCircuitTelemetry)
        assertTrue(snapshot.providerHealth.isEmpty())
    }

    @Test
    fun snapshotReflectsSuppliedProviderLifecycleState() {
        val snapshot = dataLoomHealthSnapshot(
            providerLifecycleState = ProviderLifecycleCoordinatorState.INITIALIZED,
        )

        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, snapshot.providerLifecycleState)
    }

    @Test
    fun snapshotReflectsSuppliedRetryCircuitTelemetrySnapshotByReference() {
        val telemetrySnapshot = RetryCircuitTelemetrySnapshot(
            metricCounts = emptyMap(),
            exporters = listOf(
                RetryCircuitExporterSnapshot(
                    exporterId = RetryCircuitTelemetryExporterId("primary"),
                    health = RetryCircuitExporterHealth.HEALTHY,
                    acceptedCount = 5L,
                    droppedCount = 0L,
                    exportedCount = 5L,
                    failureCount = 0L,
                    timeoutCount = 0L,
                    lastFailureReason = null,
                ),
            ),
        )

        val snapshot = dataLoomHealthSnapshot(retryCircuitTelemetry = telemetrySnapshot)

        assertEquals(telemetrySnapshot, snapshot.retryCircuitTelemetry)
    }

    @Test
    fun providerHealthStatusIsPreservedForEachProviderKey() {
        val providerId = ProviderId("storage-primary")
        val snapshot = dataLoomHealthSnapshot(
            providerHealth = mapOf(
                providerId to ProviderHealth(status = ProviderHealthStatus.DEGRADED),
            ),
        )

        assertEquals(ProviderHealthStatus.DEGRADED, snapshot.providerHealth.getValue(providerId).status)
    }

    @Test
    fun errorMessageIsMaskedByDefaultRedactionPolicyProvingASensitiveFieldIsRedacted() {
        val providerId = ProviderId("transport-primary")
        val sensitiveError = testError(message = "credential rotation failed for key vault entry 7f3a-secret")
        val snapshot = dataLoomHealthSnapshot(
            providerHealth = mapOf(
                providerId to ProviderHealth(status = ProviderHealthStatus.UNHEALTHY, error = sensitiveError),
            ),
        )

        val errorAttributes = snapshot.providerHealth.getValue(providerId).errorAttributes

        // The raw message must never appear anywhere in the redacted output.
        errorAttributes.entries.values.forEach { value ->
            assertTrue(!value.contains("secret"), "Redacted attributes leaked the raw error message: $value")
        }
        // CONFIDENTIAL fields are removed outright by the default policy, so
        // no "message" key survives at all -- proving genuine redaction, not
        // merely truncation.
        assertNull(errorAttributes["message"])
    }

    @Test
    fun closedErrorVocabularyFieldsSurviveDefaultRedactionUnmasked() {
        val providerId = ProviderId("scheduler-primary")
        val error = testError(message = "irrelevant for this assertion")
        val snapshot = dataLoomHealthSnapshot(
            providerHealth = mapOf(
                providerId to ProviderHealth(status = ProviderHealthStatus.UNHEALTHY, error = error),
            ),
        )

        val errorAttributes = snapshot.providerHealth.getValue(providerId).errorAttributes

        assertEquals("TEST-CODE", errorAttributes["code"])
        assertEquals(ErrorCategory.NETWORK.name, errorAttributes["category"])
        assertEquals(ErrorSeverity.ERROR.name, errorAttributes["severity"])
        assertEquals(Recoverability.RECOVERABLE.name, errorAttributes["recoverability"])
    }

    @Test
    fun healthyProviderWithNoErrorProducesEmptyErrorAttributes() {
        val providerId = ProviderId("connectivity-primary")
        val snapshot = dataLoomHealthSnapshot(
            providerHealth = mapOf(
                providerId to ProviderHealth(status = ProviderHealthStatus.HEALTHY),
            ),
        )

        assertTrue(snapshot.providerHealth.getValue(providerId).errorAttributes.isEmpty())
    }

    @Test
    fun detailFieldCountReflectsMetadataSizeWithoutDisclosingContent() {
        val providerId = ProviderId("storage-secondary")
        val details = DataLoomMetadata.of(
            mapOf(
                "internalDiskPath" to "/var/secret/mount",
                "internalHostAlias" to "node-7",
            ),
        )
        val snapshot = dataLoomHealthSnapshot(
            providerHealth = mapOf(
                providerId to ProviderHealth(status = ProviderHealthStatus.HEALTHY, details = details),
            ),
        )

        val redacted = snapshot.providerHealth.getValue(providerId)
        assertEquals(2, redacted.detailFieldCount)
        // toString() must never leak the underlying metadata keys/values.
        assertTrue(!redacted.toString().contains("/var/secret/mount"))
        assertTrue(!redacted.toString().contains("internalDiskPath"))
    }

    private fun testError(message: String): DataLoomError = object : DataLoomError {
        override val code = ErrorCode("TEST-CODE")
        override val category = ErrorCategory.NETWORK
        override val severity = ErrorSeverity.ERROR
        override val recoverability = Recoverability.RECOVERABLE
        override val message = message
        override val cause: Throwable? = null
    }
}
