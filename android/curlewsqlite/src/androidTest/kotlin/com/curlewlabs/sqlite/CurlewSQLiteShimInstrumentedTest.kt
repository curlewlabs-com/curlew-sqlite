package com.curlewlabs.sqlite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises every entrypoint of the JNI shim against the real bundled engine.
 *
 * This suite exists because of what the build *cannot* check. `-Wl,--no-undefined` proves
 * each `sqlite3_*` symbol the shim references exists in `libsqliteX.so`, but C carries no
 * signature in its symbol names, so nothing verifies that the prototypes hand-declared in
 * `src/main/cpp/curlew_sqlite3.h` match the engine's real ones. A wrong argument count or
 * width links cleanly and corrupts the stack at run time. Calling each one and asserting a
 * real result is the check - so a future engine pin bump that changed a signature fails
 * here rather than in the field.
 *
 * It is deliberately only the binding: no schema beyond a probe table, no connection
 * profile, no storage layer. A failure here should mean the binding or the engine is
 * wrong, not a caller's use of it.
 */
@RunWith(AndroidJUnit4::class)
class CurlewSQLiteShimInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseFile: File get() = context.getDatabasePath("curlew-sqlite-shim-probe.db")

    @Before
    fun setUp() {
        CurlewSQLite.load()
        context.deleteDatabase(databaseFile.name)
        databaseFile.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseFile.name)
    }

    @Test
    fun everyEntrypointRoundTripsAgainstTheBundledEngine() {
        val database = openProbeDatabase()
        try {
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeExtendedResultCodes(database, 1))

            // The AAR's Java bindings expose no sqlite3_db_config, so defensive mode was
            // out of reach through them. The shim exposes it, and the read-back is what
            // lets a caller require the setting rather than merely request it.
            val defensive = IntArray(1)
            assertEquals(
                CurlewSQLite.OK,
                CurlewSQLite.nativeDbConfigDefensive(database, 1, defensive),
            )
            assertEquals(1, defensive[0])

            execute(database, "CREATE TABLE probe (id INTEGER PRIMARY KEY, v TEXT) STRICT")

            // A non-BMP character, which is the case the byte-array boundary exists for:
            // JNI's own string calls speak modified UTF-8 and would corrupt exactly this.
            val payload = "bound value \uD83D\uDC4B caf\u00E9"
            val insert = prepare(database, "INSERT INTO probe (id, v) VALUES (?, ?)")
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeBindInt64(insert, 1, 7L))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.bindText(insert, 2, payload))
            assertEquals(CurlewSQLite.DONE, CurlewSQLite.nativeStep(insert))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(insert))
            assertEquals(1, CurlewSQLite.nativeChanges(database))

            val read = prepare(database, "SELECT id, v FROM probe WHERE id = ?")
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeBindInt64(read, 1, 7L))
            assertEquals(CurlewSQLite.ROW, CurlewSQLite.nativeStep(read))
            assertEquals(7L, CurlewSQLite.nativeColumnInt64(read, 0))
            assertEquals(payload, CurlewSQLite.columnText(read, 1))
            assertEquals(CurlewSQLite.DONE, CurlewSQLite.nativeStep(read))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(read))
        } finally {
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeCloseV2(database))
        }
    }

    /**
     * The engine that loaded is the pinned one. A libsqliteX.so from anywhere else - a
     * different AAR, or a platform library resolved by mistake - fails the source-ID guard
     * even at the same version.
     */
    @Test
    fun theLoadedEngineReportsThePinnedIdentity() {
        val database = openProbeDatabase()
        try {
            val read = prepare(database, "SELECT sqlite_version(), sqlite_source_id()")
            assertEquals(CurlewSQLite.ROW, CurlewSQLite.nativeStep(read))
            val version = checkNotNull(CurlewSQLite.columnText(read, 0))
            val sourceId = checkNotNull(CurlewSQLite.columnText(read, 1))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(read))

            assertEquals(AndroidBundledSQLiteVersionGuard.minimumVersion, AndroidBundledSQLiteVersionGuard.requireSupported(version))
            assertEquals(AndroidBundledSQLiteSourceIdGuard.EXPECTED_SOURCE_ID, sourceId)
            AndroidBundledSQLiteSourceIdGuard.requireExpected(sourceId)
        } finally {
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeCloseV2(database))
        }
    }

    /** A SQL NULL must be distinguishable from the empty string, which shares its type. */
    @Test
    fun columnTextSeparatesSqlNullFromTheEmptyString() {
        val database = openProbeDatabase()
        try {
            execute(database, "CREATE TABLE probe (id INTEGER PRIMARY KEY, v TEXT) STRICT")
            execute(database, "INSERT INTO probe (id, v) VALUES (1, NULL), (2, '')")

            val read = prepare(database, "SELECT v FROM probe ORDER BY id")
            assertEquals(CurlewSQLite.ROW, CurlewSQLite.nativeStep(read))
            assertNull(CurlewSQLite.columnText(read, 0))
            assertEquals(CurlewSQLite.ROW, CurlewSQLite.nativeStep(read))
            assertEquals("", CurlewSQLite.columnText(read, 0))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(read))
        } finally {
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeCloseV2(database))
        }
    }

    /**
     * A bound SQL NULL must be distinguishable from a stored integer zero: nativeColumnInt64
     * answers 0 for both, so [CurlewSQLite.isNull] (nativeColumnType underneath) is the only
     * thing that tells them apart. Both values go in through the binding, not as SQL
     * literals, so nativeBindNull is the call under test.
     */
    @Test
    fun aBoundNullIsDistinguishableFromAStoredZero() {
        val database = openProbeDatabase()
        try {
            execute(database, "CREATE TABLE probe (id INTEGER PRIMARY KEY, n INTEGER) STRICT")
            val insert = prepare(database, "INSERT INTO probe (id, n) VALUES (?, ?)")
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeBindInt64(insert, 1, 1L))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeBindNull(insert, 2))
            assertEquals(CurlewSQLite.DONE, CurlewSQLite.nativeStep(insert))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(insert))

            val zero = prepare(database, "INSERT INTO probe (id, n) VALUES (?, ?)")
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeBindInt64(zero, 1, 2L))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeBindInt64(zero, 2, 0L))
            assertEquals(CurlewSQLite.DONE, CurlewSQLite.nativeStep(zero))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(zero))

            val read = prepare(database, "SELECT n FROM probe ORDER BY id")
            assertEquals(CurlewSQLite.ROW, CurlewSQLite.nativeStep(read))
            assertTrue(CurlewSQLite.isNull(read, 0))
            assertEquals(0L, CurlewSQLite.nativeColumnInt64(read, 0))
            assertEquals(CurlewSQLite.ROW, CurlewSQLite.nativeStep(read))
            assertFalse(CurlewSQLite.isNull(read, 0))
            assertEquals(0L, CurlewSQLite.nativeColumnInt64(read, 0))
            assertEquals(CurlewSQLite.DONE, CurlewSQLite.nativeStep(read))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(read))
        } finally {
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeCloseV2(database))
        }
    }

    /**
     * A failed prepare must report a usable diagnosis: the result code and the message are
     * what a caller's error mapping is built on. The AAR's Java bindings surfaced neither -
     * they mapped result codes onto exception types and exposed no numeric code.
     */
    @Test
    fun aFailedStatementReportsItsResultCodeAndMessage() {
        val database = openProbeDatabase()
        try {
            val statement = LongArray(1)
            val result = CurlewSQLite.prepareV2(database, "SELECT * FROM no_such_table", statement)

            assertNotEquals(CurlewSQLite.OK, result)
            assertEquals(0L, statement[0])
            assertNotEquals(CurlewSQLite.OK, CurlewSQLite.nativeExtendedErrcode(database))
            assertTrue(
                "expected the message to name the missing table",
                CurlewSQLite.errmsg(database).contains("no_such_table"),
            )
        } finally {
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeCloseV2(database))
        }
    }

    /**
     * The regression this whole layer exists for: `BEGIN IMMEDIATE` must take write intent
     * (RESERVED), which still admits a reader, and not the exclusive lock.
     *
     * The AAR's `SQLiteSession` classifies a statement by its prefix and runs every
     * `BEG...` as `BEGIN EXCLUSIVE`, so this same pairing through it refuses the reader for
     * the full busy budget and then fails - on a database that is merely being written to.
     *
     * The admission alone is not the property: a reader let in only after the peer's
     * transaction ended would satisfy a bare success check too. What rules that out is the
     * pair of values the connections see, not how long the read took - the reader
     * observing the committed image excludes a commit having landed first, and the peer
     * still observing its own uncommitted one excludes a rollback. So the peer held write
     * intent at the instant the reader was admitted, which is what the same statement does
     * on iOS.
     */
    @Test
    fun beginImmediateTakesWriteIntentAndStillAdmitsAReader() {
        val peer = openProbeDatabase()
        val reader = openProbeDatabase()
        try {
            execute(peer, "PRAGMA busy_timeout = 2500")
            execute(reader, "PRAGMA busy_timeout = 2500")
            execute(peer, "CREATE TABLE probe (id INTEGER PRIMARY KEY, v TEXT) STRICT")
            execute(peer, "INSERT INTO probe (id, v) VALUES (1, 'before')")

            execute(peer, "BEGIN IMMEDIATE")
            execute(peer, "UPDATE probe SET v = 'after' WHERE id = 1")
            try {
                val read = prepare(reader, "SELECT v FROM probe WHERE id = 1")
                assertEquals(CurlewSQLite.ROW, CurlewSQLite.nativeStep(read))
                // The reader sees the pre-transaction value: RESERVED lets it read the
                // committed image while the writer holds write intent.
                assertEquals("before", CurlewSQLite.columnText(read, 0))
                assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(read))

                // The rollback half of that ordering: a peer that had already ended its
                // transaction would leave 'before' visible to the reader too.
                val peerRead = prepare(peer, "SELECT v FROM probe WHERE id = 1")
                assertEquals(CurlewSQLite.ROW, CurlewSQLite.nativeStep(peerRead))
                assertEquals("after", CurlewSQLite.columnText(peerRead, 0))
                assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(peerRead))
            } finally {
                execute(peer, "ROLLBACK")
            }
        } finally {
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeCloseV2(reader))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeCloseV2(peer))
        }
    }

    /**
     * The other half of the pair above: write intent must still exclude a second writer.
     * Without this, "the reader was admitted" would be equally satisfied by a peer that
     * took no lock at all.
     */
    @Test
    fun beginImmediateStillExcludesASecondWriter() {
        val peer = openProbeDatabase()
        val second = openProbeDatabase()
        try {
            execute(peer, "PRAGMA busy_timeout = 250")
            execute(second, "PRAGMA busy_timeout = 250")
            execute(peer, "CREATE TABLE probe (id INTEGER PRIMARY KEY, v TEXT) STRICT")

            execute(peer, "BEGIN IMMEDIATE")
            try {
                val statement = LongArray(1)
                assertEquals(
                    CurlewSQLite.OK,
                    CurlewSQLite.prepareV2(second, "BEGIN IMMEDIATE", statement),
                )
                val result = CurlewSQLite.nativeStep(statement[0])
                assertTrue(
                    "expected a contention code, got $result",
                    CurlewSQLite.isContentionCode(result),
                )
                // finalize reports the failed step's code rather than OK, which is how a
                // caller can surface a busy outcome from a cleanup path.
                assertEquals(result, CurlewSQLite.nativeFinalize(statement[0]))
            } finally {
                execute(peer, "ROLLBACK")
            }
        } finally {
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeCloseV2(second))
            assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeCloseV2(peer))
        }
    }

    private fun openProbeDatabase(): Long {
        val handle = LongArray(1)
        val result =
            CurlewSQLite.openV2(
                databaseFile.absolutePath,
                CurlewSQLite.OPEN_READWRITE or CurlewSQLite.OPEN_CREATE,
                handle,
            )
        assertEquals(CurlewSQLite.OK, result)
        assertNotEquals(0L, handle[0])
        return handle[0]
    }

    private fun prepare(
        database: Long,
        sql: String,
    ): Long {
        val statement = LongArray(1)
        assertEquals(
            "prepare failed: ${CurlewSQLite.errmsg(database)}",
            CurlewSQLite.OK,
            CurlewSQLite.prepareV2(database, sql, statement),
        )
        assertNotEquals(0L, statement[0])
        return statement[0]
    }

    private fun execute(
        database: Long,
        sql: String,
    ) {
        val statement = prepare(database, sql)
        val result = CurlewSQLite.nativeStep(statement)
        assertEquals(CurlewSQLite.OK, CurlewSQLite.nativeFinalize(statement))
        assertTrue(
            "expected $sql to complete, got $result: ${CurlewSQLite.errmsg(database)}",
            result == CurlewSQLite.DONE || result == CurlewSQLite.ROW,
        )
    }
}
