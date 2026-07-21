package io.dataloom

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Verifies that the dataloom-testing module compiles and that its common source
 * set is reachable under the approved test configuration.
 *
 * The approved dependencies on dataloom-api and dataloom-core are verified
 * structurally: if either dependency were absent the module would fail to
 * compile.
 */
class DataLoomTestingModuleTest {

    @Test
    fun `testing module compiles and internal marker is accessible`() {
        assertNotNull(DataLoomTestingModule::class.simpleName)
    }
}
