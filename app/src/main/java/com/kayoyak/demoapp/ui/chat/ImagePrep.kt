package com.kayoyak.demoapp.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Preparing a picked picture for the model.
 *
 * Two sizes are made from one file, and they exist for different reasons:
 *
 *  - what the model sees, downscaled so the longest edge is [MODEL_EDGE]. A phone
 *    photo is several thousand pixels wide, and every one of them costs vision
 *    tokens on a device that is already the bottleneck.
 *  - what the history keeps, a [THUMB_EDGE] square thumbnail. Conversations are
 *    written to disk and kept; storing the original would grow a session by
 *    megabytes per picture.
 *
 * Both are JPEG: the decoder accepts it everywhere, and a screenshot re-encoded
 * as JPEG is smaller than the PNG it came from.
 */
internal object ImagePrep {
    const val MODEL_EDGE = 1024
    const val THUMB_EDGE = 160

    /** The bytes handed to the engine, or null when the file cannot be decoded. */
    fun forModel(context: Context, uri: Uri): ByteArray? =
        encode(context, uri, MODEL_EDGE, 85)

    /** The data URI kept in the message, or null when the file cannot be decoded. */
    fun thumbnail(context: Context, uri: Uri): String? =
        encode(context, uri, THUMB_EDGE, 70)?.let { bytes ->
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

    private fun encode(context: Context, uri: Uri, edge: Int, quality: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, edge)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        val scaled = scaleToFit(decoded, edge)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== decoded) {
            scaled.recycle()
        }
        decoded.recycle()
        return out.toByteArray()
    }

    /** Power-of-two sampling first, so the bitmap never has to be fully decoded at full size. */
    private fun sampleSize(width: Int, height: Int, edge: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= edge && height / (sample * 2) >= edge) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToFit(source: Bitmap, edge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= edge) {
            return source
        }
        val ratio = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
