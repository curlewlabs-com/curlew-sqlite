// The JNI shim behind com.curlewlabs.sqlite.CurlewSQLite.
//
// Every function here is a pass-through onto one sqlite3_* entrypoint, matching the iOS
// shim (ios/CurlewSQLiteShim.c) one for one. No SQL is built here, no handle is cached, no
// state is kept: the caller owns statement lifetime, on Android as on iOS. Keeping this
// file free of policy is what lets code written against one platform's binding be read
// against the other's.
//
// It exists in place of the AAR's AOSP Java bindings (org.sqlite.database.*), which
// rewrite the SQL they are given: any statement whose first three characters are "BEG" -
// including BEGIN IMMEDIATE - is executed as BEGIN EXCLUSIVE. The engine itself is not
// the problem, and it is used unchanged; only the layer above it is replaced.
//
// == Text crosses this boundary as UTF-8 bytes, never as jstring ==
//
// NewStringUTF and GetStringUTFChars speak modified UTF-8, in which a non-BMP character
// (an emoji in a user's name, say) is encoded as a surrogate pair of three-byte sequences
// rather than the one four-byte sequence standard UTF-8 uses. SQLite stores and returns
// standard UTF-8. Handing either function the other's encoding corrupts exactly those
// characters and nothing else, which is the kind of defect that survives every ASCII test
// fixture and surfaces as mojibake in stored data. So the boundary carries byte arrays and
// the Kotlin side does the String <-> UTF-8 conversion, where the platform's own decoder
// handles the full range.
//
// == Binding failures abort at load, deliberately ==
//
// JNI_OnLoad resolves everything up front and calls FatalError on any miss, rather than
// returning JNI_ERR. Returning an error would surface as a catchable UnsatisfiedLinkError
// on whichever thread first touched the database - and a caller that catches Throwable
// around its storage work would then degrade silently into a permanently dead store with
// no crash to show for it. FatalError aborts the process instead, at load, where a
// release check that launches the app sees it and a native crash reporter records it.
//
// The realistic cause is release-only: R8 renaming the Kotlin object below breaks the
// FindClass lookup here, and instrumented tests usually run an unminified debug build, so
// nothing but a release build can observe it. The keep rule in consumer-rules.pro is the
// primary control; this is what makes its absence loud.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "curlew_sqlite3.h"

#define CURLEW_SQLITE_CLASS "com/curlewlabs/sqlite/CurlewSQLite"

static sqlite3 *asDatabase(jlong handle) {
    return (sqlite3 *) (intptr_t) handle;
}

static sqlite3_stmt *asStatement(jlong handle) {
    return (sqlite3_stmt *) (intptr_t) handle;
}

// Copies `length` bytes into a fresh JVM byte array. Returns NULL with a pending
// OutOfMemoryError if the allocation fails, which the caller propagates by returning
// NULL - the Kotlin side sees the exception, not a truncated value.
static jbyteArray toByteArray(JNIEnv *env, const void *bytes, jsize length) {
    jbyteArray array = (*env)->NewByteArray(env, length);
    if (array == NULL) {
        return NULL;
    }
    if (length > 0) {
        (*env)->SetByteArrayRegion(env, array, 0, length, (const jbyte *) bytes);
    }
    return array;
}

// Zeroes the caller's output slot before anything can fail, so every return path leaves
// either the new handle or 0 there and never a handle from an earlier call. A caller that
// reuses one array across calls would otherwise close or finalize that stale handle a
// second time on a failed allocation. Returns false with a pending exception when the
// array has no slot, which the caller propagates.
static int clearHandle(JNIEnv *env, jlongArray out) {
    jlong none = 0;
    (*env)->SetLongArrayRegion(env, out, 0, 1, &none);
    return !(*env)->ExceptionCheck(env);
}

static jint openV2(
    JNIEnv *env,
    jobject thiz,
    jbyteArray pathUtf8,
    jint flags,
    jlongArray outHandle) {
    (void) thiz;
    if (!clearHandle(env, outHandle)) {
        return SQLITE_MISUSE;
    }
    jsize length = (*env)->GetArrayLength(env, pathUtf8);
    // sqlite3_open_v2 takes a NUL-terminated path; the JVM array is not.
    char *path = malloc((size_t) length + 1);
    if (path == NULL) {
        return SQLITE_NOMEM;
    }
    (*env)->GetByteArrayRegion(env, pathUtf8, 0, length, (jbyte *) path);
    path[length] = '\0';

    sqlite3 *database = NULL;
    int result = sqlite3_open_v2(path, &database, flags, NULL);
    free(path);

    // Written back even on failure: sqlite3_open_v2 hands back a usable handle for a
    // failed open precisely so the caller can read sqlite3_errmsg off it, and the Kotlin
    // side closes it.
    jlong handle = (jlong) (intptr_t) database;
    (*env)->SetLongArrayRegion(env, outHandle, 0, 1, &handle);
    return result;
}

static jint closeV2(JNIEnv *env, jobject thiz, jlong database) {
    (void) env;
    (void) thiz;
    return sqlite3_close_v2(asDatabase(database));
}

static jint extendedResultCodes(JNIEnv *env, jobject thiz, jlong database, jint enabled) {
    (void) env;
    (void) thiz;
    return sqlite3_extended_result_codes(asDatabase(database), enabled);
}

static jint extendedErrcode(JNIEnv *env, jobject thiz, jlong database) {
    (void) env;
    (void) thiz;
    return sqlite3_extended_errcode(asDatabase(database));
}

static jbyteArray errmsg(JNIEnv *env, jobject thiz, jlong database) {
    (void) thiz;
    const char *message = sqlite3_errmsg(asDatabase(database));
    if (message == NULL) {
        return NULL;
    }
    return toByteArray(env, message, (jsize) strlen(message));
}

static jint prepareV2(
    JNIEnv *env,
    jobject thiz,
    jlong database,
    jbyteArray sqlUtf8,
    jlongArray outStatement) {
    (void) thiz;
    if (!clearHandle(env, outStatement)) {
        return SQLITE_MISUSE;
    }
    jsize length = (*env)->GetArrayLength(env, sqlUtf8);
    char *sql = malloc((size_t) length + 1);
    if (sql == NULL) {
        return SQLITE_NOMEM;
    }
    (*env)->GetByteArrayRegion(env, sqlUtf8, 0, length, (jbyte *) sql);
    sql[length] = '\0';

    sqlite3_stmt *statement = NULL;
    int result = sqlite3_prepare_v2(asDatabase(database), sql, length, &statement, NULL);
    free(sql);

    jlong handle = (jlong) (intptr_t) statement;
    (*env)->SetLongArrayRegion(env, outStatement, 0, 1, &handle);
    return result;
}

static jint finalizeStatement(JNIEnv *env, jobject thiz, jlong statement) {
    (void) env;
    (void) thiz;
    return sqlite3_finalize(asStatement(statement));
}

static jint step(JNIEnv *env, jobject thiz, jlong statement) {
    (void) env;
    (void) thiz;
    return sqlite3_step(asStatement(statement));
}

static jbyteArray columnText(JNIEnv *env, jobject thiz, jlong statement, jint column) {
    (void) thiz;
    sqlite3_stmt *handle = asStatement(statement);
    const unsigned char *value = sqlite3_column_text(handle, column);
    if (value == NULL) {
        // A SQL NULL, which the Kotlin side maps to a null String. Distinguishable from
        // the empty string, which returns a zero-length array.
        return NULL;
    }
    // Read after column_text, per SQLite's documented ordering: column_text can convert
    // the value's encoding, and column_bytes must describe the post-conversion bytes.
    jsize length = (jsize) sqlite3_column_bytes(handle, column);
    return toByteArray(env, value, length);
}

static jlong columnInt64(JNIEnv *env, jobject thiz, jlong statement, jint column) {
    (void) env;
    (void) thiz;
    return (jlong) sqlite3_column_int64(asStatement(statement), column);
}

// The stored type of a column in the current row, which is what separates a SQL NULL from
// a stored zero: columnInt64 above returns 0 for NULL and for zero. columnText needs no equivalent - it
// already answers NULL - so this exists for the integer half alone. Call it BEFORE
// sqlite3_column_text on the same column, whose encoding conversion may rewrite the type
// this reports. The iOS twin is curlew_sqlite3_column_type.
static jint columnType(JNIEnv *env, jobject thiz, jlong statement, jint column) {
    (void) env;
    (void) thiz;
    return (jint) sqlite3_column_type(asStatement(statement), column);
}

static jint changes(JNIEnv *env, jobject thiz, jlong database) {
    (void) env;
    (void) thiz;
    return sqlite3_changes(asDatabase(database));
}

// SQLITE_DBCONFIG_DEFENSIVE's verb form: sets the flag and reports back what the engine
// actually holds, so the caller can fail the open when the setting did not take. The
// varargs signature is why this cannot be a bare pass-through like its neighbors.
static jint dbConfigDefensive(
    JNIEnv *env,
    jobject thiz,
    jlong database,
    jint enabled,
    jintArray outCurrent) {
    (void) thiz;
    int current = -1;
    int result = sqlite3_db_config(
        asDatabase(database), SQLITE_DBCONFIG_DEFENSIVE, enabled, &current);
    jint reported = (jint) current;
    (*env)->SetIntArrayRegion(env, outCurrent, 0, 1, &reported);
    return result;
}

static jint bindText(
    JNIEnv *env,
    jobject thiz,
    jlong statement,
    jint index,
    jbyteArray valueUtf8) {
    (void) thiz;
    jsize length = (*env)->GetArrayLength(env, valueUtf8);
    jbyte *value = (*env)->GetByteArrayElements(env, valueUtf8, NULL);
    if (value == NULL) {
        return SQLITE_NOMEM;
    }
    // SQLITE_TRANSIENT: SQLite copies the bytes before this returns, so releasing the
    // JVM array immediately below is safe.
    int result = sqlite3_bind_text(
        asStatement(statement), index, (const char *) value, length, SQLITE_TRANSIENT);
    (*env)->ReleaseByteArrayElements(env, valueUtf8, value, JNI_ABORT);
    return result;
}

static jint bindInt64(JNIEnv *env, jobject thiz, jlong statement, jint index, jlong value) {
    (void) env;
    (void) thiz;
    return sqlite3_bind_int64(asStatement(statement), index, (sqlite3_int64) value);
}

static jint bindNull(JNIEnv *env, jobject thiz, jlong statement, jint index) {
    (void) env;
    (void) thiz;
    return sqlite3_bind_null(asStatement(statement), index);
}

// Bound against the Kotlin `object CurlewSQLite`, whose members are instance methods -
// hence the `jobject thiz` second parameter above rather than a `jclass`. Nothing reads
// it; the shim holds no per-instance state, and an `object` has exactly one instance.
//
// Every signature here must match its `external fun` declaration exactly. A mismatch is
// caught by RegisterNatives at load and aborts, which is the point: the alternative -
// implicit `Java_pkg_Class_method` name mangling - fails at the first call instead, on a
// path that catches Throwable.
static const JNINativeMethod METHODS[] = {
    {"nativeOpenV2", "([BI[J)I", (void *) openV2},
    {"nativeCloseV2", "(J)I", (void *) closeV2},
    {"nativeExtendedResultCodes", "(JI)I", (void *) extendedResultCodes},
    {"nativeExtendedErrcode", "(J)I", (void *) extendedErrcode},
    {"nativeErrmsg", "(J)[B", (void *) errmsg},
    {"nativePrepareV2", "(J[B[J)I", (void *) prepareV2},
    {"nativeFinalize", "(J)I", (void *) finalizeStatement},
    {"nativeStep", "(J)I", (void *) step},
    {"nativeColumnText", "(JI)[B", (void *) columnText},
    {"nativeColumnInt64", "(JI)J", (void *) columnInt64},
    {"nativeColumnType", "(JI)I", (void *) columnType},
    {"nativeChanges", "(J)I", (void *) changes},
    {"nativeDbConfigDefensive", "(JI[I)I", (void *) dbConfigDefensive},
    {"nativeBindText", "(JI[B)I", (void *) bindText},
    {"nativeBindInt64", "(JIJ)I", (void *) bindInt64},
    {"nativeBindNull", "(JI)I", (void *) bindNull},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        // No env means no way to raise a Java-visible error, so this is already the
        // abort path whether we take it deliberately or not.
        return JNI_ERR;
    }
    jclass clazz = (*env)->FindClass(env, CURLEW_SQLITE_CLASS);
    if (clazz == NULL) {
        (*env)->FatalError(
            env,
            "CurlewSQLite class not found - the JNI-bound Kotlin object was renamed or "
            "stripped; see the keep rule in consumer-rules.pro");
    }
    if ((*env)->RegisterNatives(
            env, clazz, METHODS, sizeof(METHODS) / sizeof(METHODS[0])) != JNI_OK) {
        (*env)->FatalError(
            env,
            "CurlewSQLite native registration failed - a declared external fun does not "
            "match the shim's method table");
    }
    (*env)->DeleteLocalRef(env, clazz);
    return JNI_VERSION_1_6;
}
