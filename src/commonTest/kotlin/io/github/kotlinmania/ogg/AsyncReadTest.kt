// port-lint: tests tests/async_read.rs
package io.github.kotlinmania.ogg

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private class RandomWouldBlock(
    private val inner: OggRead,
    private val rng: Random = Random(0x5eed1234),
) : OggAsyncRead {
    override suspend fun read(buf: ByteArray, offset: Int, length: Int): Int {
        while (rng.nextBoolean()) {
            yield()
        }
        return inner.read(buf, offset, length)
    }
}

class AsyncReadTest {
    private suspend fun testOggRandomWouldBlockRun(seed: Int) {
        val rng = Random(seed)
        val c1 = ByteBufferCursor()
        val testArr = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val testArr2 = byteArrayOf(2, 4, 8, 16, 32, 64, 128.toByte(), 127, 126, 125, 124)
        val testArr3 = byteArrayOf(3, 5, 9, 17, 33, 65, 129.toByte(), 129.toByte(), 127, 126, 125)
        run {
            val w = PacketWriter.new(c1)
            val np = PacketWriteEndInfo.NormalPacket
            w.writePacket(testArr, 0xdeadb33fu, np, 0uL)
            w.writePacket(testArr2, 0xdeadb33fu, np, 1uL)
            w.writePacket(testArr3, 0xdeadb33fu, PacketWriteEndInfo.EndPage, 2uL)
        }
        assertEquals(0uL, c1.seek(OggSeekFrom.Start(0uL)))
        run {
            val rwd = RandomWouldBlock(c1, rng)
            val r = AsyncPacketReader.new(rwd)
            val p1 = r.tryNext()!!
            assertContentEquals(testArr, p1.data)
            val p2 = r.tryNext()!!
            assertContentEquals(testArr2, p2.data)
            val p3 = r.tryNext()!!
            assertContentEquals(testArr3, p3.data)
        }

        // Now test packets spanning multiple segments
        val c2 = ByteBufferCursor()
        val testArrMultiSeg = ByteArray(700) { idx -> ((idx and 0xFF) / 4).toByte() }
        run {
            val w = PacketWriter.new(c2)
            val np = PacketWriteEndInfo.NormalPacket
            w.writePacket(testArr, 0xdeadb33fu, np, 0uL)
            w.writePacket(testArrMultiSeg, 0xdeadb33fu, np, 1uL)
            w.writePacket(testArr3, 0xdeadb33fu, PacketWriteEndInfo.EndPage, 2uL)
        }
        assertEquals(0uL, c2.seek(OggSeekFrom.Start(0uL)))
        run {
            val rwd = RandomWouldBlock(c2, rng)
            val r = AsyncPacketReader.new(rwd)
            val p1 = r.tryNext()!!
            assertContentEquals(testArr, p1.data)
            val p2 = r.tryNext()!!
            assertContentEquals(testArrMultiSeg, p2.data)
            val p3 = r.tryNext()!!
            assertContentEquals(testArr3, p3.data)
        }

        // Now test packets spanning multiple pages
        val c3 = ByteBufferCursor()
        val testArrMultiPage = ByteArray(14_000) { idx -> ((idx and 0xFF) / 4).toByte() }
        run {
            val w = PacketWriter.new(c3)
            val np = PacketWriteEndInfo.NormalPacket
            w.writePacket(testArrMultiPage, 0xdeadb33fu, np, 1uL)
            w.writePacket(testArr3, 0xdeadb33fu, PacketWriteEndInfo.EndPage, 2uL)
        }
        assertEquals(0uL, c3.seek(OggSeekFrom.Start(0uL)))
        run {
            val rwd = RandomWouldBlock(c3, rng)
            val r = AsyncPacketReader.new(rwd)
            val p2 = r.tryNext()!!
            assertContentEquals(testArrMultiPage, p2.data)
            val p3 = r.tryNext()!!
            assertContentEquals(testArr3, p3.data)
        }
    }

    @Test
    fun testOggRandomWouldBlock() =
        runTest {
            for (i in 0 until 100) {
                testOggRandomWouldBlockRun(i)
            }
        }
}
