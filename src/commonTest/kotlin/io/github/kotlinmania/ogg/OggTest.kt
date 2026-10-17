// port-lint: tests src/test.rs
package io.github.kotlinmania.ogg

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class XorShift(
    private var s0: UInt,
    private var s1: UInt,
    private var s2: UInt,
    private var s3: UInt,
) {
    companion object {
        fun fromTwo(seed0: UInt, seed1: UInt): XorShift {
            val xs =
                XorShift(
                    s0 = seed0 xor 0x2a24a930u,
                    s1 = seed1 xor 0xa9f60227u,
                    s2 = seed0.inv() xor 0x68c44d2du,
                    s3 = seed1.inv() xor 0xa1f9794au,
                )
            xs.next()
            xs.next()
            xs.next()
            return xs
        }
    }

    fun next(): UInt {
        var r = s3
        r = r xor (r shl 11)
        r = r xor (r shr 8)
        s3 = s2
        s2 = s1
        s1 = s0
        r = r xor s0
        r = r xor (s0 shr 19)
        s0 = r
        return r
    }
}

private fun genPck(seed: UInt, lenDFour: Int): ByteArray {
    val ret = ByteArray(lenDFour * 4)
    val xs = XorShift.fromTwo(seed, lenDFour.toUInt())
    if (lenDFour > 0) {
        ret[0] = (seed and 0xFFu).toByte()
        ret[1] = ((seed shr 8) and 0xFFu).toByte()
        ret[2] = ((seed shr 16) and 0xFFu).toByte()
        ret[3] = ((seed shr 24) and 0xFFu).toByte()
    }
    for (i in 1 until lenDFour) {
        val v = xs.next()
        val off = i * 4
        ret[off] = (v and 0xFFu).toByte()
        ret[off + 1] = ((v shr 8) and 0xFFu).toByte()
        ret[off + 2] = ((v shr 16) and 0xFFu).toByte()
        ret[off + 3] = ((v shr 24) and 0xFFu).toByte()
    }
    return ret
}

private fun testArrEq(aArr: ByteArray, bArr: ByteArray) {
    for (i in bArr.indices) {
        if (aArr[i] != bArr[i]) {
            error("Mismatch of values at index $i: ${aArr[i]} ${bArr[i]}")
        }
    }
}

private fun testSeekR(r: PacketReader, absgp: ULong, offset: Int = 0) {
    r.seekAbsgp(null, absgp)
    val skipCount = (absgp % 3uL).toInt() + offset
    repeat(skipCount) {
        r.readPacket()!!
    }
    val pck = r.readPacket()!!
    assertTrue(abs(absgp.toLong() - pck.absgpPage().toLong()) <= 3L)
    testArrEq(pck.data, genPck(absgp.toUInt(), pck.data.size / 4))
}

private fun ensureContinuesR(r: PacketReader, absgp: ULong) {
    val pck0 = r.readPacket()!!
    testArrEq(pck0.data, genPck(absgp.toUInt(), pck0.data.size / 4))
    val pck1 = r.readPacket()!!
    testArrEq(pck1.data, genPck((absgp + 1uL).toUInt(), pck1.data.size / 4))
    val pck2 = r.readPacket()!!
    testArrEq(pck2.data, genPck((absgp + 2uL).toUInt(), pck2.data.size / 4))
    val pck3 = r.readPacket()!!
    testArrEq(pck3.data, genPck((absgp + 3uL).toUInt(), pck3.data.size / 4))
}

class OggTest {
    @Test
    fun testPacketRw() {
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
            val r = PacketReader.new(c1)
            val p1 = r.readPacket()!!
            assertContentEquals(testArr, p1.data)
            val p2 = r.readPacket()!!
            assertContentEquals(testArr2, p2.data)
            val p3 = r.readPacket()!!
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
            val r = PacketReader.new(c2)
            val p1 = r.readPacket()!!
            assertContentEquals(testArr, p1.data)
            val p2 = r.readPacket()!!
            testArrEq(testArrMultiSeg, p2.data)
            val p3 = r.readPacket()!!
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
            val r = PacketReader.new(c3)
            val p2 = r.readPacket()!!
            testArrEq(testArrMultiPage, p2.data)
            val p3 = r.readPacket()!!
            assertContentEquals(testArr3, p3.data)
        }
    }

    @Test
    fun testPageEndAfterFirstPacket() {
        val c = ByteBufferCursor()
        val testArr = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val testArr2 = byteArrayOf(2, 4, 8, 16, 32, 64, 128.toByte(), 127, 126, 125, 124)
        val testArr3 = byteArrayOf(3, 5, 9, 17, 33, 65, 129.toByte(), 129.toByte(), 127, 126, 125)
        run {
            val w = PacketWriter.new(c)
            val np = PacketWriteEndInfo.NormalPacket
            w.writePacket(testArr, 0xdeadb33fu, PacketWriteEndInfo.EndPage, 0uL)
            w.writePacket(testArr2, 0xdeadb33fu, np, 1uL)
            w.writePacket(testArr3, 0xdeadb33fu, PacketWriteEndInfo.EndPage, 2uL)
        }
        assertEquals(0uL, c.seek(OggSeekFrom.Start(0uL)))
        run {
            val r = PacketReader.new(c)
            val p1 = r.readPacket()!!
            assertContentEquals(testArr, p1.data)
            val p2 = r.readPacket()!!
            assertContentEquals(testArr2, p2.data)
            val p3 = r.readPacket()!!
            assertContentEquals(testArr3, p3.data)
        }
    }

    @Test
    fun testPacketWrite() {
        val c = ByteBufferCursor()

        val testArrOut =
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
                0x6d,
                0x94.toByte(),
                0x4e,
                0x3d,
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
        val testArrIn =
            byteArrayOf(
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

        run {
            val w = PacketWriter.new(c)
            w.writePacket(testArrIn, 0x5b90a374u, PacketWriteEndInfo.EndPage, 0uL)
        }
        val cr = c.getRef()
        assertEquals(testArrOut.size, cr.size)
        testArrEq(cr, testArrOut)
    }

    @Test
    fun testWriteLarge() {
        val c = ByteBufferCursor()
        val testArr = genPck(1234u, 70_000 / 4)
        val w = PacketWriter.new(c)
        w.writePacket(testArr, 0x5b90a374u, PacketWriteEndInfo.EndPage, 0uL)

        assertEquals(0uL, c.seek(OggSeekFrom.Start(0uL)))
        val r = PacketReader.new(c)
        val p = r.readPacket()!!
        testArrEq(testArr, p.data)
    }

    @Test
    fun testByteSeekingContinued() {
        val c = ByteBufferCursor()
        val off: ULong
        run {
            val w = PacketWriter.new(c)
            val np = PacketWriteEndInfo.NormalPacket
            val ep = PacketWriteEndInfo.EndPage
            val es = PacketWriteEndInfo.EndStream

            w.writePacket(genPck(1u, 300), 0xdeadb33fu, ep, 1uL)
            w.writePacket(genPck(2u, 270_000), 0xdeadb33fu, np, 2uL)
            off = w.getCurrentOffs()
            w.writePacket(genPck(3u, 270_000), 0xdeadb33fu, np, 3uL)
            w.writePacket(genPck(4u, 270_000), 0xdeadb33fu, es, 4uL)
        }
        assertEquals(0uL, c.seek(OggSeekFrom.Start(0uL)))

        val r = PacketReader.new(c)
        val pck1 = r.readPacket()!!
        assertEquals(1uL, pck1.absgpPage())
        testArrEq(pck1.data, genPck(1u, pck1.data.size / 4))

        // Jump over the second packet
        assertEquals(off, r.seekBytes(OggSeekFrom.Start(off)))
        val pck3 = r.readPacket()!!
        assertEquals(3uL, pck3.absgpPage())
        testArrEq(pck3.data, genPck(3u, pck3.data.size / 4))
        val pck4 = r.readPacket()!!
        assertEquals(4uL, pck4.absgpPage())
        testArrEq(pck4.data, genPck(4u, pck4.data.size / 4))
    }

    @Test
    fun testSeeking() {
        val pckCount = 402
        val rng = XorShift.fromTwo(0x9899eb03u, 0x54138143u)
        val c = ByteBufferCursor()

        run {
            val w = PacketWriter.new(c)
            val np = PacketWriteEndInfo.NormalPacket
            val ep = PacketWriteEndInfo.EndPage

            for (ctr in 0 until pckCount) {
                w.writePacket(
                    genPck(ctr.toUInt(), rng.next().toInt() and 127),
                    0xdeadb33fu,
                    if ((ctr + 1) % 3 == 0) ep else np,
                    ctr.toULong(),
                )
            }
        }
        assertEquals(0uL, c.seek(OggSeekFrom.Start(0uL)))

        val r = PacketReader.new(c)
        testSeekR(r, 32uL)
        testSeekR(r, 300uL)
        testSeekR(r, 314uL)
        testSeekR(r, 100uL)
        ensureContinuesR(r, 101uL)
        testSeekR(r, 10uL)
        ensureContinuesR(r, 11uL)

        r.seekAbsgp(null, 377uL)
        r.seekAbsgp(null, 377uL)
        testSeekR(r, 377uL)
        ensureContinuesR(r, 378uL)

        r.seekAbsgp(null, 200uL)
        r.seekAbsgp(null, 200uL)
        testSeekR(r, 200uL)
        ensureContinuesR(r, 201uL)

        testSeekR(r, 401uL)
        testSeekR(r, 250uL)
    }

    @Test
    fun testSeekingContinued() {
        val pckCount = 402
        val pckLenAdd = IntArray(8)
        pckLenAdd[0] = 133_000
        pckLenAdd[1] = 133_000
        pckLenAdd[2] = 270_000
        pckLenAdd[3] = 30_000
        pckLenAdd[4] = 13_000

        val rng = XorShift.fromTwo(0x9899eb03u, 0x54138143u)
        val c = ByteBufferCursor()

        run {
            val w = PacketWriter.new(c)
            val np = PacketWriteEndInfo.NormalPacket
            val ep = PacketWriteEndInfo.EndPage

            for (ctr in 0 until pckCount) {
                val rv = rng.next().toInt()
                val size = ((rv and 127) + pckLenAdd[(rv ushr 8) and 7]) ushr 2
                w.writePacket(
                    genPck(ctr.toUInt(), size),
                    0xdeadb33fu,
                    if ((ctr + 1) % 3 == 0) ep else np,
                    ctr.toULong(),
                )
            }
        }
        assertEquals(0uL, c.seek(OggSeekFrom.Start(0uL)))

        val r = PacketReader.new(c)
        testSeekR(r, 32uL)
        testSeekR(r, 300uL, 2)
        testSeekR(r, 314uL, 2)
        testSeekR(r, 100uL, -1)
        ensureContinuesR(r, 101uL)
        testSeekR(r, 10uL)
        ensureContinuesR(r, 11uL)

        r.seekAbsgp(null, 377uL)
        r.seekAbsgp(null, 377uL)
        testSeekR(r, 377uL)
        ensureContinuesR(r, 378uL)

        r.seekAbsgp(null, 200uL)
        r.seekAbsgp(null, 200uL)
        testSeekR(r, 200uL)
        ensureContinuesR(r, 201uL)

        testSeekR(r, 401uL, -2)
        testSeekR(r, 250uL, -1)
    }

    @Test
    fun testIssue14() {
        val c1 = ByteBufferCursor()
        val testArr = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val testArr2 = byteArrayOf(2, 4, 8, 16, 32, 64, 128.toByte(), 127, 126, 125, 124)
        val testArr3 = byteArrayOf(3, 5, 9, 17, 33, 65, 129.toByte(), 129.toByte(), 127, 126, 125)
        run {
            c1.writeAll("O".encodeToByteArray())
            val w = PacketWriter.new(c1)
            val np = PacketWriteEndInfo.NormalPacket
            w.writePacket(testArr, 0xdeadb33fu, np, 0uL)
            w.writePacket(testArr2, 0xdeadb33fu, np, 1uL)
            w.writePacket(testArr3, 0xdeadb33fu, PacketWriteEndInfo.EndPage, 2uL)
        }
        assertEquals(0uL, c1.seek(OggSeekFrom.Start(0uL)))
        run {
            val r = PacketReader.new(c1)
            val p1 = r.readPacket()!!
            assertContentEquals(testArr, p1.data)
            val p2 = r.readPacket()!!
            assertContentEquals(testArr2, p2.data)
            val p3 = r.readPacket()!!
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
            val r = PacketReader.new(c2)
            val p1 = r.readPacket()!!
            assertContentEquals(testArr, p1.data)
            val p2 = r.readPacket()!!
            testArrEq(testArrMultiSeg, p2.data)
            val p3 = r.readPacket()!!
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
            val r = PacketReader.new(c3)
            val p2 = r.readPacket()!!
            testArrEq(testArrMultiPage, p2.data)
            val p3 = r.readPacket()!!
            assertContentEquals(testArr3, p3.data)
        }
    }

    @Test
    fun testIssue7() {
        val c = ByteBufferCursor()
        val testArr = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val testArr2 = byteArrayOf(2, 4, 8, 16, 32, 64, 128.toByte(), 127, 126, 125, 124)
        run {
            val w = PacketWriter.new(c)
            w.writePacket(testArr, 0xdeadb33fu, PacketWriteEndInfo.EndStream, 0uL)
        }
        // Write trailing garbage.
        c.writeAll(testArr2)

        // Trailing garbage should be ignored.
        assertEquals(0uL, c.seek(OggSeekFrom.Start(0uL)))
        run {
            val r = PacketReader.new(c)
            val p1 = r.readPacket()!!
            assertContentEquals(testArr, p1.data)
            // Make sure we don't yield the trailing garbage.
            assertNull(r.readPacket())
        }

        // Non-Ogg data should return an error.
        val nonOgg = ByteBufferCursor(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        run {
            val r = PacketReader.new(nonOgg)
            assertFailsWith<OggReadError.NoCapturePatternFound> {
                r.readPacket()
            }
        }

        // Empty data is considered non-Ogg data.
        val emptyOgg = ByteBufferCursor(ByteArray(0))
        run {
            val r = PacketReader.new(emptyOgg)
            assertFailsWith<OggReadError.NoCapturePatternFound> {
                r.readPacket()
            }
        }
    }
}
