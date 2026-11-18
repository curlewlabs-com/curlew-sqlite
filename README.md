# curlew-sqlite

One pinned release of the official SQLite engine for iOS and Android, behind the
same small C entrypoint set on both, with a runtime check that the engine a
process actually opened is the pinned one.

## What it is

- **iOS:** a CocoaPods pod, `CurlewSQLite`. It compiles sqlite.org's
  amalgamation for SQLite 3.54.1, fetched by URL and refused before compiling
  unless its SHA-256 matches the pin in
  [`CurlewSQLite.lock.json`](CurlewSQLite.lock.json). It also compiles a C shim
  of 16 `curlew_sqlite3_*` pass-through functions and the Swift identity check
  `CurlewSQLiteProviderIdentity`, all in one module named `CurlewSQLite`.
- **Android:** a Gradle library module, `android/curlewsqlite`, package
  `com.curlewlabs.sqlite`. It does **not** compile SQLite. It downloads
  sqlite.org's prebuilt Android AAR (`sqlite-android-3540100.aar`), refuses it
  unless its SHA-256 matches the pin in its `build.gradle.kts`, and packages the
  AAR's `libsqliteX.so` for each ABI unchanged. The JNI shim,
  `libcurlewsqlite.so`, links against those same files and is fronted by the
  Kotlin object `CurlewSQLite`. The identity check is
  `AndroidBundledSQLiteVersionGuard` and `AndroidBundledSQLiteSourceIdGuard`.

Both platforms run the same SQLite release, identified by its source ID:

```text
2026-10-16 14:45:20 555e07ef33f69a0957d24d8e555b702464a131d6f1607f4c3c1e4551e8e6ca76
```

They are not the same binary. On iOS the pod compiles the amalgamation with the
definitions the pin lists. On Android the engine is the one sqlite.org built,
with the compile options sqlite.org chose.

## Why it exists

The SQLite an app gets by default is whatever the device's OS ships: a
different version on every OS release, and often well behind the current one.
An app that needs a particular release, for example one with the fix for
SQLite's [WAL-reset bug](https://www.sqlite.org/wal.html#the_wal_reset_bug),
which affects versions through 3.51.2, has to bring its own engine.

On iOS that means compiling the amalgamation yourself.

On Android, sqlite.org already publishes a prebuilt engine with AOSP-derived
Java bindings. Those bindings do not pass SQL through: `SQLiteSession`
classifies a statement by its first three characters and runs every `BEG...`
statement as `BEGIN EXCLUSIVE`. So a `BEGIN IMMEDIATE`, which should take write
intent and still admit readers, instead takes the exclusive lock and keeps every
reader out for the full busy timeout. This library uses sqlite.org's engine but
replaces the Java layer with a thin JNI shim that passes each call straight
through. It also carries text as UTF-8 byte arrays, because JNI's own string
calls use modified UTF-8, which corrupts characters outside the Basic
Multilingual Plane (emoji, for one).

Bringing your own engine raises a second problem: being sure it is the engine
that actually runs. A process that links the OS library by mistake, or picks up
a repackaged build, still reports a plausible version. The identity check
compares `sqlite_source_id()`, which names the exact upstream check-in, against
the pinned value, and also requires a minimum version. Run it on every open,
before touching any table. A mismatch is a hard failure: there is no fallback to
the OS engine.

The C entrypoints are the same on both platforms. The only addition is
`sqlite3_column_bytes` on Android, which is needed to build a JVM byte array.
That keeps storage code written against one platform readable against the
other.

## How to use it

### iOS (CocoaPods)

```ruby
pod 'CurlewSQLite', :git => 'https://github.com/curlewlabs-com/curlew-sqlite.git', :tag => 'v0.1.0'
```

CocoaPods checks out the tag, then the pod's `prepare_command` runs
[`ios/fetch-amalgamation.sh`](ios/fetch-amalgamation.sh). That script downloads
the pinned zip from sqlite.org, verifies its SHA-256, and extracts `sqlite3.c`
and `sqlite3.h`. The pod supports iOS 15.6 and later. It is compiled with
`SQLITE_THREADSAFE=1`, `SQLITE_ENABLE_LOCKING_STYLE=0` (POSIX advisory locks in
the app sandbox) and `HAVE_USLEEP=1`.

SwiftPM is not provided. The pod is the only iOS packaging.

```swift
import CurlewSQLite

var database: OpaquePointer?
let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
guard curlew_sqlite3_open_v2(path, &database, flags, nil) == SQLITE_OK else { /* ... */ }

// Before touching any table: read the engine's identity on this connection
// and check it.
//   SELECT sqlite_version(), sqlite_source_id()
try CurlewSQLiteProviderIdentity.validateRuntimeIdentity(version: version, sourceID: sourceID)
```

Call the `curlew_sqlite3_*` functions rather than the bare `sqlite3_*` names.
Those names exist only in this pod, so a call to one cannot bind to the OS's
`libsqlite3`, even in an app that links it too. `sqlite3.h` is public for its
constants and types. The full list of functions is
in [`ios/CurlewSQLiteShim.h`](ios/CurlewSQLiteShim.h).

Every process that opens a database (an app extension, for example) should run
the check on its own connection. Each process loads the engine separately, so a
check that only the host app runs does not cover the others.

### Android (source module)

Check out the tag beside your project, for example as a git submodule, and
include the module:

```kotlin
// settings.gradle.kts
include(":curlewsqlite")
project(":curlewsqlite").projectDir = file("third_party/curlew-sqlite/android/curlewsqlite")

// app/build.gradle.kts
dependencies {
    implementation(project(":curlewsqlite"))
}
```

Your build supplies the Android Gradle Plugin. The module is built and tested
with AGP 9.5.0, Gradle 9.8.2, NDK 28.2.13676358 (pinned in the module),
`compileSdk` 36 and `minSdk` 24.

At build time the module downloads
`https://www.sqlite.org/2026/sqlite-android-3540100.aar` and fails unless its
SHA-256 is the pinned
`45982177674e6fde0f5ec5c9387335a1a5c3e351ee986cbb8a42fc6d721660de`. It then
packages only the AAR's native engine: the AOSP Java bindings never reach your
classpath. Do not also depend on that AAR yourself: both would supply
`libsqliteX.so` for each ABI.

The module ships its own R8 keep rule, so you write none. If the binding class
were renamed, the shim would abort at `System.loadLibrary` by design (see the
comment at the top of `curlew_sqlite_jni.c`).

```kotlin
import com.curlewlabs.sqlite.AndroidBundledSQLiteSourceIdGuard
import com.curlewlabs.sqlite.AndroidBundledSQLiteVersionGuard
import com.curlewlabs.sqlite.CurlewSQLite

CurlewSQLite.load()
val handle = LongArray(1)
val result = CurlewSQLite.openV2(path, CurlewSQLite.OPEN_READWRITE or CurlewSQLite.OPEN_CREATE, handle)
// Close handle[0] even when result != OK: SQLite returns a handle for a failed
// open so that errmsg can be read from it.

// Before touching any table: read sqlite_version() and sqlite_source_id() on
// this connection and check them.
AndroidBundledSQLiteVersionGuard.requireSupported(version)
AndroidBundledSQLiteSourceIdGuard.requireExpected(sourceId)
```

Handles are raw pointers carried as `Long`. Passing a stale or finalized handle
back is a use-after-free, not an exception, exactly as it is in C.

## What it does not do

- It builds no SQL, caches no handle, and holds no state. Statement lifetime is
  the caller's.
- It exposes only the entrypoints listed in the shims. Anything else (blobs,
  doubles, busy handlers, user functions) needs a new shim function on both
  platforms.
- It has no configuration. The compile definitions, the ABI set, the version
  floor and the source ID all follow the pin. Using a different engine means a
  different release of this repository, or a fork.
- It provides no prebuilt binaries and no SwiftPM package.

## Moving the engine

A new SQLite release moves both platforms in one change:

- `CurlewSQLite.lock.json`: the amalgamation URL, its SHA-256 and the archive's
  top-level directory;
- `android/curlewsqlite/build.gradle.kts`: the AAR URL and its SHA-256;
- the version floor and source ID in `ios/CurlewSQLiteProviderIdentity.swift`
  and `android/curlewsqlite/src/main/kotlin/com/curlewlabs/sqlite/BundledSQLiteIdentity.kt`;
- the prototypes in `android/curlewsqlite/src/main/cpp/curlew_sqlite3.h`,
  re-checked against the new release's `sqlite3.h`, as that header's comment
  requires.

Check each download against the SHA3-256 that
[sqlite.org's download page](https://www.sqlite.org/download.html) publishes
before recording its SHA-256. The tests on both platforms read the engine's
reported version and source ID, so a pin moved without the constants fails CI.

## Releases

Releases are fixed `vMAJOR.MINOR.PATCH` tags. The tag is what a Podfile names
and what an Android checkout uses. The SQLite release each tag pins is in that
tag's `CurlewSQLite.lock.json`. Moving to a new SQLite release is a minor
release while the project is 0.x.
[CONTRIBUTING.md](CONTRIBUTING.md#releasing) has the steps.

## License

Licensed under either of

- Apache License, Version 2.0 ([LICENSE-APACHE](LICENSE-APACHE))
- MIT license ([LICENSE-MIT](LICENSE-MIT))

at your option.

SQLite itself is in the public domain. This repository does not contain it: the
build downloads it from sqlite.org.

### Contribution

By submitting a contribution for inclusion in this project, you agree that it
is dual licensed as above, without any additional terms or conditions.
