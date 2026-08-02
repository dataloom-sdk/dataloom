package io.dataloom.consumer

import io.dataloom.api.queue.QueueProvider
import io.dataloom.runtime.queue.AppleFileQueueProvider

/** External Apple consumer probe for production durable queue persistence. */
public fun createAppleQueueProvider(
    directoryPath: String,
    fileName: String = AppleFileQueueProvider.DEFAULT_FILE_NAME,
): QueueProvider = AppleFileQueueProvider(
    directoryPath = directoryPath,
    fileName = fileName,
)
