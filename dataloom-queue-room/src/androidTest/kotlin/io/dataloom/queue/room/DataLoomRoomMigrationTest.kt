package io.dataloom.queue.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration-test foundation for every future Room schema version.
 *
 * Version 1 has no predecessor, so this verifies that the committed exported
 * schema is packaged as an androidTest asset and can create a database. Future
 * versions must extend this class with migration and validation tests.
 */
@RunWith(AndroidJUnit4::class)
class DataLoomRoomMigrationTest {

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DataLoomRoomDatabase::class.java,
    )

    @Test
    fun version1SchemaCanBeCreatedFromCommittedAsset() {
        migrationTestHelper.createDatabase(TEST_DATABASE, 1).close()
    }

    private companion object {
        const val TEST_DATABASE = "dataloom-room-migration-test"
    }
}
