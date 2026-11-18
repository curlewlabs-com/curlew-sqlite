import Foundation

/// The pinned identity of the SQLite engine this pod compiles, and the check a caller
/// runs against it on every open before touching its own tables: a version floor plus
/// an exact source-ID match, with no retry through the OS SQLite library and no
/// lower-capability mode.
///
/// Pure string comparison, so it needs no SQLite symbols. The caller reads
/// `SELECT sqlite_version(), sqlite_source_id()` on its own connection and passes both
/// strings in. Every process that opens a database runs the check on its own
/// connection: an app extension links its own copy of the engine, and a check only
/// the host app ran would not cover it.
public enum CurlewSQLiteProviderIdentity {
    public static let minimumRuntimeVersion = "3.54.1"
    public static let expectedSourceID = """
        2026-10-16 14:45:20 555e07ef33f69a0957d24d8e555b702464a131d6f1607f4c3c1e4551e8e6ca76
        """

    /// The floor is deliberately newer than 3.37.0 (the release that added STRICT
    /// tables): versions through 3.51.2 can contain SQLite's WAL-reset corruption bug,
    /// and rejecting that range keeps a future journal-mode change from silently
    /// turning an accepted binary into a corruption risk.
    public static func validateRuntimeVersion(_ runtimeVersion: String) throws {
        let found = try SQLiteRuntimeVersion(parsing: runtimeVersion)
        let minimum = try SQLiteRuntimeVersion(parsing: minimumRuntimeVersion)
        guard found >= minimum else {
            throw CurlewSQLiteProviderError.runtimeVersionTooOld(
                found: runtimeVersion,
                minimum: minimumRuntimeVersion
            )
        }
    }

    /// A newer semantic version is permitted while the source-ID comparison stays
    /// exact, which is what keeps the effective provider hard-pinned: an engine bump
    /// has to move the pin, its checksum, the minimum, and this source ID together.
    public static func validateRuntimeIdentity(version: String, sourceID: String) throws {
        try validateRuntimeVersion(version)
        guard sourceID == expectedSourceID else {
            throw CurlewSQLiteProviderError.runtimeSourceIDMismatch(
                found: sourceID,
                expected: expectedSourceID
            )
        }
    }
}

/// Why the identity check refused an engine.
public enum CurlewSQLiteProviderError: Error, Equatable, LocalizedError, Sendable {
    case malformedRuntimeVersion(String)
    case runtimeVersionTooOld(found: String, minimum: String)
    case runtimeSourceIDMismatch(found: String, expected: String)

    public var errorDescription: String? {
        switch self {
        case let .malformedRuntimeVersion(value):
            return "SQLite reported a malformed runtime version: \(value)"
        case let .runtimeVersionTooOld(found, minimum):
            return "SQLite runtime \(found) is below required minimum \(minimum)"
        case let .runtimeSourceIDMismatch(found, expected):
            return "SQLite source ID \(found) does not match pinned source ID \(expected)"
        }
    }
}

private struct SQLiteRuntimeVersion: Comparable {
    let major: Int
    let minor: Int
    let patch: Int

    init(parsing value: String) throws {
        let components = value.split(separator: ".", omittingEmptySubsequences: false)
        guard components.count == 3,
              components.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }),
              let major = Int(components[0]),
              let minor = Int(components[1]),
              let patch = Int(components[2]) else {
            throw CurlewSQLiteProviderError.malformedRuntimeVersion(value)
        }
        self.major = major
        self.minor = minor
        self.patch = patch
    }

    static func < (lhs: SQLiteRuntimeVersion, rhs: SQLiteRuntimeVersion) -> Bool {
        if lhs.major != rhs.major { return lhs.major < rhs.major }
        if lhs.minor != rhs.minor { return lhs.minor < rhs.minor }
        return lhs.patch < rhs.patch
    }
}
