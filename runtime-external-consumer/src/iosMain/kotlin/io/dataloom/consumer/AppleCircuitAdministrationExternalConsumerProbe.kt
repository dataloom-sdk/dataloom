package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitAdministrationExecutor
import io.dataloom.api.circuit.CircuitAdministrationStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.AppleFileCircuitAdministrationExecutor
import io.dataloom.runtime.retry.AppleFileCircuitAdministrationStateStore

public fun appleCircuitAdministrationStateStoreExternalProbe(
    directoryPath: String,
    fileName: String = AppleFileCircuitAdministrationStateStore.DEFAULT_FILE_NAME,
): CircuitAdministrationStateStore = AppleFileCircuitAdministrationStateStore(
    directoryPath = directoryPath,
    fileName = fileName,
)

public fun appleCircuitAdministrationExecutorExternalProbe(
    directoryPath: String,
    clock: DataLoomClock,
    fileName: String = AppleFileCircuitAdministrationExecutor.DEFAULT_FILE_NAME,
): CircuitAdministrationExecutor = AppleFileCircuitAdministrationExecutor(
    directoryPath = directoryPath,
    clock = clock,
    fileName = fileName,
)
