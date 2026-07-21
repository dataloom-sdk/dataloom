package io.dataloom

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Verifies that the dataloom-core module compiles and that its common source
 * set is reachable under the approved test configuration.
 *
 * The approved dependency on dataloom-api is verified structurally: if the
 * dependency were absent the module would fail to compile.
 */
class DataLoomCoreModuleTest {

    @Test
    fun `core module compiles and internal marker is accessible`() {
        assertNotNull(DataLoomCoreModule::class.simpleName)
    }
}
