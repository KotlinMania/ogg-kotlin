// port-lint: source src/crc.rs
package io.github.kotlinmania.ogg

/**
 * Implementation of the CRC algorithm with the Vorbis-specific parameters and setup.
 */
@OptIn(ExperimentalUnsignedTypes::class)
private val CRC_LOOKUP_ARRAY: UIntArray = lookupArray()

private fun getTblElem(idx: UInt): UInt {
    var r: UInt = idx shl 24
    var i = 0
    while (i < 8) {
        val msbMask = (-(((r shr 31) and 1u).toInt())).toUInt()
        r = (r shl 1) xor (msbMask and 0x04c11db7u)
        i += 1
    }
    return r
}

@OptIn(ExperimentalUnsignedTypes::class)
private fun lookupArray(): UIntArray {
    val lupArr = UIntArray(0x100)
    var i = 0
    while (i < 0x100) {
        lupArr[i] = getTblElem(i.toUInt())
        i += 1
    }
    return lupArr
}

/**
 * Computes the Vorbis CRC32 checksum of [array].
 */
public fun vorbisCrc32(array: ByteArray): UInt = vorbisCrc32Update(0u, array)

/**
 * Updates a running Vorbis CRC32 checksum [cur] with the bytes in [array].
 */
@OptIn(ExperimentalUnsignedTypes::class)
public fun vorbisCrc32Update(cur: UInt, array: ByteArray): UInt =
    vorbisCrc32Update(cur, array, 0, array.size)

/**
 * Updates a running Vorbis CRC32 checksum [cur] with a slice of [array] starting at [offset] of [length] bytes.
 */
@OptIn(ExperimentalUnsignedTypes::class)
public fun vorbisCrc32Update(cur: UInt, array: ByteArray, offset: Int, length: Int): UInt {
    var ret: UInt = cur
    val end = offset + length
    var i = offset
    while (i < end) {
        val av = array[i].toUByte().toUInt()
        val lookupIdx = (av xor (ret shr 24)).toInt()
        ret = (ret shl 8) xor CRC_LOOKUP_ARRAY[lookupIdx]
        i += 1
    }
    return ret
}
