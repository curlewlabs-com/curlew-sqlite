import CurlewSQLite
import Foundation
import XCTest

/// Drives the pod's own engine through the shim, then the identity check against what
/// that engine reports. The round trip is what shows the compiled amalgamation is the
/// pinned one: an engine that resolved anywhere else fails the source-ID assertion.
final class CurlewSQLiteTests: XCTestCase {
    // Swift cannot import this compound C macro from sqlite3.h.
    private static let strictDatatypeConstraintResult = SQLITE_CONSTRAINT | Int32(12 << 8)
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private var databaseURL: URL!
    private var database: OpaquePointer!

    override func setUpWithError() throws {
        databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("curlew-sqlite-\(UUID().uuidString).db")
        var handle: OpaquePointer?
        let result = databaseURL.path.withCString { path in
            curlew_sqlite3_open_v2(
                path,
                &handle,
                SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX,
                nil
            )
        }
        database = handle
        XCTAssertEqual(result, SQLITE_OK, message())
        XCTAssertEqual(curlew_sqlite3_extended_result_codes(database, 1), SQLITE_OK)
    }

    override func tearDownWithError() throws {
        if let database {
            XCTAssertEqual(curlew_sqlite3_close_v2(database), SQLITE_OK)
        }
        try? FileManager.default.removeItem(at: databaseURL)
    }

    func testEngineReportsThePinnedIdentity() throws {
        let statement = try prepare("SELECT sqlite_version(), sqlite_source_id()")
        defer { curlew_sqlite3_finalize(statement) }
        XCTAssertEqual(curlew_sqlite3_step(statement), SQLITE_ROW, message())
        let version = String(cString: try XCTUnwrap(curlew_sqlite3_column_text(statement, 0)))
        let sourceID = String(cString: try XCTUnwrap(curlew_sqlite3_column_text(statement, 1)))

        XCTAssertEqual(version, CurlewSQLiteProviderIdentity.minimumRuntimeVersion)
        XCTAssertEqual(sourceID, CurlewSQLiteProviderIdentity.expectedSourceID)
        XCTAssertNoThrow(
            try CurlewSQLiteProviderIdentity.validateRuntimeIdentity(version: version, sourceID: sourceID)
        )
    }

    func testBoundValuesRoundTripThroughAStrictTable() throws {
        try execute("CREATE TABLE sample (key TEXT PRIMARY KEY NOT NULL, body TEXT, revision INTEGER) STRICT")
        // Quotes and SQL punctuation must survive as data; interpolating the value would
        // either corrupt it or change the prepared statement. The non-BMP character is
        // the case an encoding mistake corrupts while every ASCII fixture passes.
        let body = "bound value '); DROP TABLE sample; -- \u{1F44B} caf\u{E9}"

        let insert = try prepare("INSERT INTO sample (key, body, revision) VALUES (?, ?, ?)")
        try bindText("first", at: 1, statement: insert)
        try bindText(body, at: 2, statement: insert)
        XCTAssertEqual(curlew_sqlite3_bind_int64(insert, 3, 53), SQLITE_OK)
        XCTAssertEqual(curlew_sqlite3_step(insert), SQLITE_DONE, message())
        XCTAssertEqual(curlew_sqlite3_finalize(insert), SQLITE_OK)
        XCTAssertEqual(curlew_sqlite3_changes(database), 1)

        let nulls = try prepare("INSERT INTO sample (key, body, revision) VALUES (?, ?, ?)")
        try bindText("second", at: 1, statement: nulls)
        XCTAssertEqual(curlew_sqlite3_bind_null(nulls, 2), SQLITE_OK)
        XCTAssertEqual(curlew_sqlite3_bind_null(nulls, 3), SQLITE_OK)
        XCTAssertEqual(curlew_sqlite3_step(nulls), SQLITE_DONE, message())
        XCTAssertEqual(curlew_sqlite3_finalize(nulls), SQLITE_OK)

        let read = try prepare("SELECT body, revision FROM sample ORDER BY key")
        defer { curlew_sqlite3_finalize(read) }
        XCTAssertEqual(curlew_sqlite3_step(read), SQLITE_ROW, message())
        XCTAssertEqual(String(cString: try XCTUnwrap(curlew_sqlite3_column_text(read, 0))), body)
        XCTAssertEqual(curlew_sqlite3_column_int64(read, 1), 53)

        // column_int64 answers 0 for a NULL and for a stored zero alike, so the type is
        // what separates them; it is read before column_text, which may convert it.
        XCTAssertEqual(curlew_sqlite3_step(read), SQLITE_ROW, message())
        XCTAssertEqual(curlew_sqlite3_column_type(read, 1), SQLITE_NULL)
        XCTAssertEqual(curlew_sqlite3_column_int64(read, 1), 0)
        XCTAssertNil(curlew_sqlite3_column_text(read, 0))
        XCTAssertEqual(curlew_sqlite3_step(read), SQLITE_DONE)
    }

    func testStrictTableRejectsTextInAnIntegerColumn() throws {
        try execute("CREATE TABLE sample (key TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL) STRICT")
        let insert = try prepare("INSERT INTO sample (key, revision) VALUES (?, ?)")
        defer { curlew_sqlite3_finalize(insert) }
        try bindText("strict", at: 1, statement: insert)
        try bindText("not-an-integer", at: 2, statement: insert)

        XCTAssertEqual(curlew_sqlite3_step(insert), Self.strictDatatypeConstraintResult)
        XCTAssertEqual(curlew_sqlite3_extended_errcode(database), Self.strictDatatypeConstraintResult)
    }

    func testDefensiveModeReadsBackWhatItSet() {
        var current: Int32 = -1
        XCTAssertEqual(curlew_sqlite3_db_config_defensive(database, 1, &current), SQLITE_OK)
        XCTAssertEqual(current, 1)
    }

    func testAFailedPrepareReportsItsCodeAndMessage() {
        var statement: OpaquePointer?
        let result = "SELECT * FROM missing_table".withCString { sql in
            curlew_sqlite3_prepare_v2(database, sql, -1, &statement, nil)
        }
        XCTAssertEqual(result, SQLITE_ERROR)
        XCTAssertNil(statement)
        XCTAssertTrue(message().contains("missing_table"), message())
    }

    func testIdentityRejectsAnEngineBeforeTheFloor() {
        // STRICT predates the floor, so the boundary case must reject a version that
        // supports STRICT but predates the pinned release.
        XCTAssertThrowsError(try CurlewSQLiteProviderIdentity.validateRuntimeVersion("3.54.0")) { error in
            XCTAssertEqual(
                error as? CurlewSQLiteProviderError,
                .runtimeVersionTooOld(found: "3.54.0", minimum: "3.54.1")
            )
        }
    }

    func testIdentityAcceptsTheFloorAndNewerEngines() {
        XCTAssertNoThrow(try CurlewSQLiteProviderIdentity.validateRuntimeVersion("3.54.1"))
        XCTAssertNoThrow(try CurlewSQLiteProviderIdentity.validateRuntimeVersion("3.55.0"))
        XCTAssertNoThrow(try CurlewSQLiteProviderIdentity.validateRuntimeVersion("4.0.0"))
    }

    func testIdentityRejectsAMalformedVersion() {
        for value in ["3.54", "3.54.1.1", "3..1", "v3.54.1", ""] {
            XCTAssertThrowsError(try CurlewSQLiteProviderIdentity.validateRuntimeVersion(value)) { error in
                XCTAssertEqual(error as? CurlewSQLiteProviderError, .malformedRuntimeVersion(value))
            }
        }
    }

    func testIdentityRejectsADifferentSourceAtThePinnedVersion() {
        // A matching version is not enough: the OS engine or a repackaged amalgamation
        // at the same version must fail the same check.
        XCTAssertThrowsError(
            try CurlewSQLiteProviderIdentity.validateRuntimeIdentity(
                version: "3.54.1",
                sourceID: "different-source-id"
            )
        ) { error in
            XCTAssertEqual(
                error as? CurlewSQLiteProviderError,
                .runtimeSourceIDMismatch(
                    found: "different-source-id",
                    expected: CurlewSQLiteProviderIdentity.expectedSourceID
                )
            )
        }
    }

    private func message() -> String {
        guard let database, let text = curlew_sqlite3_errmsg(database) else { return "no database" }
        return String(cString: text)
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        let result = sql.withCString { pointer in
            curlew_sqlite3_prepare_v2(database, pointer, -1, &statement, nil)
        }
        XCTAssertEqual(result, SQLITE_OK, message())
        return try XCTUnwrap(statement)
    }

    private func execute(_ sql: String) throws {
        let statement = try prepare(sql)
        defer { curlew_sqlite3_finalize(statement) }
        XCTAssertEqual(curlew_sqlite3_step(statement), SQLITE_DONE, message())
    }

    private func bindText(_ value: String, at index: Int32, statement: OpaquePointer) throws {
        let result = value.withCString { pointer in
            curlew_sqlite3_bind_text(statement, index, pointer, -1, Self.transient)
        }
        XCTAssertEqual(result, SQLITE_OK, message())
    }
}
