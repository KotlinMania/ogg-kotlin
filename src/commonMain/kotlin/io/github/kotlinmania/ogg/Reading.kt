// port-lint: source src/reading.rs
package io.github.kotlinmania.ogg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min

/**
 * Error that can be raised when decoding an Ogg transport.
 */
public sealed class OggReadError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The capture pattern for a new page was not found where one was expected.
     */
    public class NoCapturePatternFound : OggReadError("No Ogg capture pattern found")

    /**
     * Invalid stream structure version, with the given one attached.
     */
    public class InvalidStreamStructVer(
        public val ver: UByte,
    ) : OggReadError("A non zero stream structure version was passed")

    /**
     * Mismatch of the hash value with (expected, calculated) value.
     */
    public class HashMismatch(
        public val expected: UInt,
        public val calculated: UInt,
    ) : OggReadError("CRC32 hash mismatch")

    /**
     * I/O error occurred.
     */
    public class ReadError(
        public val err: OggIoError,
    ) : OggReadError("I/O error", err)

    /**
     * Some constraint required by the spec was not met.
     */
    public class InvalidData : OggReadError("Constraint violated")

    public fun descriptionStr(): String =
        when (this) {
            is NoCapturePatternFound -> "No Ogg capture pattern found"
            is InvalidStreamStructVer -> "A non zero stream structure version was passed"
            is HashMismatch -> "CRC32 hash mismatch"
            is ReadError -> "I/O error"
            is InvalidData -> "Constraint violated"
        }

    override fun toString(): String = descriptionStr()
}

/**
 * Containing information about an Ogg page that is shared between multiple places.
 */
internal class PageBaseInfo(
    /** `true`: the first packet is continued from the page before. `false`: if it's a "fresh" one. */
    var startsWithContinued: Boolean,
    /** `true` if this page is the first one in the logical bitstream. */
    var firstPage: Boolean,
    /** `true` if this page is the last one in the logical bitstream. */
    var lastPage: Boolean,
    /** Absolute granule position. The codec defines further meaning. */
    var absgp: ULong,
    /** Page counter. */
    var sequenceNum: UInt,
    /** Page checksum. */
    var checksum: UInt,
    /**
     * Packet information:
     * index is number of packet, pair is (offset, length) of packet.
     * If [endsWithContinued] is true, the last element will contain information
     * about the continued packet.
     */
    var packetPositions: MutableList<Pair<UShort, UShort>>,
    /**
     * `true` if the packet is continued in subsequent page(s).
     * `false` if the packet has a segment of length < 255 inside this page.
     */
    var endsWithContinued: Boolean,
)

/**
 * Internal helper struct for [PacketReader] state.
 */
internal class PageInfo(
    /** Basic information about the last read page. */
    var bi: PageBaseInfo,
    /** The index of the first "unread" packet. */
    var packetIdx: UByte,
    /** Contains the package data. */
    var pageBody: ByteArray,
    /**
     * If there is a residue from previous pages in terms of a package spanning multiple
     * pages, this field contains it. Having this `MutableList<ByteArray>` and
     * not `ByteArray` ensures O(n) complexity instead of O(n^2)
     * for `n` as number of pages that the packet is contained in.
     */
    var lastOverlapPck: MutableList<ByteArray>,
) {
    /**
     * Returns `true` if the first "unread" packet is the first one
     * in the page, `false` otherwise.
     */
    fun isFirstPckInPg(): Boolean = packetIdx == 0u.toUByte()

    /**
     * Returns `true` if the first "unread" packet is the last one
     * in the page, `false` otherwise.
     * If the first "unread" packet isn't completed in this page
     * (spans page borders), this returns `false`.
     */
    fun isLastPckInPg(): Boolean {
        val continuedAdd = if (bi.endsWithContinued) 1 else 0
        return (packetIdx.toInt() + 1 + continuedAdd) == bi.packetPositions.size
    }
}

/**
 * Contains a fully parsed Ogg page.
 */
public class OggPage internal constructor(
    internal val inner: PageParser,
) {
    /** Returns whether there is an ending packet in the page. */
    internal fun hasPacketEnd(): Boolean {
        val continuedSub = if (inner.bi.endsWithContinued) 1 else 0
        return (inner.bi.packetPositions.size - continuedSub) > 0
    }

    /** Returns whether there is a packet that both starts and ends inside the page. */
    internal fun hasWholePacket(): Boolean {
        val endSub = if (inner.bi.endsWithContinued) 1 else 0
        val startSub = if (inner.bi.startsWithContinued) 1 else 0
        val sub = endSub + startSub
        return (inner.bi.packetPositions.size - sub) > 0
    }

    /** Returns whether there is a starting packet in the page. */
    internal fun hasPacketStart(): Boolean {
        val startSub = if (inner.bi.startsWithContinued) 1 else 0
        return (inner.bi.packetPositions.size - startSub) > 0
    }
}

/**
 * A set of options that control details on how a [PageParser] parses Ogg pages.
 */
public class PageParsingOptions(
    /**
     * Whether the CRC checksum for the parsed pages will be verified to match
     * the page and packet data. Verifying it is the default behavior, as
     * a hash mismatch usually signals stream corruption, but specialized
     * applications may wish to deal with the contained data anyway.
     */
    public val verifyChecksum: Boolean = true,
) {
    public companion object {
        public fun default(): PageParsingOptions = PageParsingOptions(verifyChecksum = true)
    }
}

/**
 * Result of creating a [PageParser] via [PageParser.new] or [PageParser.newWithParseOpts].
 */
public class PageParserInit(
    public val parser: PageParser,
    public val segmentsCount: Int,
)

/**
 * Helper class for parsing pages.
 *
 * It's created using the [new] function and then it's fed more data via the [parseSegments]
 * and [parsePacketData] functions, each called exactly once and in that precise order.
 *
 * Then later code uses the [OggPage] returned by the [parsePacketData] function.
 */
public class PageParser internal constructor(
    internal val bi: PageBaseInfo,
    internal val streamSerial: UInt,
    internal val checksum: UInt,
    internal val headerBuf: ByteArray,
    internal var packetCount: UShort,
    internal var segmentsOrPacketsBuf: ByteArray,
    internal val parseOpts: PageParsingOptions,
) {
    public companion object {
        /**
         * Creates a new Page parser.
         *
         * The [headerBuf] param contains the first 27 bytes of a new Ogg page.
         * Determining when one begins is your responsibility. Usually they
         * begin directly after the end of a previous Ogg page, but
         * after you've performed a seek you might end up within the middle of a page
         * and need to recapture.
         *
         * Returns a [PageParserInit] containing the page parser and the requested size of the
         * segments array. You should allocate and fill such an array, in order to pass it to
         * the [parseSegments] function.
         */
        public fun new(headerBuf: ByteArray): PageParserInit =
            newWithParseOpts(headerBuf, PageParsingOptions.default())

        /**
         * Creates a new Page parser with the specified [parseOpts].
         */
        public fun newWithParseOpts(
            headerBuf: ByteArray,
            parseOpts: PageParsingOptions,
        ): PageParserInit {
            if (headerBuf.size < 27) {
                throw OggReadError.ReadError(
                    OggIoError(OggIoErrorKind.UnexpectedEof, "header buffer must be 27 bytes"),
                )
            }
            val headerRdr = ByteBufferCursor(headerBuf.copyOf(27))
            headerRdr.setPosition(4)
            val streamStructureVersion =
                try {
                    headerRdr.readU8()
                } catch (e: OggIoError) {
                    throw OggReadError.ReadError(e)
                }
            if (streamStructureVersion != 0u.toUByte()) {
                throw OggReadError.InvalidStreamStructVer(streamStructureVersion)
            }
            val headerTypeFlag = headerRdr.readU8()
            val absgp = headerRdr.readU64Le()
            val streamSerial = headerRdr.readU32Le()
            val sequenceNum = headerRdr.readU32Le()
            val checksum = headerRdr.readU32Le()
            val segmentsCount = headerRdr.readU8().toInt()

            val parser =
                PageParser(
                    bi =
                        PageBaseInfo(
                            startsWithContinued = (headerTypeFlag and 0x01u) != 0u.toUByte(),
                            firstPage = (headerTypeFlag and 0x02u) != 0u.toUByte(),
                            lastPage = (headerTypeFlag and 0x04u) != 0u.toUByte(),
                            absgp = absgp,
                            sequenceNum = sequenceNum,
                            checksum = checksum,
                            packetPositions = ArrayList(),
                            endsWithContinued = false,
                        ),
                    streamSerial = streamSerial,
                    checksum = checksum,
                    headerBuf = headerBuf.copyOf(27),
                    packetCount = 0u,
                    segmentsOrPacketsBuf = ByteArray(0),
                    parseOpts = parseOpts,
                )
            return PageParserInit(parser, segmentsCount)
        }
    }

    /**
     * Parses the segments buffer, and returns the requested size
     * of the packets content array.
     *
     * You should allocate and fill such an array, in order to pass it to the [parsePacketData]
     * function.
     */
    public fun parseSegments(segmentsBuf: ByteArray): Int {
        var pageSiz: UShort = 0u
        // Whether our page ends with a continued packet
        bi.endsWithContinued = bi.startsWithContinued

        // First run: get the number of packets,
        // whether the page ends with a continued packet,
        // and the size of the page's body
        for (b in segmentsBuf) {
            val v = b.toUByte()
            pageSiz = (pageSiz + v.toUShort()).toUShort()
            if (v < 255u) {
                packetCount = (packetCount + 1u).toUShort()
            }
            bi.endsWithContinued = v >= 255u
        }

        val capacity = packetCount.toInt() + (if (bi.endsWithContinued) 1 else 0)
        val packets = ArrayList<Pair<UShort, UShort>>(capacity)
        var curPacketSiz: UShort = 0u
        var curPacketOffs: UShort = 0u

        // Second run: get the offsets of the packets
        for (b in segmentsBuf) {
            val v = b.toUByte()
            curPacketSiz = (curPacketSiz + v.toUShort()).toUShort()
            if (v < 255u) {
                packets.add(Pair(curPacketOffs, curPacketSiz))
                curPacketOffs = (curPacketOffs + curPacketSiz).toUShort()
                curPacketSiz = 0u
            }
        }
        if (bi.endsWithContinued) {
            packets.add(Pair(curPacketOffs, curPacketSiz))
        }

        bi.packetPositions = packets
        segmentsOrPacketsBuf = segmentsBuf.copyOf()
        return pageSiz.toInt()
    }

    /**
     * Parses the packets data and verifies the checksum if appropriate.
     *
     * Returns an [OggPage] to be used by later code.
     */
    public fun parsePacketData(packetData: ByteArray): OggPage {
        if (parseOpts.verifyChecksum) {
            // 1. Clear the checksum field in the header buffer
            headerBuf[22] = 0
            headerBuf[23] = 0
            headerBuf[24] = 0
            headerBuf[25] = 0

            // 2. Calculate the hash
            var hashCalculated: UInt = vorbisCrc32Update(0u, headerBuf)
            hashCalculated = vorbisCrc32Update(hashCalculated, segmentsOrPacketsBuf)
            hashCalculated = vorbisCrc32Update(hashCalculated, packetData)

            // 3. Compare to the extracted one
            if (checksum != hashCalculated) {
                throw OggReadError.HashMismatch(checksum, hashCalculated)
            }
        }
        segmentsOrPacketsBuf = packetData.copyOf()
        return OggPage(this)
    }
}

/**
 * Low level struct for reading from an Ogg stream.
 *
 * Note that most times you'll want the higher level [PacketReader] struct.
 */
public class BasePacketReader {
    private val pageInfos: MutableMap<UInt, PageInfo> = mutableMapOf()
    private var streamWithStuff: UInt? = null
    private var hasSeeked: Boolean = false

    public companion object {
        public fun new(): BasePacketReader = BasePacketReader()
    }

    /**
     * Extracts a packet from the cache, if the cache contains valid packet data,
     * otherwise it returns `null`.
     */
    public fun readPacket(): Packet? {
        val strSerial = streamWithStuff ?: return null
        val pgInfo = pageInfos[strSerial] ?: return null
        val (offsU, lenU) = pgInfo.bi.packetPositions[pgInfo.packetIdx.toInt()]
        val offs = offsU.toInt()
        val len = lenU.toInt()

        // If there is a continued packet, and we are at the start right now,
        // and we actually have its end in the current page, glue it together.
        val needToGlue =
            pgInfo.packetIdx == 0u.toUByte() &&
                pgInfo.bi.startsWithContinued &&
                !(pgInfo.bi.endsWithContinued && pgInfo.bi.packetPositions.size == 1)

        val packetContent: ByteArray =
            if (needToGlue) {
                var siz = 0
                for (pck in pgInfo.lastOverlapPck) {
                    siz += pck.size
                }
                siz += len
                val cont = ByteArray(siz)
                var dstOff = 0
                for (pck in pgInfo.lastOverlapPck) {
                    pck.copyInto(cont, destinationOffset = dstOff)
                    dstOff += pck.size
                }
                pgInfo.lastOverlapPck = ArrayList()
                pgInfo.pageBody.copyInto(
                    cont,
                    destinationOffset = dstOff,
                    startIndex = offs,
                    endIndex = offs + len,
                )
                cont
            } else {
                pgInfo.pageBody.copyOfRange(offs, offs + len)
            }

        val firstPckInPg = pgInfo.isFirstPckInPg()
        val firstPckOverall = pgInfo.bi.firstPage && firstPckInPg

        val lastPckInPg = pgInfo.isLastPckInPg()
        val lastPckOverall = pgInfo.bi.lastPage && lastPckInPg

        // Update the last read index.
        pgInfo.packetIdx = (pgInfo.packetIdx + 1u).toUByte()
        // Set streamWithStuff to null so that future packet reads yield a page read first
        if (lastPckInPg) {
            streamWithStuff = null
        }

        return Packet(
            data = packetContent,
            firstPacketPg = firstPckInPg,
            firstPacketStream = firstPckOverall,
            lastPacketPg = lastPckInPg,
            lastPacketStream = lastPckOverall,
            absgpPage = pgInfo.bi.absgp,
            checksumPage = pgInfo.bi.checksum,
            streamSerial = strSerial,
        )
    }

    /**
     * Pushes a given Ogg page, updating the internal structures with its contents.
     */
    public fun pushPage(page: OggPage) {
        val pgPrs = page.inner
        val existingInf = pageInfos[pgPrs.streamSerial]
        if (existingInf != null) {
            if (pgPrs.bi.firstPage) {
                throw OggReadError.InvalidData()
            }
            if (pgPrs.bi.startsWithContinued != existingInf.bi.endsWithContinued) {
                if (!hasSeeked) {
                    throw OggReadError.InvalidData()
                } else {
                    // If we have seeked, we are more tolerant here,
                    // and just drop the continued packet's content.
                    existingInf.lastOverlapPck.clear()
                    if (pgPrs.bi.startsWithContinued) {
                        pgPrs.bi.packetPositions.removeAt(0)
                        if (pgPrs.packetCount != 0u.toUShort()) {
                            pgPrs.packetCount = (pgPrs.packetCount - 1u).toUShort()
                        } else {
                            pgPrs.bi.endsWithContinued = false
                        }
                    }
                }
            } else if (pgPrs.bi.startsWithContinued) {
                // Remember the packet at the end so that it can be glued together once
                // we encounter the next segment with length < 255
                val (offsU, lenU) = existingInf.bi.packetPositions[existingInf.packetIdx.toInt()]
                val offs = offsU.toInt()
                val len = lenU.toInt()
                if (len != existingInf.pageBody.size) {
                    val tmp = existingInf.pageBody.copyOfRange(offs, offs + len)
                    existingInf.lastOverlapPck.add(tmp)
                } else {
                    existingInf.lastOverlapPck.add(existingInf.pageBody)
                    existingInf.pageBody = ByteArray(0)
                }
            }
            existingInf.bi = pgPrs.bi
            existingInf.packetIdx = 0u
            existingInf.pageBody = pgPrs.segmentsOrPacketsBuf
        } else {
            if (!hasSeeked) {
                if (!pgPrs.bi.firstPage || pgPrs.bi.startsWithContinued) {
                    throw OggReadError.InvalidData()
                }
            } else {
                if (pgPrs.bi.startsWithContinued) {
                    pgPrs.bi.packetPositions.removeAt(0)
                    if (pgPrs.packetCount != 0u.toUShort()) {
                        pgPrs.packetCount = (pgPrs.packetCount - 1u).toUShort()
                    } else {
                        pgPrs.bi.endsWithContinued = false
                    }
                    pgPrs.bi.startsWithContinued = false
                }
            }
            pageInfos[pgPrs.streamSerial] =
                PageInfo(
                    bi = pgPrs.bi,
                    packetIdx = 0u,
                    pageBody = pgPrs.segmentsOrPacketsBuf,
                    lastOverlapPck = ArrayList(),
                )
        }
        val pgHasStuff = pgPrs.packetCount > 0u
        streamWithStuff = if (pgHasStuff) pgPrs.streamSerial else null
    }

    /**
     * Reset the internal state after a seek.
     */
    public fun updateAfterSeek() {
        streamWithStuff = null
        pageInfos.clear()
        hasSeeked = true
    }
}

private sealed class UntilPageHeaderReaderMode {
    data object Searching : UntilPageHeaderReaderMode()

    data class FoundWithNeeded(
        val needed: UByte,
    ) : UntilPageHeaderReaderMode()

    data class SeekNeeded(
        val offset: Int,
    ) : UntilPageHeaderReaderMode()

    data object Found : UntilPageHeaderReaderMode()
}

private enum class UntilPageHeaderResult {
    Eof,
    Found,
    ReadNeeded,
    SeekNeeded,
}

private class UntilPageHeaderReader {
    var mode: UntilPageHeaderReaderMode = UntilPageHeaderReaderMode.Searching
    var cptOf: UByte = 0u
    val retBuf: ByteArray = ByteArray(27)
    var readAmount: Int = 0

    fun checkArr(arr: ByteArray, length: Int): Int? {
        for (i in 0 until length) {
            val ch = arr[i].toInt() and 0xFF
            when {
                ch == 'O'.code -> {
                    cptOf = 1u
                }

                ch == 'g'.code && (cptOf == 1u.toUByte() || cptOf == 2u.toUByte()) -> {
                    cptOf = (cptOf + 1u).toUByte()
                }

                ch == 'S'.code && cptOf == 3u.toUByte() -> {
                    return i
                }

                else -> {
                    cptOf = 0u
                }
            }
        }
        return null
    }

    fun doRead(rdr: OggRead): UntilPageHeaderResult {
        val buf = ByteArray(1024)
        val maxReadLen =
            if (readAmount < 27) {
                27 - readAmount
            } else {
                when (val currentMode = mode) {
                    is UntilPageHeaderReaderMode.Searching -> buf.size
                    is UntilPageHeaderReaderMode.FoundWithNeeded -> currentMode.needed.toInt()
                    is UntilPageHeaderReaderMode.SeekNeeded -> return UntilPageHeaderResult.SeekNeeded
                    is UntilPageHeaderReaderMode.Found -> return UntilPageHeaderResult.Found
                }
            }

        val rdLen =
            try {
                rdr.read(buf, 0, maxReadLen)
            } catch (e: OggIoError) {
                throw OggReadError.ReadError(e)
            }

        if (rdLen == 0) {
            return UntilPageHeaderResult.Eof
        }
        readAmount += rdLen

        val readAmountMax = 150 * 1024
        if (readAmount > readAmountMax) {
            throw OggReadError.NoCapturePatternFound()
        }

        val off: Int
        val needed: Int
        when (val currentMode = mode) {
            is UntilPageHeaderReaderMode.Searching -> {
                val foundOff = checkArr(buf, rdLen)
                if (foundOff != null) {
                    retBuf[0] = 'O'.code.toByte()
                    retBuf[1] = 'g'.code.toByte()
                    retBuf[2] = 'g'.code.toByte()
                    retBuf[3] = 'S'.code.toByte()
                    off = foundOff
                    needed = 24
                } else {
                    return UntilPageHeaderResult.ReadNeeded
                }
            }

            is UntilPageHeaderReaderMode.FoundWithNeeded -> {
                off = 0
                needed = currentMode.needed.toInt()
            }

            else -> {
                error("unexpected mode in UntilPageHeaderReader.doRead")
            }
        }

        val fndBufLen = rdLen - off
        val copyAmount = min(needed, fndBufLen)
        val startFill = 27 - needed
        buf.copyInto(
            retBuf,
            destinationOffset = startFill,
            startIndex = off,
            endIndex = off + copyAmount,
        )

        return when {
            fndBufLen == needed -> {
                mode = UntilPageHeaderReaderMode.Found
                UntilPageHeaderResult.Found
            }

            fndBufLen < needed -> {
                val neededNew = needed - copyAmount
                mode = UntilPageHeaderReaderMode.FoundWithNeeded(neededNew.toUByte())
                UntilPageHeaderResult.ReadNeeded
            }

            else -> {
                mode = UntilPageHeaderReaderMode.SeekNeeded(needed - fndBufLen)
                UntilPageHeaderResult.SeekNeeded
            }
        }
    }

    fun doSeek(skr: OggSeek): UntilPageHeaderResult =
        when (val currentMode = mode) {
            is UntilPageHeaderReaderMode.Searching,
            is UntilPageHeaderReaderMode.FoundWithNeeded,
            -> {
                UntilPageHeaderResult.ReadNeeded
            }

            is UntilPageHeaderReaderMode.SeekNeeded -> {
                try {
                    skr.seek(OggSeekFrom.Current(currentMode.offset.toLong()))
                } catch (e: OggIoError) {
                    throw OggReadError.ReadError(e)
                }
                mode = UntilPageHeaderReaderMode.Found
                UntilPageHeaderResult.Found
            }

            is UntilPageHeaderReaderMode.Found -> {
                UntilPageHeaderResult.Found
            }
        }

    fun intoHeader(): ByteArray =
        when (mode) {
            is UntilPageHeaderReaderMode.Found -> retBuf
            else -> error("wrong mode")
        }
}

/**
 * Reader for packets from an Ogg stream.
 *
 * This reads codec packets belonging to several different logical streams from one physical
 * Ogg container stream.
 */
public class PacketReader(
    private var rdr: OggReadSeek,
    private val pgParseOpts: PageParsingOptions = PageParsingOptions.default(),
) {
    private val basePckRdr: BasePacketReader = BasePacketReader.new()
    private var readSomePg: Boolean = false

    public companion object {
        /** Constructs a new [PacketReader] with a given [OggReadSeek]. */
        public fun new(rdr: OggReadSeek): PacketReader =
            newWithPageParseOpts(rdr, PageParsingOptions.default())

        /** Constructs a new [PacketReader] with a given [OggReadSeek] and [PageParsingOptions]. */
        public fun newWithPageParseOpts(
            rdr: OggReadSeek,
            pgParseOpts: PageParsingOptions,
        ): PacketReader = PacketReader(rdr, pgParseOpts)
    }

    /** Returns the wrapped reader, consuming the [PacketReader]. */
    public fun intoInner(): OggReadSeek = rdr

    /** Returns a reference to the wrapped reader. */
    public fun getRef(): OggReadSeek = rdr

    /** Returns a mutable reference to the wrapped reader. */
    public fun getMut(): OggReadSeek = rdr

    /**
     * Reads a packet, and returns it on success.
     *
     * `null` is returned if the physical stream has ended.
     */
    public fun readPacket(): Packet? {
        while (true) {
            val pck = basePckRdr.readPacket()
            if (pck != null) {
                return pck
            }
            val page = readOggPage() ?: return null
            basePckRdr.pushPage(page)
        }
    }

    /**
     * Reads a packet, and returns it on success.
     *
     * The difference to the [readPacket] function is that this function
     * throws an [OggReadError.ReadError] if the physical stream has ended.
     */
    public fun readPacketExpected(): Packet {
        val p = readPacket()
        if (p != null) {
            return p
        }
        throw OggReadError.ReadError(
            OggIoError(
                OggIoErrorKind.UnexpectedEof,
                "Expected ogg packet but found end of physical stream",
            ),
        )
    }

    private fun readUntilPgHeader(): ByteArray? {
        val r = UntilPageHeaderReader()
        var res = r.doRead(rdr)
        while (true) {
            res =
                when (res) {
                    UntilPageHeaderResult.Eof -> {
                        return null
                    }

                    UntilPageHeaderResult.Found -> {
                        readSomePg = true
                        break
                    }

                    UntilPageHeaderResult.ReadNeeded -> {
                        r.doRead(rdr)
                    }

                    UntilPageHeaderResult.SeekNeeded -> {
                        r.doSeek(rdr)
                    }
                }
        }
        return r.intoHeader()
    }

    private fun readOggPage(): OggPage? {
        val headerBuf = readUntilPgHeader()
        if (headerBuf == null) {
            if (readSomePg) {
                return null
            }
            throw OggReadError.NoCapturePatternFound()
        }
        val init = PageParser.newWithParseOpts(headerBuf, pgParseOpts)
        val pgPrs = init.parser
        val pageSegments = init.segmentsCount

        val segmentsBuf = ByteArray(pageSegments)
        try {
            rdr.readExact(segmentsBuf)
        } catch (e: OggIoError) {
            throw OggReadError.ReadError(e)
        }

        val pageSiz = pgPrs.parseSegments(segmentsBuf)

        val packetData = ByteArray(pageSiz)
        try {
            rdr.readExact(packetData)
        } catch (e: OggIoError) {
            throw OggReadError.ReadError(e)
        }

        return pgPrs.parsePacketData(packetData)
    }

    /**
     * Seeks the underlying reader by the specified number of bytes.
     * All new pages will be read from the new position.
     *
     * This also flushes all the unread packets in the queue.
     */
    public fun seekBytes(pos: OggSeekFrom): ULong {
        val r = rdr.seek(pos)
        basePckRdr.updateAfterSeek()
        return r
    }

    private fun seekPos(pos: OggSeekFrom): ULong =
        try {
            rdr.seek(pos)
        } catch (e: OggIoError) {
            throw OggReadError.ReadError(e)
        }

    private fun pgReadMatchSerial(streamSerial: UInt?): Pair<ULong, OggPage>? {
        var pos: ULong
        var pg: OggPage
        var continuedPckStart: ULong? = null
        while (true) {
            pos = seekPos(OggSeekFrom.Current(0L))
            pg = readOggPage() ?: return null

            if (streamSerial != null && pg.inner.streamSerial != streamSerial) {
                continue
            }
            val startPos = continuedPckStart
            if (startPos == null) {
                if (pg.hasWholePacket()) {
                    break
                } else if (pg.hasPacketStart()) {
                    continuedPckStart = pos
                }
            } else if (pg.hasPacketEnd()) {
                pos = startPos
                break
            }
        }
        return Pair(pos, pg)
    }

    /**
     * Seeks to absolute granule pos.
     *
     * More specifically, it seeks to the first Ogg page
     * that has an `absgp` greater or equal to the specified one.
     * In the case of continued packets, the seek operation may also end up
     * at the last page that comes before such a page and has a packet start.
     */
    public fun seekAbsgp(streamSerial: UInt?, posGoal: ULong): Boolean {
        fun found(pos: ULong): Boolean {
            seekPos(OggSeekFrom.Start(pos))
            basePckRdr.updateAfterSeek()
            return true
        }

        fun abOf(pg: OggPage): ULong = pg.inner.bi.absgp

        fun seqOf(pg: OggPage): UInt = pg.inner.bi.sequenceNum

        // Seek to the start of the file to get the starting boundary
        seekPos(OggSeekFrom.Start(0uL))
        val beginMatch = pgReadMatchSerial(streamSerial) ?: return false
        var beginPos = beginMatch.first
        var beginPg = beginMatch.second

        // If the goal is the beginning, we are done.
        if (posGoal == 0uL) {
            return found(beginPos)
        }

        // Seek to the end of the file to get the ending boundary
        seekBeforeEnd(rdr, 200uL * 1024uL)
        var endPos: ULong
        var endPg: OggPage
        while (true) {
            val match = pgReadMatchSerial(streamSerial) ?: return false
            endPos = match.first
            endPg = match.second
            if (endPg.inner.bi.absgp == posGoal) {
                return found(endPos)
            }
            if (endPg.inner.bi.absgp > posGoal) {
                break
            }
            if (endPg.inner.bi.lastPage) {
                return false
            }
        }

        // Perform the bisection
        while (true) {
            if (seqOf(endPg) - seqOf(beginPg) <= 1u) {
                return found(endPos)
            }
            val posToSeek = beginPos + (endPos - beginPos) / 2uL
            seekPos(OggSeekFrom.Start(posToSeek))
            val match = pgReadMatchSerial(streamSerial) ?: return false
            val pos = match.first
            val pg = match.second

            if (seqOf(endPg) == seqOf(pg) || seqOf(beginPg) == seqOf(pg)) {
                // Switch to a linear seek to get the last details.
                var lastPacketEndPos = beginPos
                seekPos(OggSeekFrom.Start(beginPos))
                while (true) {
                    val linPos =
                        try {
                            rdr.streamPosition()
                        } catch (e: OggIoError) {
                            throw OggReadError.ReadError(e)
                        }
                    val linPg = readOggPage() ?: return false
                    when {
                        streamSerial != null && linPg.inner.streamSerial != streamSerial -> {
                            continue
                        }

                        abOf(linPg) == (-1L).toULong() -> {
                            continue
                        }

                        abOf(linPg) >= posGoal -> {
                            return found(lastPacketEndPos)
                        }

                        else -> {
                            if (linPg.hasPacketEnd()) {
                                lastPacketEndPos = linPos
                            }
                        }
                    }
                }
            }
            if (abOf(pg) >= posGoal) {
                endPos = pos
                endPg = pg
            } else {
                beginPos = pos
                beginPg = pg
            }
        }
    }

    /**
     * Resets the internal state by deleting all unread packets.
     */
    public fun deleteUnreadPackets() {
        basePckRdr.updateAfterSeek()
    }
}

private fun seekBeforeEnd(rdr: OggReadSeek, offs: ULong): ULong {
    val endPos =
        try {
            rdr.seek(OggSeekFrom.End(0L))
        } catch (e: OggIoError) {
            throw OggReadError.ReadError(e)
        }
    val endPosToSeek = if (endPos < offs) endPos else offs
    return try {
        rdr.seek(OggSeekFrom.End(-(endPosToSeek.toLong())))
    } catch (e: OggIoError) {
        throw OggReadError.ReadError(e)
    }
}

/**
 * Asynchronous byte source for [AsyncPacketReader].
 */
public interface OggAsyncRead {
    /**
     * Suspends and reads available bytes into [buf], returning the number of bytes read, or `0` on EOF.
     */
    public suspend fun read(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int
}

private sealed class PageDecodeState {
    data object Head : PageDecodeState()

    data class Segments(
        val parser: PageParser,
        val size: Int,
    ) : PageDecodeState()

    data class PacketData(
        val parser: PageParser,
        val size: Int,
    ) : PageDecodeState()

    data object InUpdate : PageDecodeState()

    fun neededSize(): Int =
        when (this) {
            is Head -> 27
            is Segments -> size
            is PacketData -> size
            is InUpdate -> error("invalid state")
        }
}

private class AsyncByteBuffer {
    private var buf: ByteArray = ByteArray(4096)
    private var readPos: Int = 0
    private var writePos: Int = 0

    val size: Int
        get() = writePos - readPos

    fun append(data: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        if (writePos + length > buf.size) {
            val currentLen = size
            if (currentLen + length <= buf.size) {
                buf.copyInto(buf, destinationOffset = 0, startIndex = readPos, endIndex = writePos)
                readPos = 0
                writePos = currentLen
            } else {
                var newCap = buf.size * 2
                while (newCap < currentLen + length) {
                    newCap *= 2
                }
                val newBuf = ByteArray(newCap)
                buf.copyInto(newBuf, destinationOffset = 0, startIndex = readPos, endIndex = writePos)
                buf = newBuf
                readPos = 0
                writePos = currentLen
            }
        }
        data.copyInto(buf, destinationOffset = writePos, startIndex = offset, endIndex = offset + length)
        writePos += length
    }

    fun splitTo(n: Int): ByteArray {
        val out = buf.copyOfRange(readPos, readPos + n)
        readPos += n
        if (readPos == writePos) {
            readPos = 0
            writePos = 0
        }
        return out
    }
}

private class PageDecoder(
    private val parseOpts: PageParsingOptions,
) {
    private var state: PageDecodeState = PageDecodeState.Head

    fun decode(buf: AsyncByteBuffer): OggPage? {
        while (true) {
            val neededSize = state.neededSize()
            if (buf.size < neededSize) {
                return null
            }
            var ret: OggPage? = null
            val consumedBuf = buf.splitTo(neededSize)
            val prevState = state
            state = PageDecodeState.InUpdate
            state =
                when (prevState) {
                    is PageDecodeState.Head -> {
                        val hdrBuf = consumedBuf.copyOf(27)
                        val init = PageParser.newWithParseOpts(hdrBuf, parseOpts)
                        PageDecodeState.Segments(init.parser, init.segmentsCount)
                    }

                    is PageDecodeState.Segments -> {
                        val newNeededLen = prevState.parser.parseSegments(consumedBuf)
                        PageDecodeState.PacketData(prevState.parser, newNeededLen)
                    }

                    is PageDecodeState.PacketData -> {
                        ret = prevState.parser.parsePacketData(consumedBuf)
                        PageDecodeState.Head
                    }

                    is PageDecodeState.InUpdate -> {
                        error("invalid state")
                    }
                }
            if (ret != null) {
                return ret
            }
        }
    }

    fun decodeEof(buf: AsyncByteBuffer): OggPage? = decode(buf)
}

/**
 * Async packet reading functionality corresponding to `ogg::reading::async_api::PacketReader`.
 */
public class AsyncPacketReader(
    private val inner: OggAsyncRead,
    pgParseOpts: PageParsingOptions = PageParsingOptions.default(),
) {
    private val basePckRdr: BasePacketReader = BasePacketReader.new()
    private val pgDecoder: PageDecoder = PageDecoder(pgParseOpts)
    private val readBuf: AsyncByteBuffer = AsyncByteBuffer()
    private val scratch: ByteArray = ByteArray(4096)
    private var eofReached: Boolean = false

    public companion object {
        /** Wraps the specified [OggAsyncRead] into an async Ogg packet reader. */
        public fun new(inner: OggAsyncRead): AsyncPacketReader =
            newWithPageParseOpts(inner, PageParsingOptions.default())

        /** Wraps the specified [OggAsyncRead] into an async Ogg packet reader with [pgParseOpts]. */
        public fun newWithPageParseOpts(
            inner: OggAsyncRead,
            pgParseOpts: PageParsingOptions,
        ): AsyncPacketReader = AsyncPacketReader(inner, pgParseOpts)
    }

    private suspend fun nextPage(): OggPage? {
        while (true) {
            val decoded =
                if (eofReached) {
                    pgDecoder.decodeEof(readBuf)
                } else {
                    pgDecoder.decode(readBuf)
                }
            if (decoded != null) {
                return decoded
            }
            if (eofReached) {
                return null
            }
            val n =
                try {
                    inner.read(scratch, 0, scratch.size)
                } catch (e: OggIoError) {
                    throw OggReadError.ReadError(e)
                }
            if (n <= 0) {
                eofReached = true
            } else {
                readBuf.append(scratch, 0, n)
            }
        }
    }

    /**
     * Suspends and reads the next [Packet] from the async stream, or returns `null` on EOF.
     */
    public suspend fun tryNext(): Packet? {
        while (true) {
            val pck = basePckRdr.readPacket()
            if (pck != null) {
                return pck
            }
            val page = nextPage() ?: return null
            basePckRdr.pushPage(page)
        }
    }

    /**
     * Exposes this async packet reader as a [Flow] of [Packet]s.
     */
    public fun asFlow(): Flow<Packet> =
        flow {
            while (true) {
                val pck = tryNext() ?: break
                emit(pck)
            }
        }
}
