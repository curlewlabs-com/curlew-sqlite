// The CurlewSQLite shim: C wrappers over the SQLite calls the native local store
// makes.

#include "CurlewSQLiteShim.h"

int curlew_sqlite3_open_v2(
    const char *filename,
    sqlite3 **database,
    int flags,
    const char *vfs
) {
    return sqlite3_open_v2(filename, database, flags, vfs);
}

int curlew_sqlite3_close_v2(sqlite3 *database) {
    return sqlite3_close_v2(database);
}

int curlew_sqlite3_extended_result_codes(sqlite3 *database, int enabled) {
    return sqlite3_extended_result_codes(database, enabled);
}

int curlew_sqlite3_extended_errcode(sqlite3 *database) {
    return sqlite3_extended_errcode(database);
}

const char *curlew_sqlite3_errmsg(sqlite3 *database) {
    return sqlite3_errmsg(database);
}

int curlew_sqlite3_prepare_v2(
    sqlite3 *database,
    const char *sql,
    int byteCount,
    sqlite3_stmt **statement,
    const char **tail
) {
    return sqlite3_prepare_v2(database, sql, byteCount, statement, tail);
}

int curlew_sqlite3_finalize(sqlite3_stmt *statement) {
    return sqlite3_finalize(statement);
}

int curlew_sqlite3_step(sqlite3_stmt *statement) {
    return sqlite3_step(statement);
}

const unsigned char *curlew_sqlite3_column_text(sqlite3_stmt *statement, int column) {
    return sqlite3_column_text(statement, column);
}

sqlite3_int64 curlew_sqlite3_column_int64(sqlite3_stmt *statement, int column) {
    return sqlite3_column_int64(statement, column);
}

// The stored type of a column in the current row, which is what separates a SQL NULL
// from a stored zero: sqlite3_column_int64 returns 0 for NULL and for zero. A nullable TEXT column
// needs no equivalent - sqlite3_column_text already returns NULL - so this exists for
// the integer half alone. Call it BEFORE sqlite3_column_text on the same column: that
// call may convert the value's encoding and rewrite the type this reports.
int curlew_sqlite3_column_type(sqlite3_stmt *statement, int column) {
    return sqlite3_column_type(statement, column);
}

int curlew_sqlite3_changes(sqlite3 *database) {
    return sqlite3_changes(database);
}

int curlew_sqlite3_db_config_defensive(
    sqlite3 *database,
    int enabled,
    int *current
) {
    return sqlite3_db_config(database, SQLITE_DBCONFIG_DEFENSIVE, enabled, current);
}

int curlew_sqlite3_bind_text(
    sqlite3_stmt *statement,
    int index,
    const char *value,
    int byteCount,
    sqlite3_destructor_type destructor
) {
    return sqlite3_bind_text(statement, index, value, byteCount, destructor);
}

int curlew_sqlite3_bind_int64(sqlite3_stmt *statement, int index, sqlite3_int64 value) {
    return sqlite3_bind_int64(statement, index, value);
}

int curlew_sqlite3_bind_null(sqlite3_stmt *statement, int index) {
    return sqlite3_bind_null(statement, index);
}
