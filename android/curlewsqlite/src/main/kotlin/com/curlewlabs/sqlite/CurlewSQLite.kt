package com.curlewlabs.sqlite

import java.nio.charset.StandardCharsets

/**
 * Android's direct binding to sqlite.org's engine, and the whole of what sits between a
 * caller and `sqlite3_*`.
 *
 * This is the Kotlin face of `src/main/cpp/curlew_sqlite_jni.c`, and its surface is
 * deliberately the same entrypoint set the iOS shim (`ios/CurlewSQLiteShim.h`) declares.
 * Neither side builds SQL, caches a handle, or holds state; the caller owns statement
 * lifetime on every platform. That symmetry is the point - storage code written against
 * the Android and iOS bindings can be read against each other because the primitives
 * underneath them match.
 *
 * It replaces the AAR's AOSP Java bindings (`org.sqlite.database.*`), whose
 * `SQLiteSession` classifies a statement by its first three characters and runs every
 * `BEG...` as `BEGIN EXCLUSIVE` - so a caller's `BEGIN IMMEDIATE` takes the exclusive
 * lock rather than write intent and excludes every concurrent reader, unlike the same
 * statement on iOS. The engine underneath is unchanged: the AAR's own `libsqliteX.so`.
 *
 * **Handles are raw pointers carried as `Long`.** Passing a stale or already-finalized
 * handle back across this boundary is a use-after-free, not an exception - the same
 * contract Swift has with its `OpaquePointer`s, and the reason a caller should keep
 * statement lifetime inside one type rather than exposing it.
 *
 * Text crosses as UTF-8 `ByteArray`; the wrappers below do the `String` conversion. The C
 * file's header comment owns why (the JNI string calls speak modified UTF-8, which
 * corrupts non-BMP characters).
 */
object CurlewSQLite {
    // Result codes. Only the ones a caller commonly branches on are declared - the rest
    // pass through as opaque ints.
    const val OK = 0
    const val ROW = 100
    const val DONE = 101

    // The two lock-contention codes, checked against the primary byte so an extended code
    // (SQLITE_BUSY_SNAPSHOT and friends) classifies with its family.
    const val BUSY = 5
    const val LOCKED = 6

    // Damage, as distinct from "could not read": SQLITE_NOTADB means the header is not
    // SQLite's at all, SQLITE_CORRUPT means the pages disagree with themselves.
    const val CORRUPT = 11
    const val NOTADB = 26

    // A rejected write. Reading it confirms the engine enforces STRICT column types,
    // where the extended code is SQLITE_CONSTRAINT_DATATYPE.
    const val CONSTRAINT = 19

    // The primary-code mask. An extended result code packs its family into the low byte.
    const val PRIMARY_CODE_MASK = 0xFF

    // SQLITE_NULL, the one column type the binding branches on: [nativeColumnInt64] returns
    // 0 for a SQL NULL and for a stored zero alike, so a nullable INTEGER column is read
    // back through [isNull] first. A nullable TEXT column needs no type check - [columnText]
    // already answers null - so the other four type codes are never compared against.
    const val TYPE_NULL = 5

    const val OPEN_READWRITE = 0x00000002
    const val OPEN_CREATE = 0x00000004

    /**
     * Loads the shim, which the dynamic linker resolves against the AAR's `libsqliteX.so`
     * as a declared dependency - so this one call brings up the shim and the engine, and
     * no caller loads the engine directly.
     *
     * Idempotent, and cheap after the first call: the runtime tracks loaded libraries per
     * classloader. Called by the caller before its first SQLite call, rather than from a
     * static initializer, so the ordering is explicit at every entry point.
     *
     * A failure is rethrown as [CurlewSQLiteLoadError], so a caller can tell "the library
     * is not on this device" from a native method that failed to resolve after a successful
     * load - the two arrive as the same [UnsatisfiedLinkError].
     */
    fun load() {
        try {
            System.loadLibrary("curlewsqlite")
        } catch (error: UnsatisfiedLinkError) {
            throw CurlewSQLiteLoadError(error)
        }
    }

    external fun nativeOpenV2(
        pathUtf8: ByteArray,
        flags: Int,
        outHandle: LongArray,
    ): Int

    external fun nativeCloseV2(database: Long): Int

    external fun nativeExtendedResultCodes(
        database: Long,
        enabled: Int,
    ): Int

    external fun nativeExtendedErrcode(database: Long): Int

    external fun nativeErrmsg(database: Long): ByteArray?

    external fun nativePrepareV2(
        database: Long,
        sqlUtf8: ByteArray,
        outStatement: LongArray,
    ): Int

    external fun nativeFinalize(statement: Long): Int

    external fun nativeStep(statement: Long): Int

    external fun nativeColumnText(
        statement: Long,
        column: Int,
    ): ByteArray?

    external fun nativeColumnInt64(
        statement: Long,
        column: Int,
    ): Long

    external fun nativeColumnType(
        statement: Long,
        column: Int,
    ): Int

    external fun nativeChanges(database: Long): Int

    external fun nativeDbConfigDefensive(
        database: Long,
        enabled: Int,
        outCurrent: IntArray,
    ): Int

    external fun nativeBindText(
        statement: Long,
        index: Int,
        valueUtf8: ByteArray,
    ): Int

    external fun nativeBindInt64(
        statement: Long,
        index: Int,
        value: Long,
    ): Int

    external fun nativeBindNull(
        statement: Long,
        index: Int,
    ): Int

    /**
     * Opens [path], writing the handle into [outHandle] even when the result is not [OK] -
     * `sqlite3_open_v2` hands back a usable handle for a failed open so the caller can read
     * [errmsg] off it, and the caller closes it either way.
     */
    fun openV2(
        path: String,
        flags: Int,
        outHandle: LongArray,
    ): Int = nativeOpenV2(path.toByteArray(StandardCharsets.UTF_8), flags, outHandle)

    fun prepareV2(
        database: Long,
        sql: String,
        outStatement: LongArray,
    ): Int = nativePrepareV2(database, sql.toByteArray(StandardCharsets.UTF_8), outStatement)

    fun errmsg(database: Long): String = nativeErrmsg(database)?.toString(StandardCharsets.UTF_8) ?: "no error message"

    /** Null for a SQL NULL, distinct from the empty string a zero-length value returns. */
    fun columnText(
        statement: Long,
        column: Int,
    ): String? = nativeColumnText(statement, column)?.toString(StandardCharsets.UTF_8)

    fun bindText(
        statement: Long,
        index: Int,
        value: String,
    ): Int = nativeBindText(statement, index, value.toByteArray(StandardCharsets.UTF_8))

    /**
     * True when the column holds a SQL NULL in the current row. Read it BEFORE
     * [columnText] on the same column: that call may convert the value's encoding and
     * rewrite the type the shim reports. On iOS, compare
     * `curlew_sqlite3_column_type` against `SQLITE_NULL` at the same point.
     */
    fun isNull(
        statement: Long,
        column: Int,
    ): Boolean = nativeColumnType(statement, column) == TYPE_NULL

    /** True for [BUSY] and [LOCKED], read off the primary byte so extended codes match. */
    fun isContentionCode(resultCode: Int): Boolean {
        val primary = resultCode and PRIMARY_CODE_MASK
        return primary == BUSY || primary == LOCKED
    }

    /** True for [CORRUPT] and [NOTADB], on the same primary-byte rule as [isContentionCode]. */
    fun isCorruptionCode(resultCode: Int): Boolean {
        val primary = resultCode and PRIMARY_CODE_MASK
        return primary == CORRUPT || primary == NOTADB
    }
}

/**
 * [CurlewSQLite.load] could not load the library: this install is missing its native code.
 *
 * Still an [UnsatisfiedLinkError], so every catch written for that arrival takes it
 * unchanged; the subtype exists only for a caller that must not read an unresolved native
 * method - a defect in the release itself - as a broken install.
 */
class CurlewSQLiteLoadError(
    cause: UnsatisfiedLinkError,
) : UnsatisfiedLinkError(cause.message) {
    init {
        initCause(cause)
    }
}
