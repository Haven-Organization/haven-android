/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.emoji.impl.imagepacks

/** Whether an image file is animated, from its bytes (like Haven Desktop's blobIsAnimated): GIF, APNG or animated WebP. */
internal object AnimatedImageSniffer {
    fun isAnimated(bytes: ByteArray): Boolean = when {
        bytes.startsWith("GIF8") -> isAnimatedGif(bytes)
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes.startsWith("PNG", offset = 1) -> isApng(bytes)
        bytes.startsWith("RIFF") && bytes.startsWith("WEBP", offset = 8) -> isAnimatedWebp(bytes)
        else -> false
    }

    private fun ByteArray.startsWith(ascii: String, offset: Int = 0): Boolean =
        size >= offset + ascii.length && ascii.indices.all { this[offset + it] == ascii[it].code.toByte() }

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xff

    /** More than one image in the file (walking the blocks, so pixel data can't be mistaken for a block). */
    private fun isAnimatedGif(b: ByteArray): Boolean {
        if (b.size < 13) return false
        var i = 13
        if (b.u8(10) and 0x80 != 0) i += 3 * (1 shl ((b.u8(10) and 0x07) + 1)) // global colour table
        var frames = 0
        while (i < b.size) {
            when (b.u8(i)) {
                0x2C -> { // image descriptor
                    if (++frames > 1) return true
                    if (i + 10 > b.size) return false
                    val packed = b.u8(i + 9)
                    i += 10
                    if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 0x07) + 1)) // local colour table
                    i += 1 // LZW minimum code size
                    i = skipSubBlocks(b, i) ?: return false
                }
                0x21 -> i = skipSubBlocks(b, i + 2) ?: return false // extension
                0x3B -> return false // trailer
                else -> return false
            }
        }
        return false
    }

    private fun skipSubBlocks(b: ByteArray, start: Int): Int? {
        var i = start
        while (i < b.size) {
            val len = b.u8(i)
            i += 1 + len
            if (len == 0) return i
        }
        return null
    }

    /** An `acTL` chunk before the image data. */
    private fun isApng(b: ByteArray): Boolean {
        var i = 8
        while (i + 8 <= b.size) {
            val length = (b.u8(i) shl 24) or (b.u8(i + 1) shl 16) or (b.u8(i + 2) shl 8) or b.u8(i + 3)
            if (b.startsWith("acTL", i + 4)) return true
            if (b.startsWith("IDAT", i + 4)) return false
            if (length < 0) return false
            i += 12 + length
        }
        return false
    }

    /** The VP8X header's animation flag. */
    private fun isAnimatedWebp(b: ByteArray): Boolean =
        b.size > 20 && b.startsWith("VP8X", 12) && (b.u8(20) and 0x02) != 0
}
