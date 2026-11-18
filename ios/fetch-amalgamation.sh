#!/bin/sh
# Fetches the SQLite amalgamation CurlewSQLite.lock.json pins and refuses it
# unless its SHA-256 matches, so nothing unverified reaches the compiler.
#
#   fetch-amalgamation.sh <url> <sha256> <directory>
#
# The podspec's prepare_command runs it from the pod's root with the pin's
# values, and leaves <directory>/sqlite3.{c,h} there for the pod to compile.
# Only those two files are extracted: they are the whole engine.
#
# With CURLEW_SQLITE_AMALGAMATION_ZIP set to a local copy of the pinned zip,
# that file is used in place of the download, for a build that runs offline
# or fetches its inputs itself. It is checked against the same SHA-256 and
# refused the same way, so it cannot stand in for any other archive.
set -eu

if [ "$#" -ne 3 ]; then
    echo "usage: fetch-amalgamation.sh <url> <sha256> <directory>" >&2
    exit 2
fi
url=$1
sha256=$2
directory=$3
archive="$directory.zip"

if [ -n "${CURLEW_SQLITE_AMALGAMATION_ZIP:-}" ]; then
    echo "fetch-amalgamation: using $CURLEW_SQLITE_AMALGAMATION_ZIP in place of $url"
    cp "$CURLEW_SQLITE_AMALGAMATION_ZIP" "$archive"
else
    echo "fetch-amalgamation: downloading $url"
    # sqlite.org is one host behind a CDN, so every failed attempt is retried: a
    # refused or reset connection as much as a 5xx. curl's plain --retry skips
    # the first two, and a 404 retried costs seconds. A digest mismatch below
    # is never retried. Each attempt has its own deadline, so a stalled
    # transfer fails into the next attempt instead of hanging the install. The
    # archive is under 3 MB.
    curl --fail --silent --show-error --location --retry 3 --retry-all-errors \
        --retry-delay 2 --connect-timeout 20 --max-time 120 \
        --output "$archive" "$url"
fi

if ! printf '%s  %s\n' "$sha256" "$archive" | shasum -a 256 -c - >/dev/null; then
    echo "fetch-amalgamation: $archive does not match the pinned SHA-256 $sha256" >&2
    shasum -a 256 "$archive" >&2
    rm -f "$archive"
    exit 1
fi

rm -rf "$directory"
unzip -q "$archive" "$directory/sqlite3.c" "$directory/sqlite3.h"
rm -f "$archive"
echo "fetch-amalgamation: verified $sha256 and extracted $directory/sqlite3.{c,h}"
