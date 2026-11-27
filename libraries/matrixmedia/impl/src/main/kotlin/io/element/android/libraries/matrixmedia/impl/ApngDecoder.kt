/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.matrixmedia.impl

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import okio.BufferedSource
import java.nio.ByteBuffer

/**
 * Haven: plays animated PNGs (APNG), which browsers (so Haven Desktop) animate but Android's image decoder shows as a still
 * of the first frame; many sticker and emoji packs are APNGs. Frames are decoded as they're drawn, at the size they're
 * drawn at, so a big sticker shown small doesn't hold full-size frames in memory.
 */
internal class ApngDecoder(private val source: ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val drawable = APNGDrawable(
            object : ByteBufferLoader() {
                override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
            }
        )
        return DecodeResult(image = drawable.asImage(), isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (isApng(result.source.source())) ApngDecoder(result.source) else null
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** A PNG with an `acTL` chunk before its image data (without consuming [source]). */
        fun isApng(source: BufferedSource): Boolean = runCatching {
            val peek = source.peek()
            if (!peek.request(8) || !peek.readByteArray(8).contentEquals(PNG_SIGNATURE)) return false
            repeat(64) {
                if (!peek.request(8)) return false
                val length = peek.readInt().toLong() and 0xffffffffL
                when (peek.readUtf8(4)) {
                    "acTL" -> return true
                    "IDAT", "IEND" -> return false
                }
                peek.skip(length + 4) // data and CRC
            }
            false
        }.getOrDefault(false)
    }
}
