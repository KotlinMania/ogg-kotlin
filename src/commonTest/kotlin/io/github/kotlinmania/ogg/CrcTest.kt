// port-lint: tests src/crc.rs
package io.github.kotlinmania.ogg

import kotlin.test.Test
import kotlin.test.assertEquals

class CrcTest {
    @Test
    fun testCrc32() {
        // Test page taken from real Ogg file
        val testArr =
            byteArrayOf(
                0x4f,
                0x67,
                0x67,
                0x53,
                0x00,
                0x02,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x74,
                0xa3.toByte(),
                0x90.toByte(),
                0x5b,
                0x00,
                0x00,
                0x00,
                0x00,
                // The spec requires us to zero out the CRC field
                0x00,
                0x00,
                0x00,
                0x00,
                0x01,
                0x1e,
                0x01,
                0x76,
                0x6f,
                0x72,
                0x62,
                0x69,
                0x73,
                0x00,
                0x00,
                0x00,
                0x00,
                0x02,
                0x44,
                0xac.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x80.toByte(),
                0xb5.toByte(),
                0x01,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0xb8.toByte(),
                0x01,
            )
        assertEquals(0x9f858776u, vorbisCrc32(byteArrayOf(61, 61, 33)))
        assertEquals(0x3d4e946du, vorbisCrc32(testArr))
        assertEquals(0x7b374db8u, vorbisCrc32(testArr.copyOfRange(0, 27)))
    }
}
