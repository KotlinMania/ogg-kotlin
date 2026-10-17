// port-lint: source src/writing.rs
package io.github.kotlinmania.ogg

private class CurrentPageValues(
    /** `true` if this page is the first one in the logical bitstream. */
    var firstPage: Boolean = true,
    /** Page counter of the current page, increased for every page. */
    var sequenceNum: UInt = 0u,
    /** Points to the first unwritten position in [curPgLacing]. */
    var segmentCnt: UByte = 0u,
    val curPgLacing: ByteArray = ByteArray(255),
    /** The data and the absolute granule positions of the packets. */
    val curPgData: MutableList<Pair<ByteArray, ULong>> = ArrayList(255),
    /**
     * Non-null offset if the last packet couldn't make it fully into this page and
     * has to be continued in the next page. Points to the first index in
     * `curPgData.last()` that should NOT be written in this page anymore.
     */
    var pckThisOverflowIdx: Int? = null,
    /**
     * Non-null offset if the first packet couldn't make it fully into the last page and
     * has to be continued in this page. Points to the first index in `curPgData[0]`
     * that hasn't been written.
     */
    var pckLastOverflowIdx: Int? = null,
)

/**
 * Specifies whether to end something with the write of the packet.
 *
 * If you want to end a stream you need to inform the Ogg [PacketWriter]
 * about this. This is the enum to do so.
 *
 * Also, codecs sometimes have special requirements to put
 * the first packet of the whole stream into its own page.
 * The [EndPage] variant can be used for this.
 */
public enum class PacketWriteEndInfo {
    /** No ends here, just a normal packet. */
    NormalPacket,

    /** Force-end the current page. */
    EndPage,

    /** End the whole logical stream. */
    EndStream,
}

/**
 * Writer for packets into an Ogg stream.
 */
public class PacketWriter(
    private var wtr: OggWrite,
) {
    private val pageVals: MutableMap<UInt, CurrentPageValues> = mutableMapOf()

    public companion object {
        public fun new(wtr: OggWrite): PacketWriter = PacketWriter(wtr)
    }

    public fun intoInner(): OggWrite = wtr

    /**
     * Access the interior writer.
     *
     * This allows access of the writer contained inside.
     * No guarantees are given onto the pattern of the writes.
     */
    public fun inner(): OggWrite = wtr

    /**
     * Access the interior writer mutably.
     *
     * This allows access of the writer contained inside.
     * No guarantees are given onto the pattern of the writes.
     */
    public fun innerMut(): OggWrite = wtr

    /**
     * Write a packet into the Ogg stream.
     */
    public fun writePacket(
        pckCont: ByteArray,
        serial: UInt,
        inf: PacketWriteEndInfo,
        absgp: ULong,
    ) {
        val isEndStream = inf == PacketWriteEndInfo.EndStream
        val pg =
            pageVals.getOrPut(serial) {
                CurrentPageValues(
                    firstPage = true,
                    sequenceNum = 0u,
                    segmentCnt = 0u,
                    curPgLacing = ByteArray(255),
                    curPgData = ArrayList(255),
                    pckThisOverflowIdx = null,
                    pckLastOverflowIdx = null,
                )
            }

        val contLen = pckCont.size
        pg.curPgData.add(Pair(pckCont.copyOf(), absgp))

        val lastDataSegmentSize = (contLen % 255).toUByte()
        val neededSegments = (contLen / 255) + 1
        var segmentInPageI: Int = pg.segmentCnt.toInt()
        var atPageEnd = false
        for (segmentI in 0 until neededSegments) {
            atPageEnd = false
            if (segmentI + 1 < neededSegments) {
                // For all segments containing 255 pieces of data
                pg.curPgLacing[segmentInPageI] = 255u.toByte()
            } else {
                // For the last segment, must contain < 255 pieces of data (including 0)
                pg.curPgLacing[segmentInPageI] = lastDataSegmentSize.toByte()
            }
            pg.segmentCnt = (segmentInPageI + 1).toUByte()
            segmentInPageI = (segmentInPageI + 1) % 255
            if (segmentInPageI == 0) {
                if (segmentI + 1 < neededSegments) {
                    // We have to flush a page, but we know there are more to come...
                    pg.pckThisOverflowIdx = (segmentI + 1) * 255
                    writePage(wtr, serial, pg, false)
                } else {
                    // We have to write a page end, and it's the very last we need to write
                    writePage(wtr, serial, pg, isEndStream)
                    pg.pckThisOverflowIdx = null
                    pg.pckLastOverflowIdx = null
                }
                atPageEnd = true
            }
        }
        if (inf != PacketWriteEndInfo.NormalPacket && !atPageEnd) {
            // Write a page end
            writePage(wtr, serial, pg, isEndStream)
            pg.pckLastOverflowIdx = null
        }
    }

    /**
     * Returns the current byte offset of the underlying seekable writer.
     */
    public fun getCurrentOffs(): ULong {
        val seekable =
            wtr as? OggSeek
                ?: throw OggIoError(OggIoErrorKind.InvalidInput, "underlying writer does not support seeking")
        return seekable.streamPosition()
    }

    private fun writePage(
        wtr: OggWrite,
        serial: UInt,
        pg: CurrentPageValues,
        lastPage: Boolean,
    ) {
        val hdrCur = ByteBufferCursor()
        hdrCur.writeAll(byteArrayOf(0x4f, 0x67, 0x67, 0x53, 0x00))
        var flags: UByte = 0u
        if (pg.pckLastOverflowIdx != null) flags = flags or 0x01u
        if (pg.firstPage) flags = flags or 0x02u
        if (lastPage) flags = flags or 0x04u

        hdrCur.writeU8(flags)

        val pckData = pg.curPgData
        var lastFinishingPckAbsgp: ULong = (-1L).toULong()
        for ((idx, pair) in pckData.withIndex()) {
            val absgp = pair.second
            if (!(idx + 1 == pckData.size && pg.pckThisOverflowIdx != null)) {
                lastFinishingPckAbsgp = absgp
            }
        }

        hdrCur.writeU64Le(lastFinishingPckAbsgp)
        hdrCur.writeU32Le(serial)
        hdrCur.writeU32Le(pg.sequenceNum)

        // Checksum, calculated below
        hdrCur.writeU32Le(0u)
        hdrCur.writeU8(pg.segmentCnt)

        val segmentCountInt = pg.segmentCnt.toInt()
        val hdrBytes = hdrCur.getRef()

        var hashCalculated = vorbisCrc32Update(0u, hdrBytes)
        hashCalculated = vorbisCrc32Update(hashCalculated, pg.curPgLacing, 0, segmentCountInt)

        for ((idx, pair) in pckData.withIndex()) {
            val pck = pair.first
            var start = 0
            if (idx == 0) {
                val overflowStart = pg.pckLastOverflowIdx
                if (overflowStart != null) {
                    start = overflowStart
                }
            }
            var end = pck.size
            if (idx + 1 == pckData.size) {
                val overflowEnd = pg.pckThisOverflowIdx
                if (overflowEnd != null) {
                    end = overflowEnd
                }
            }
            hashCalculated = vorbisCrc32Update(hashCalculated, pck, start, end - start)
        }

        // Go back to enter the checksum at byte offset 22
        hdrCur.seek(OggSeekFrom.Start(22uL))
        hdrCur.writeU32Le(hashCalculated)

        // Write header, segment lacing table, and packet slices
        wtr.writeAll(hdrCur.getRef())
        wtr.writeAll(pg.curPgLacing, 0, segmentCountInt)
        for ((idx, pair) in pckData.withIndex()) {
            val pck = pair.first
            var start = 0
            if (idx == 0) {
                val overflowStart = pg.pckLastOverflowIdx
                if (overflowStart != null) {
                    start = overflowStart
                }
            }
            var end = pck.size
            if (idx + 1 == pckData.size) {
                val overflowEnd = pg.pckThisOverflowIdx
                if (overflowEnd != null) {
                    end = overflowEnd
                }
            }
            wtr.writeAll(pck, start, end - start)
        }

        // Reset the page state for the next page
        pg.firstPage = false
        pg.sequenceNum += 1u
        pg.segmentCnt = 0u
        if (pg.pckThisOverflowIdx != null) {
            val d = pg.curPgData.removeAt(pg.curPgData.lastIndex)
            pg.curPgData.clear()
            pg.curPgData.add(d)
        } else {
            pg.curPgData.clear()
        }

        pg.pckLastOverflowIdx = pg.pckThisOverflowIdx
        pg.pckThisOverflowIdx = null
    }
}
