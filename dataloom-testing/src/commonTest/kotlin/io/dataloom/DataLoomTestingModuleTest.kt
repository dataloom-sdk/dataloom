package io.dataloom

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Verifies that the dataloom-testing module compiles and that its common source
 * set is reachable under the approved test configuration.
 */
class DataLoomTestingModuleTest {
    @Test
    fun `testing module marker is accessible`() {
        assertNotNull(DataLoomTestingModule::class.simpleName)
    }
}
