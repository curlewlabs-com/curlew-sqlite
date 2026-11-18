package com.curlewlabs.sqlite

/*
 * The pinned identity of the engine this library packages, and the check a caller runs
 * against it on every open before touching its own tables: a version floor plus an exact
 * source-ID match. A mismatch is a hard failure; there is no retry through the platform's
 * SQLite and no lower-capability mode.
 *
 * Pure string comparison, so it needs no native code. The caller reads
 * `SELECT sqlite_version()` and `SELECT sqlite_source_id()` on its own connection and
 * passes the strings in. The iOS twin is `CurlewSQLiteProviderIdentity`.
 */

data class SQLiteEngineVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SQLiteEngineVersion> {
    override fun compareTo(other: SQLiteEngineVersion): Int =
        compareValuesBy(this, other, SQLiteEngineVersion::major, SQLiteEngineVersion::minor, SQLiteEngineVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

object AndroidBundledSQLiteVersionGuard {
    private val versionPattern = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)")

    // Enforce the pinned release, not merely STRICT-table support: versions through
    // 3.51.2 can contain SQLite's WAL-reset corruption bug, and rejecting that range keeps
    // a future journal-mode change from silently turning an accepted binary into a
    // corruption risk.
    val minimumVersion = SQLiteEngineVersion(3, 54, 1)

    fun requireSupported(reportedVersion: String): SQLiteEngineVersion {
        val parsed = parse(reportedVersion)
        if (parsed < minimumVersion) {
            throw UnsupportedBundledSQLiteVersionException(
                reportedVersion = reportedVersion,
                minimumVersion = minimumVersion,
                malformed = false,
            )
        }
        return parsed
    }

    private fun parse(reportedVersion: String): SQLiteEngineVersion {
        val match = versionPattern.matchEntire(reportedVersion)
        val components = match?.groupValues?.drop(1)?.map { it.toIntOrNull() }
        if (components == null || components.size != 3 || components.any { it == null }) {
            throw UnsupportedBundledSQLiteVersionException(
                reportedVersion = reportedVersion,
                minimumVersion = minimumVersion,
                malformed = true,
            )
        }
        return SQLiteEngineVersion(
            major = checkNotNull(components[0]),
            minor = checkNotNull(components[1]),
            patch = checkNotNull(components[2]),
        )
    }
}

/**
 * A newer version is permitted by [AndroidBundledSQLiteVersionGuard] while this comparison
 * stays exact, which is what keeps the effective engine hard-pinned: an engine bump has to
 * move the AAR pin, its SHA-256, the minimum version, and this source ID together.
 */
object AndroidBundledSQLiteSourceIdGuard {
    const val EXPECTED_SOURCE_ID =
        "2026-10-16 14:45:20 555e07ef33f69a0957d24d8e555b702464a131d6f1607f4c3c1e4551e8e6ca76"

    fun requireExpected(reportedSourceId: String) {
        if (reportedSourceId != EXPECTED_SOURCE_ID) {
            throw UnsupportedBundledSQLiteSourceIdException(
                reportedSourceId = reportedSourceId,
                expectedSourceId = EXPECTED_SOURCE_ID,
            )
        }
    }
}

class UnsupportedBundledSQLiteVersionException(
    val reportedVersion: String,
    val minimumVersion: SQLiteEngineVersion,
    val malformed: Boolean,
) : IllegalStateException(
        if (malformed) {
            "Unsupported SQLite version '$reportedVersion'; expected major.minor.patch and at least $minimumVersion"
        } else {
            "Unsupported SQLite version '$reportedVersion'; require at least $minimumVersion"
        },
    )

class UnsupportedBundledSQLiteSourceIdException(
    val reportedSourceId: String,
    val expectedSourceId: String,
) : IllegalStateException(
        "Unsupported SQLite source id '$reportedSourceId'; expected '$expectedSourceId'",
    )
