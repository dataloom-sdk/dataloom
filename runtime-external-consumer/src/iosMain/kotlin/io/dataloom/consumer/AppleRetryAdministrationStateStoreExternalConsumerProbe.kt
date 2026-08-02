package io.dataloom.consumer

import io.dataloom.api.retry.RetryAdministrationStateStore
import io.dataloom.runtime.retry.AppleFileRetryAdministrationStateStore

/** External Apple consumer probe for durable retry-administration state. */
public fun createAppleRetryAdministrationStateStore(
    directoryPath: String,
    fileName: String = AppleFileRetryAdministrationStateStore.DEFAULT_FILE_NAME,
): RetryAdministrationStateStore = AppleFileRetryAdministrationStateStore(
    directoryPath = directoryPath,
    fileName = fileName,
)
