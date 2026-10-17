// port-lint: tests src/writing.rs
package io.github.kotlinmania.ogg

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WritingTest {
    @Test
    fun testRecapture() {
        // Test that we can deal with recapture at varying distances.
        val c = ByteBufferCursor()
        val testArr = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val testArr2 = byteArrayOf(2, 4, 8, 16, 32, 64, 128.toByte(), 127, 126, 125, 124)
        val testArr3 = byteArrayOf(3, 5, 9, 17, 33, 65, 129.toByte(), 129.toByte(), 127, 126, 125)

        val np = PacketWriteEndInfo.NormalPacket
        val ep = PacketWriteEndInfo.EndPage
        val w = PacketWriter.new(c)
        w.writePacket(testArr, 0xdeadb33fu, ep, 0uL)

        // Now, after the end of the page, put in some noise.
        w.innerMut().writeAll(ByteArray(38))

        w.writePacket(testArr2, 0xdeadb33fu, np, 1uL)
        w.writePacket(testArr3, 0xdeadb33fu, ep, 2uL)

        assertEquals(0uL, c.seek(OggSeekFrom.Start(0uL)))
        val r = PacketReader.new(c)
        val p1 = r.readPacket()!!
        assertContentEquals(testArr, p1.data)
        val p2 = r.readPacket()!!
        assertContentEquals(testArr2, p2.data)
        val p3 = r.readPacket()!!
        assertContentEquals(testArr3, p3.data)
    }
}
