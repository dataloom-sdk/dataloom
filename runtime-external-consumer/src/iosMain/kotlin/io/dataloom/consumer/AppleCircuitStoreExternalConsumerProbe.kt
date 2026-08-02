package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.runtime.retry.AppleFileCircuitBreakerStateStore

/** External Apple consumer probe for production durable circuit persistence. */
public fun createAppleCircuitBreakerStateStore(
    directoryPath: String,
    fileName: String = AppleFileCircuitBreakerStateStore.DEFAULT_FILE_NAME,
): CircuitBreakerStateStore = AppleFileCircuitBreakerStateStore(
    directoryPath = directoryPath,
    fileName = fileName,
)
