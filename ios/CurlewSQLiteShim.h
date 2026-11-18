// Declarations for the CurlewSQLite shim's C wrappers over SQLite.

#ifndef CURLEW_SQLITE_SHIM_H
#define CURLEW_SQLITE_SHIM_H

#include "sqlite3.h"

#ifdef __cplusplus
extern "C" {
#endif

int curlew_sqlite3_open_v2(
    const char *filename,
    sqlite3 **database,
    int flags,
    const char *vfs
);
int curlew_sqlite3_close_v2(sqlite3 *database);
int curlew_sqlite3_extended_result_codes(sqlite3 *database, int enabled);
int curlew_sqlite3_extended_errcode(sqlite3 *database);
const char *curlew_sqlite3_errmsg(sqlite3 *database);
int curlew_sqlite3_prepare_v2(
    sqlite3 *database,
    const char *sql,
    int byteCount,
    sqlite3_stmt **statement,
    const char **tail
);
int curlew_sqlite3_finalize(sqlite3_stmt *statement);
int curlew_sqlite3_step(sqlite3_stmt *statement);
const unsigned char *curlew_sqlite3_column_text(sqlite3_stmt *statement, int column);
sqlite3_int64 curlew_sqlite3_column_int64(sqlite3_stmt *statement, int column);
int curlew_sqlite3_column_type(sqlite3_stmt *statement, int column);
int curlew_sqlite3_changes(sqlite3 *database);
int curlew_sqlite3_db_config_defensive(
    sqlite3 *database,
    int enabled,
    int *current
);
int curlew_sqlite3_bind_text(
    sqlite3_stmt *statement,
    int index,
    const char *value,
    int byteCount,
    sqlite3_destructor_type destructor
);
int curlew_sqlite3_bind_int64(sqlite3_stmt *statement, int index, sqlite3_int64 value);
int curlew_sqlite3_bind_null(sqlite3_stmt *statement, int index);

#ifdef __cplusplus
}
#endif

#endif
