package io.dataloom.runtime.operational

import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Fixed clock for tests that construct a [io.dataloom.api.operational.DurableOperationalEventOutbox]
 * directly and only need the time source it now requires (for acknowledgement
 * timestamps and age-based retention), not any particular reading.
 */
internal val outboxTestClock: DataLoomClock = object : DataLoomClock {
    override fun now(): DataLoomInstant = DataLoomInstant(1_000L)
}
