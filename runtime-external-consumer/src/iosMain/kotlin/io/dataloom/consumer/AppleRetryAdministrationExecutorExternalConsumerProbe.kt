package io.dataloom.consumer

import io.dataloom.api.retry.RetryAdministrationExecutor
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.AppleFileRetryAdministrationExecutor

/** Proves the public Apple executor constructor from an external KMP consumer. */
public fun createAppleRetryAdministrationExecutor(
    directoryPath: String,
    clock: DataLoomClock,
): RetryAdministrationExecutor = AppleFileRetryAdministrationExecutor(
    directoryPath = directoryPath,
    clock = clock,
)
