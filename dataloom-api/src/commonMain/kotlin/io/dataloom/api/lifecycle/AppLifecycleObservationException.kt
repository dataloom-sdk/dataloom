package io.dataloom.api.lifecycle

import io.dataloom.api.error.DataLoomError

/**
 * Terminates an [AppLifecycleProvider.states] stream when the platform
 * lifecycle source cannot be observed.
 *
 * A [kotlinx.coroutines.flow.Flow] has no result channel, so the canonical
 * [DataLoomError] a provider would otherwise return travels inside this
 * exception. Coroutine cancellation is never converted into this exception.
 *
 * @property error sanitized canonical error describing the failure. It
 *   retains no raw platform exception, message, or stack trace.
 */
public class AppLifecycleObservationException(
    public val error: DataLoomError,
) : Exception(error.message)
