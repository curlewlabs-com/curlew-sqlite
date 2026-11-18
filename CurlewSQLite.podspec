# frozen_string_literal: true

# The CurlewSQLite pod: the official SQLite amalgamation at the release
# CurlewSQLite.lock.json pins, the C shim over it, and the runtime identity
# check a caller runs on every open.

require "json"

# The pin is plain JSON so the archive, its digest and the compile definitions
# can be read and updated without evaluating this Ruby.
sqlite_pin = JSON.parse(File.read(File.join(__dir__, "CurlewSQLite.lock.json")))
sqlite_archive = sqlite_pin.fetch("archive")
amalgamation = sqlite_archive.fetch("strip_prefix")

Pod::Spec.new do |spec|
  spec.name = "CurlewSQLite"
  spec.version = "0.2.0"
  spec.summary = "The official SQLite amalgamation at one pinned release, with a C shim and a runtime identity check."
  spec.homepage = "https://github.com/curlewlabs-com/curlew-sqlite"
  spec.license = { type: "MIT OR Apache-2.0", file: "LICENSE-MIT" }
  spec.author = "Curlew Labs"
  spec.source = { git: "https://github.com/curlewlabs-com/curlew-sqlite.git", tag: "v#{spec.version}" }
  spec.platform = :ios, "15.6"
  spec.swift_versions = ["6.0"]
  spec.module_name = "CurlewSQLite"

  # The amalgamation is not committed. CocoaPods runs this after it checks the
  # tag out, and the script refuses an archive whose SHA-256 is not the pin's,
  # so only the pinned bytes are ever compiled.
  spec.prepare_command = [
    "/bin/sh", "ios/fetch-amalgamation.sh",
    sqlite_archive.fetch("url"), sqlite_archive.fetch("sha256"), amalgamation
  ].join(" ")

  spec.source_files = [
    "#{amalgamation}/sqlite3.{c,h}",
    "ios/CurlewSQLiteShim.{c,h}",
    "ios/CurlewSQLiteProviderIdentity.swift"
  ]
  spec.public_header_files = [
    "#{amalgamation}/sqlite3.h",
    "ios/CurlewSQLiteShim.h"
  ]
  spec.requires_arc = false

  # The pin's compile definitions: serialized mode (SQLITE_THREADSAFE=1)
  # permits a connection to cross native executors safely; disabling alternate
  # Darwin locking styles keeps sandbox files on SQLite's POSIX advisory-lock
  # path, while HAVE_USLEEP gives bounded busy handling the established iOS
  # microsecond-sleep primitive.
  compile_flags = sqlite_pin.fetch("compile_definitions").map { |definition| "-D#{definition}" }
  spec.pod_target_xcconfig = {
    "OTHER_CFLAGS" => ["$(inherited)", *compile_flags].join(" ")
  }

  spec.test_spec "Tests" do |test_spec|
    test_spec.source_files = "ios/Tests/*.swift"
  end
end
