package com.curlewlabs.sqlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledSQLiteIdentityTest {
    @Test
    fun acceptsMinimumAndNewerCanonicalVersions() {
        assertEquals(
            SQLiteEngineVersion(3, 54, 1),
            AndroidBundledSQLiteVersionGuard.requireSupported("3.54.1"),
        )
        assertEquals(
            SQLiteEngineVersion(4, 0, 1),
            AndroidBundledSQLiteVersionGuard.requireSupported("4.0.1"),
        )
    }

    @Test
    fun rejectsVersionImmediatelyBelowTheFloor() {
        val failure =
            kotlin
                .runCatching {
                    AndroidBundledSQLiteVersionGuard.requireSupported("3.54.0")
                }.exceptionOrNull()

        assertTrue(failure is UnsupportedBundledSQLiteVersionException)
        assertEquals("3.54.0", (failure as UnsupportedBundledSQLiteVersionException).reportedVersion)
        assertEquals(AndroidBundledSQLiteVersionGuard.minimumVersion, failure.minimumVersion)
    }

    // A malformed engine report is an unexpected engine, not a
    // version that can safely proceed to open a database.
    @Test
    fun rejectsMalformedVersionWithoutPartialParsing() {
        val failure =
            kotlin
                .runCatching {
                    AndroidBundledSQLiteVersionGuard.requireSupported("3.37")
                }.exceptionOrNull()

        assertTrue(failure is UnsupportedBundledSQLiteVersionException)
        assertTrue((failure as UnsupportedBundledSQLiteVersionException).malformed)
        assertEquals("3.37", failure.reportedVersion)
    }

    // The version string alone cannot distinguish an OS engine or a rebuilt
    // binary with different patches from the pinned sqlite.org artifact.
    @Test
    fun sourceIdGuardRejectsDifferentEngineIdentity() {
        val failure =
            kotlin
                .runCatching {
                    AndroidBundledSQLiteSourceIdGuard.requireExpected(
                        "2026-06-26 20:14:12 different-source-id",
                    )
                }.exceptionOrNull()

        assertTrue(failure is UnsupportedBundledSQLiteSourceIdException)
        assertEquals(
            "2026-06-26 20:14:12 different-source-id",
            (failure as UnsupportedBundledSQLiteSourceIdException).reportedSourceId,
        )
        assertEquals(AndroidBundledSQLiteSourceIdGuard.EXPECTED_SOURCE_ID, failure.expectedSourceId)
    }
}
