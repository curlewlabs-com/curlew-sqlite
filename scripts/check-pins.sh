#!/bin/sh
# Fails unless the iOS and Android pins name the same SQLite release.
#
# The two platforms pin their engines separately: the amalgamation in
# CurlewSQLite.lock.json, the AAR in android/curlewsqlite/build.gradle.kts,
# and each its own version floor and source ID. Each platform's tests check
# its engine against its own constants, so a bump that moved one platform and
# its constants would pass them. This compares the two sides with each other:
# the archives' release number, the version floor, which that number encodes
# (3.54.1 is 3540100), and the source ID.
#
# Each value is read from the one line that declares it. A file with no such
# line, or more than one, fails rather than compares nothing.
set -eu

cd "$(dirname "$0")/.."

lock=CurlewSQLite.lock.json
gradle=android/curlewsqlite/build.gradle.kts
swift=ios/CurlewSQLiteProviderIdentity.swift
kotlin=android/curlewsqlite/src/main/kotlin/com/curlewlabs/sqlite/BundledSQLiteIdentity.kt

failed=0

# The single value `sed -n <script>` prints from a file, or a failure naming it.
one() {
    found=$(sed -n "$2" "$1")
    if [ "$(printf '%s\n' "$found" | grep -c .)" -ne 1 ]; then
        echo "check-pins: $1 does not declare $3 exactly once" >&2
        exit 1
    fi
    printf '%s\n' "$found"
}

amalgamation=$(one "$lock" 's/.*"strip_prefix": "sqlite-amalgamation-\([0-9]*\)".*/\1/p' "the amalgamation's release")
aar=$(one "$gradle" 's/^val sqliteAndroidUrl = ".*sqlite-android-\([0-9]*\)\.aar"$/\1/p' "the AAR's release")
swift_floor=$(one "$swift" 's/.*minimumRuntimeVersion = "\([0-9.]*\)"$/\1/p' "the version floor")
kotlin_floor=$(one "$kotlin" 's/.*minimumVersion = SQLiteEngineVersion(\([0-9]*\), \([0-9]*\), \([0-9]*\))$/\1.\2.\3/p' "the version floor")
swift_id=$(one "$swift" '/expectedSourceID = """/{n;s/^ *//p;}' "the source ID")
kotlin_id=$(one "$kotlin" '/EXPECTED_SOURCE_ID =$/{n;s/^ *"\(.*\)"$/\1/p;}' "the source ID")

# The release number SQLite's download names carry: X.Y.Z as X*1000000 +
# Y*10000 + Z*100.
encoded=$(printf '%s\n' "$swift_floor" | awk -F. '{ printf "%d%02d%02d00\n", $1, $2, $3 }')

compare() {
    if [ "$2" = "$3" ]; then
        echo "check-pins: $1: $2"
    else
        echo "check-pins: $1 differ: $2 and $3" >&2
        failed=1
    fi
}

compare "the amalgamation and the AAR releases" "$amalgamation" "$aar"
compare "the Swift and Kotlin version floors" "$swift_floor" "$kotlin_floor"
compare "the archives' release and the version floor" "$amalgamation" "$encoded"
compare "the Swift and Kotlin source IDs" "$swift_id" "$kotlin_id"

exit "$failed"
