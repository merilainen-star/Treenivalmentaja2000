package fi.merilainen.treenivalmentaja.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `AppDatabase` deliberately has no `fallbackToDestructiveMigration`, so a schema version with no
 * migration must fail loudly and leave the data alone — not empty the database quietly.
 *
 * The second assertion is the one that matters: after the failure the rows are still on disk, so
 * the situation is recoverable by writing the missing migration. If the destructive fallback is
 * ever reintroduced, this test fails.
 */
@RunWith(AndroidJUnit4::class)
class MigrationGuardTest {

    private val dbName = "migration-guard-test.db"
    private lateinit var context: Context

    /** No schema was exported for version 2, so no migration to the current version can exist. */
    private val orphanedVersion = 2

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)

        // A database left behind by a version the app can no longer migrate from, holding a row
        // that stands in for the user's training history.
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        legacy.execSQL("CREATE TABLE `precious` (`id` TEXT NOT NULL PRIMARY KEY)")
        legacy.execSQL("INSERT INTO `precious` (`id`) VALUES ('do-not-lose-me')")
        legacy.version = orphanedVersion
        legacy.close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun missingMigrationFailsLoudlyAndKeepsTheData() {
        val db = AppDatabase.builder(context, dbName).build()

        try {
            db.openHelper.writableDatabase
            fail("Opening across a missing migration should have thrown, but it succeeded — has fallbackToDestructiveMigration been reintroduced?")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "Expected a missing-migration message, got: ${expected.message}",
                expected.message?.contains("migration", ignoreCase = true) == true,
            )
        } finally {
            runCatching { db.close() }
        }

        val survivor = SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY
        )
        survivor.use {
            it.rawQuery("SELECT `id` FROM `precious`", null).use { cursor ->
                assertTrue("The row was wiped by the failed open", cursor.moveToFirst())
                assertEquals("do-not-lose-me", cursor.getString(0))
                assertEquals(1, cursor.count)
            }
            assertEquals(
                "The schema version was rewritten despite the failure",
                orphanedVersion,
                it.version,
            )
        }
    }
}
