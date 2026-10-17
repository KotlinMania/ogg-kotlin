#if canImport(Testing)
import Testing
import Ogg

@Suite("Ogg Swift Export Tests")
struct OggExportTests {
    @Test("Ogg swift module imported cleanly")
    func testSwiftModuleLoads() throws {
        let cursor = ByteBufferCursor()
        cursor.writeU32Le(v: 42)
        cursor.setPosition(newPosition: 0)
        #expect(cursor.readU32Le() == 42)
    }
}
#elseif canImport(XCTest)
import XCTest
import Ogg

final class OggExportTests: XCTestCase {
    func testSwiftModuleLoads() throws {
        let cursor = ByteBufferCursor()
        cursor.writeU32Le(v: 42)
        cursor.setPosition(newPosition: 0)
        XCTAssertEqual(cursor.readU32Le(), 42)
    }
}
#endif
