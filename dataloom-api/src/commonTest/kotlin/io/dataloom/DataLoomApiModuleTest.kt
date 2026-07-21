package io.dataloom

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Verifies that the dataloom-api module compiles and the common source set
 * is reachable under the approved test configuration.
 */
class DataLoomApiModuleTest {

    @Test
    fun `api module compiles and internal marker is accessible`() {
        assertNotNull(DataLoomApiModule::class.simpleName)
    }
}
