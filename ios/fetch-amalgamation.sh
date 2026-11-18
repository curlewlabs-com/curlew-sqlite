#!/bin/sh
# Fetches the SQLite amalgamation CurlewSQLite.lock.json pins and refuses it
# unless its SHA-256 matches, so nothing unverified reaches the compiler.
#
#   fetch-amalgamation.sh <url> <sha256> <directory>
#
# The podspec's prepare_command runs it from the pod's root with the pin's
# values, and leaves <directory>/sqlite3.{c,h} there for the pod to compile.
# Only those two files are extracted: they are the whole engine.
set -eu

if [ "$#" -ne 3 ]; then
    echo "usage: fetch-amalgamation.sh <url> <sha256> <directory>" >&2
    exit 2
fi
url=$1
sha256=$2
directory=$3
archive="$directory.zip"

echo "fetch-amalgamation: downloading $url"
# sqlite.org is one host behind a CDN; a dropped connection or a 5xx is worth
# a retry, a 404 is not and fails at once.
curl --fail --silent --show-error --location --retry 3 --retry-delay 2 \
    --output "$archive" "$url"

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
