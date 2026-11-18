// Declarations for the subset of the SQLite C API the JNI shim calls.
//
// The engine is sqlite.org's prebuilt Android build, the libsqliteX.so inside
// sqlite-android-3540100.aar. That AAR ships the engine and its AOSP Java bindings but no
// sqlite3.h, so the prototypes below are declared here rather than included. They are
// copied from the pinned release's sqlite3.h and must be re-checked against it whenever
// the engine pin moves - a prototype that disagrees with the exported symbol is undefined
// behaviour the linker cannot catch, since C has no cross-object signature check.
//
// The pinned release is SQLite 3.54.1, source ID
// 2026-10-16 14:45:20 555e07ef33f69a0957d24d8e555b702464a131d6f1607f4c3c1e4551e8e6ca76.
// Every prototype here has been stable across SQLite's documented API-compatibility
// guarantee for many releases; the re-check is cheap insurance, not an expected source
// of change.
//
// The set is exactly the one the iOS shim (ios/CurlewSQLiteShim.h) declares, plus
// sqlite3_column_bytes - iOS reads a NUL-terminated C string where this side needs an
// explicit length to build a JVM byte array. Keeping the lists otherwise identical is
// what keeps code written against the two bindings structurally comparable.

#ifndef CURLEW_SQLITE3_H
#define CURLEW_SQLITE3_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;
typedef long long int sqlite3_int64;
typedef void (*sqlite3_destructor_type)(void *);

// Tells SQLite to copy a bound value before the bind call returns, so the caller owns
// its buffer's lifetime. Every text bind here uses it; see bind_text in the JNI shim.
#define SQLITE_TRANSIENT ((sqlite3_destructor_type) - 1)

#define SQLITE_OK 0
#define SQLITE_NOMEM 7
#define SQLITE_ROW 100
#define SQLITE_DONE 101

#define SQLITE_DBCONFIG_DEFENSIVE 1010

int sqlite3_open_v2(const char *filename, sqlite3 **database, int flags, const char *vfs);
int sqlite3_close_v2(sqlite3 *database);
int sqlite3_extended_result_codes(sqlite3 *database, int onoff);
int sqlite3_extended_errcode(sqlite3 *database);
const char *sqlite3_errmsg(sqlite3 *database);
int sqlite3_prepare_v2(
    sqlite3 *database,
    const char *sql,
    int byteCount,
    sqlite3_stmt **statement,
    const char **tail);
int sqlite3_finalize(sqlite3_stmt *statement);
int sqlite3_step(sqlite3_stmt *statement);
const unsigned char *sqlite3_column_text(sqlite3_stmt *statement, int column);
int sqlite3_column_bytes(sqlite3_stmt *statement, int column);
sqlite3_int64 sqlite3_column_int64(sqlite3_stmt *statement, int column);
int sqlite3_column_type(sqlite3_stmt *statement, int column);
int sqlite3_changes(sqlite3 *database);
int sqlite3_db_config(sqlite3 *database, int op, ...);
int sqlite3_bind_text(
    sqlite3_stmt *statement,
    int index,
    const char *value,
    int byteCount,
    sqlite3_destructor_type destructor);
int sqlite3_bind_int64(sqlite3_stmt *statement, int index, sqlite3_int64 value);
int sqlite3_bind_null(sqlite3_stmt *statement, int index);

#ifdef __cplusplus
}
#endif

#endif
