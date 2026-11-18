# Contributing

Thanks for your interest in improving `curlew-sqlite`. The bar for a change is
whether it keeps both platforms on the one pinned official SQLite release,
reached through bindings that pass every call straight through. Bug fixes, an
engine bump, a shim entrypoint that both platforms gain together, and clearer
docs are all welcome.

Keep changes on a branch and open a pull request that states the behavior
change and how you verified it.

## Maintainership and review

Curlew Labs maintains this project. Jeffrey Wall
([jeffwall-curlewlabs](https://github.com/jeffwall-curlewlabs)) reviews changes
and approves the releases that [need approval](#releasing). Use issues for
reproducible defects and proposals; use
[private vulnerability reporting](SECURITY.md) for security concerns.

## Before a large change

Open an issue describing the problem first. Three properties carry the design,
and the README's "Why it exists" records why each one holds: the engine is the
pinned sqlite.org release on both platforms, verified by digest before it is
compiled or packaged; the shims hold no policy, so the iOS and Android
entrypoint sets stay the same; and the identity check fails closed with no
fallback to an OS engine. A short discussion up front saves rework.

## Rules

- The shims stay pass-throughs: no SQL built, no handle cached, no state kept.
  A new entrypoint lands on iOS (`ios/CurlewSQLiteShim.{c,h}`) and Android
  (`curlew_sqlite_jni.c`, `curlew_sqlite3.h`, `CurlewSQLite.kt`) in the same
  change, with a test on each.
- Nothing the build compiles or packages is unverified: each download is pinned
  by URL and SHA-256 and refused on a mismatch.
- A change to the binding ships with a test that drives the real engine. A
  test that stubs the engine asserts only that the stub was called.
- Every dependency and tool is pinned to an exact version.
- Comments say why, not what.
- Tracked text is ASCII.
- The pod and module name `CurlewSQLite`, the `curlew_sqlite3_` prefix, the
  Kotlin package `com.curlewlabs.sqlite` and the native library name
  `libcurlewsqlite.so` are release contract.

## Local checks

iOS, on a Mac with Xcode (CocoaPods comes from the `Gemfile`):

```sh
bundle install
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 bundle exec pod lib lint --platforms=ios --allow-warnings
```

`--allow-warnings` is for `sqlite3.c`, which is compiled unchanged. CI fails on
a warning in any other file.

Android, with the Android SDK and Gradle 9.8.2 on `PATH`:

```sh
cd android
gradle :curlewsqlite:assembleRelease :curlewsqlite:testDebugUnitTest
gradle :curlewsqlite:connectedDebugAndroidTest   # with an emulator or device attached
```

## Releasing

A release is a fixed `vMAJOR.MINOR.PATCH` tag on a `main` commit and the
GitHub Release published on it, carrying `curlew-sqlite-<version>.tar.gz` and
`SHA256SUMS`. The podspec's `spec.version` names the release, so the release's
pull request sets it to the new version. A fix is a patch release. A new SQLite
release, or a new, changed or removed entrypoint or behavior, is a minor release
until 1.0.

A patch or minor release needs no approval: whoever cuts it follows the steps
below in order, on a `main` commit whose required checks have passed. A major
version, a 1.0, or a minor before 1.0 that changes an interface consumers use
needs the maintainer's explicit approval before its tag is pushed.

1. Merge the release's pull request with a merge commit, and wait for the `CI`
   and `CodeQL` runs on the merge commit on `main` to pass.
2. Tag that commit and push the tag. The fixed-version-tag ruleset blocks
   moving or deleting a pushed tag, so check the commit first.

   ```sh
   git fetch origin
   git tag -a v0.1.1 -m v0.1.1 <commit>
   git push origin v0.1.1
   ```

3. The tag starts `release.yml`. It fails unless the tagged commit is on
   `main`, runs CI on it again, builds the archive from the tag with
   `git archive`, and drafts the release with the archive and `SHA256SUMS`.
4. Check the draft against a local build: the archive matches `SHA256SUMS`,
   and it holds exactly what the tag holds. Compare contents, not the archive's
   digest, since gzip output can differ between git versions.

   ```sh
   v=0.1.1
   dir=$(mktemp -d)
   gh release download "v$v" -D "$dir/draft"
   (cd "$dir/draft" && shasum -a 256 -c SHA256SUMS)
   mkdir "$dir/released" "$dir/local"
   tar -xzf "$dir/draft/curlew-sqlite-$v.tar.gz" -C "$dir/released"
   git archive --prefix="curlew-sqlite-$v/" "v$v" | tar -x -C "$dir/local"
   diff -r "$dir/released" "$dir/local"
   ```

5. Replace the draft's placeholder notes with notes in the shape of the
   previous releases: the SQLite release the tag pins, then what was fixed,
   added or removed, each entry naming its issue.

   ```sh
   gh release edit "v$v" --notes-file notes.md
   ```

6. Publish the draft:

   ```sh
   gh release edit "v$v" --draft=false
   ```

   Release immutability is on, so publishing locks the tag and both assets for
   good. A mistake found after this ships in the next patch release.

## License

By submitting a contribution for inclusion in this project, you agree that it
is licensed under the MIT and Apache-2.0 licenses, as the README's License
section describes, without any additional terms or conditions.
