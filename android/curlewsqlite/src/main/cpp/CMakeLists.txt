# Builds libcurlewsqlite.so - the JNI shim in curlew_sqlite_jni.c - against sqlite.org's
# prebuilt engine, the libsqliteX.so per ABI inside sqlite-android-3540100.aar.
#
# The engine is NOT compiled here. Linking against sqlite.org's binary rather than
# compiling an amalgamation keeps the shipped engine bit-identical to the AAR whose
# SHA-256 build.gradle.kts pins, so this project owns the layer above the engine and
# nothing else. `extractSqliteJniLibs` in build.gradle.kts verifies the AAR, unpacks its
# jni/ tree to SQLITE_JNI_DIR, and is wired to run before this configures.
#
# The same libsqliteX.so files are what the library packages, so the shim links against
# the very copy that ships - there is no second engine.

cmake_minimum_required(VERSION 3.22.1)

project(curlewsqlite C)

if(NOT DEFINED SQLITE_JNI_DIR)
    message(FATAL_ERROR "SQLITE_JNI_DIR was not passed from Gradle")
endif()

set(CURLEW_SQLITE_ENGINE "${SQLITE_JNI_DIR}/${ANDROID_ABI}/libsqliteX.so")
if(NOT EXISTS "${CURLEW_SQLITE_ENGINE}")
    message(FATAL_ERROR "Bundled SQLite engine missing at ${CURLEW_SQLITE_ENGINE}")
endif()

add_library(curlewsqlite SHARED curlew_sqlite_jni.c)

# -Werror matches the Kotlin compiler's posture in build.gradle.kts. This file binds
# a hand-declared C API to a JVM boundary, where an ignored warning (an implicit
# declaration, a pointer-size mismatch) is the shape a real defect takes.
target_compile_options(curlewsqlite PRIVATE -Wall -Wextra -Werror)

# --no-undefined makes an unresolved sqlite3_* symbol a link error here rather than a
# dlopen failure on a user's device. That is the whole reason the engine is linked
# instead of dlsym'd: the prototype set in curlew_sqlite3.h is checked against the
# engine's real exports at build time, on every ABI.
target_link_options(curlewsqlite PRIVATE -Wl,--no-undefined)

# Linked by flag rather than as an IMPORTED target, and that is a decision about
# packaging rather than a style choice. An IMPORTED shared library is a runtime file of
# the target that links it, so AGP syncs it into the external-native-build output
# directory and packages it from there - giving libsqliteX.so a packaging owner in the
# library's jniLibs and another in this build, which is the shape that becomes a
# duplicate input rather than a silent dedupe on an AGP bump.
#
# -L plus -l is a pure link-time reference: the linker resolves libsqliteX.so here, and
# nothing in the CMake project claims it as an artifact. The jniLibs directory stays the
# sole packaging owner.
target_link_directories(curlewsqlite PRIVATE "${SQLITE_JNI_DIR}/${ANDROID_ABI}")
target_link_libraries(curlewsqlite sqliteX)
