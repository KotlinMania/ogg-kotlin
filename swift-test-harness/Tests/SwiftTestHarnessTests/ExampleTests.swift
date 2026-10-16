#if canImport(Testing)
import Testing
import Ogg

@Suite("Ogg Swift Export Tests")
struct OggExportTests {
    @Test("Ogg swift module imported cleanly")
    func testSwiftModuleLoads() throws {
        #expect(Bool(true), "Ogg swift module imported cleanly")
    }
}
#elseif canImport(XCTest)
import XCTest
import Ogg

final class OggExportTests: XCTestCase {
    func testSwiftModuleLoads() throws {
        XCTAssertTrue(true, "Ogg swift module imported cleanly")
    }
}
#endif
