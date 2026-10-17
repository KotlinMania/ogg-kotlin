package io.github.kotlinmania.ogg

import kotlin.math.min

/**
 * Ogg packet representation.
 *
 * For the Ogg format, packets are the logically smallest subdivision it handles.
 *
 * Every packet belongs to a logical bitstream. The logical bitstreams then form a physical
 * bitstream, with the data combined in multiple different ways.
 *
 * Every logical bitstream is identified by the serial number its pages have stored. The [Packet]
 * class contains a property for that number as well, so that one can find out which logical
 * bitstream the [Packet] belongs to.
 */
public class Packet(
    /** The data the [Packet] contains. */
    public val data: ByteArray,
    /** `true` iff this packet is the first one in the page. */
    internal val firstPacketPg: Boolean,
    /** `true` iff this packet is the first one in the logical bitstream. */
    internal val firstPacketStream: Boolean,
    /** `true` iff this packet is the last one in the page. */
    internal val lastPacketPg: Boolean,
    /** `true` iff this packet is the last one in the logical bitstream. */
    internal val lastPacketStream: Boolean,
    /**
     * Absolute granule position of the last page the packet was in.
     * The meaning of the absolute granule position is defined by the codec.
     */
    internal val absgpPage: ULong,
    /** Serial number uniquely identifying the logical bitstream. */
    internal val streamSerial: UInt,
    /** Checksum of the last page the packet was in. */
    internal val checksumPage: UInt,
) {
    /** Returns whether the packet is the first one starting in the page. */
    public fun firstInPage(): Boolean = firstPacketPg

    /** Returns whether the packet is the first one of the entire stream. */
    public fun firstInStream(): Boolean = firstPacketStream

    /** Returns whether the packet is the last one starting in the page. */
    public fun lastInPage(): Boolean = lastPacketPg

    /** Returns whether the packet is the last one of the entire stream. */
    public fun lastInStream(): Boolean = lastPacketStream

    /**
     * Returns the absolute granule position of the page the packet ended in.
     *
     * The meaning of the absolute granule position is defined by the codec.
     */
    public fun absgpPage(): ULong = absgpPage

    /** Returns the serial number that uniquely identifies the logical bitstream. */
    public fun streamSerial(): UInt = streamSerial

    /** Returns the checksum of the page the packet ended in. */
    public fun checksumPage(): UInt = checksumPage
}

/**
 * Categorizes I/O errors encountered while reading or writing Ogg streams.
 */
public enum class OggIoErrorKind {
    UnexpectedEof,
    InvalidInput,
    WouldBlock,
    Other,
}

/**
 * Represents an I/O error encountered while reading, writing, or seeking an Ogg stream.
 */
public class OggIoError(
    public val kind: OggIoErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Enumeration of possible methods to seek within an I/O stream.
 */
public sealed class OggSeekFrom {
    /** Sets the offset to the provided number of bytes from the start. */
    public data class Start(
        public val pos: ULong,
    ) : OggSeekFrom()

    /** Sets the offset to the size of this object plus the specified number of bytes. */
    public data class End(
        public val offset: Long,
    ) : OggSeekFrom()

    /** Sets the offset to the current position plus the specified number of bytes. */
    public data class Current(
        public val offset: Long,
    ) : OggSeekFrom()
}

/**
 * Trait for reading bytes from a source.
 */
public interface OggRead {
    /**
     * Pulls some bytes from this source into the specified buffer, returning how many bytes were read.
     */
    public fun read(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int

    /**
     * Reads the exact number of bytes required to fill the slice of [buf].
     */
    public fun readExact(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset) {
        var totalRead = 0
        while (totalRead < length) {
            val n = read(buf, offset + totalRead, length - totalRead)
            if (n <= 0) {
                throw OggIoError(
                    OggIoErrorKind.UnexpectedEof,
                    "failed to fill whole buffer",
                )
            }
            totalRead += n
        }
    }
}

/**
 * Trait for seeking within a byte stream.
 */
public interface OggSeek {
    /**
     * Seeks to an offset, in bytes, in a stream.
     */
    public fun seek(pos: OggSeekFrom): ULong

    /**
     * Returns the current seek position from the start of the stream.
     */
    public fun streamPosition(): ULong = seek(OggSeekFrom.Current(0L))
}

/**
 * Trait for writing bytes to a destination.
 */
public interface OggWrite {
    /**
     * Writes a buffer into this writer, returning how many bytes were written.
     */
    public fun write(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int

    /**
     * Attempts to write an entire buffer into this writer.
     */
    public fun writeAll(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset) {
        var totalWritten = 0
        while (totalWritten < length) {
            val n = write(buf, offset + totalWritten, length - totalWritten)
            if (n <= 0) {
                throw OggIoError(
                    OggIoErrorKind.Other,
                    "failed to write whole buffer",
                )
            }
            totalWritten += n
        }
    }

    /**
     * Flushes this output stream.
     */
    public fun flush() {}
}

/**
 * Combined [OggRead] and [OggSeek] interface.
 */
public interface OggReadSeek :
    OggRead,
    OggSeek

/**
 * Combined [OggWrite] and [OggSeek] interface.
 */
public interface OggWriteSeek :
    OggWrite,
    OggSeek

/**
 * In-memory cursor over a growable byte buffer implementing [OggReadSeek] and [OggWriteSeek].
 */
public class ByteBufferCursor(
    initialData: ByteArray = ByteArray(0),
) : OggReadSeek,
    OggWriteSeek {
    public constructor() : this(ByteArray(0))

    private var buffer: ByteArray = initialData.copyOf()
    private var length: Int = initialData.size
    private var pos: Int = 0

    public val position: Int
        get() = pos

    public val size: Int
        get() = length

    public fun setPosition(newPosition: Int) {
        pos = newPosition
    }

    public fun len(): Int = length

    public fun isEmpty(): Boolean = length == 0

    public fun clear() {
        length = 0
        pos = 0
    }

    public fun getRef(): ByteArray = buffer.copyOf(length)

    public fun toByteArray(): ByteArray = buffer.copyOf(length)

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= buffer.size) return
        var newCap = if (buffer.isEmpty()) 64 else buffer.size
        while (newCap < minCapacity) {
            newCap = newCap * 2
        }
        buffer = buffer.copyOf(newCap)
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int {
        if (length == 0 || pos >= this.length) {
            return 0
        }
        val available = this.length - pos
        val toRead = min(length, available)
        buffer.copyInto(buf, destinationOffset = offset, startIndex = pos, endIndex = pos + toRead)
        pos += toRead
        return toRead
    }

    override fun write(buf: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val endPos = pos + length
        ensureCapacity(endPos)
        buf.copyInto(buffer, destinationOffset = pos, startIndex = offset, endIndex = offset + length)
        pos = endPos
        if (pos > this.length) {
            this.length = pos
        }
        return length
    }

    override fun seek(pos: OggSeekFrom): ULong {
        val target: Long =
            when (pos) {
                is OggSeekFrom.Start -> pos.pos.toLong()
                is OggSeekFrom.End -> this.length.toLong() + pos.offset
                is OggSeekFrom.Current -> this.pos.toLong() + pos.offset
            }
        if (target < 0L) {
            throw OggIoError(
                OggIoErrorKind.InvalidInput,
                "invalid seek to a negative or overflowing position",
            )
        }
        this.pos = target.toInt()
        return target.toULong()
    }

    public fun readU8(): UByte {
        if (pos >= length) {
            throw OggIoError(OggIoErrorKind.UnexpectedEof, "unexpected EOF reading u8")
        }
        val b = buffer[pos].toUByte()
        pos += 1
        return b
    }

    public fun readU16Le(): UShort {
        if (pos + 2 > length) {
            throw OggIoError(OggIoErrorKind.UnexpectedEof, "unexpected EOF reading u16")
        }
        val b0 = buffer[pos].toUByte().toUInt()
        val b1 = buffer[pos + 1].toUByte().toUInt()
        pos += 2
        return (b0 or (b1 shl 8)).toUShort()
    }

    public fun readU32Le(): UInt {
        if (pos + 4 > length) {
            throw OggIoError(OggIoErrorKind.UnexpectedEof, "unexpected EOF reading u32")
        }
        val b0 = buffer[pos].toUByte().toUInt()
        val b1 = buffer[pos + 1].toUByte().toUInt()
        val b2 = buffer[pos + 2].toUByte().toUInt()
        val b3 = buffer[pos + 3].toUByte().toUInt()
        pos += 4
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    public fun readU64Le(): ULong {
        val lo = readU32Le().toULong()
        val hi = readU32Le().toULong()
        return lo or (hi shl 32)
    }

    public fun writeU8(v: UByte) {
        val endPos = pos + 1
        ensureCapacity(endPos)
        buffer[pos] = v.toByte()
        pos = endPos
        if (pos > length) length = pos
    }

    public fun writeU16Le(v: UShort) {
        val u = v.toUInt()
        val endPos = pos + 2
        ensureCapacity(endPos)
        buffer[pos] = (u and 0xFFu).toByte()
        buffer[pos + 1] = ((u shr 8) and 0xFFu).toByte()
        pos = endPos
        if (pos > length) length = pos
    }

    public fun writeI16Le(v: Short) {
        writeU16Le(v.toUShort())
    }

    public fun writeU32Le(v: UInt) {
        val endPos = pos + 4
        ensureCapacity(endPos)
        buffer[pos] = (v and 0xFFu).toByte()
        buffer[pos + 1] = ((v shr 8) and 0xFFu).toByte()
        buffer[pos + 2] = ((v shr 16) and 0xFFu).toByte()
        buffer[pos + 3] = ((v shr 24) and 0xFFu).toByte()
        pos = endPos
        if (pos > length) length = pos
    }

    public fun writeU64Le(v: ULong) {
        writeU32Le((v and 0xFFFFFFFFuL).toUInt())
        writeU32Le((v shr 32).toUInt())
    }
}
