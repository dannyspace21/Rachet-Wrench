// Photos.kt — keeps photos the right way up.
//
// Phone cameras usually save the picture's pixels sideways and add a small
// "rotate me 90° when you show this" note (the EXIF orientation tag).
// Android's basic image decoder ignores that note, so photos looked sideways
// or mirrored in the app.  Two fixes:
//   * uprightJpeg(): right after a photo is taken (or picked from the
//     gallery), the pixels are turned upright and saved again WITHOUT the
//     EXIF block.  Every later use — thumbnails, the PDF report, the upload,
//     text recognition — then sees an upright picture.  Dropping EXIF also
//     removes hidden details such as GPS location from the phone's copy.
//   * decodeUpright(): when showing a photo, the orientation note is still
//     honoured, so photos saved by older versions also show the right way up.
// Uses only Android's built-in android.media.ExifInterface (no extra library).
package com.ratchetwrench.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File

object Photos {

    /** The EXIF orientation of an image (ORIENTATION_NORMAL if there is none or it can't be read). */
    fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    fun orientationOf(file: File): Int = runCatching {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** The turn/flip that makes a picture with this EXIF orientation upright (null = already upright). */
    private fun matrixFor(orientation: Int): Matrix? = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            else -> return null
        }
    }

    /** Return [bmp] turned upright for [orientation] (the same bitmap if nothing to do). */
    fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = matrixFor(orientation) ?: return bmp
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Decode image bytes and turn the result upright (null if it isn't an image). */
    fun decodeUpright(bytes: ByteArray, sampleSize: Int = 1): Bitmap? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: return null
        return applyOrientation(bmp, orientationOf(bytes))
    }

    /** Decode an image file and turn the result upright. */
    fun decodeUpright(file: File, sampleSize: Int = 1): Bitmap? {
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize }) ?: return null
        return applyOrientation(bmp, orientationOf(file))
    }

    /**
     * Rewrite a JPEG so its pixels are upright and it carries no EXIF data.
     * Does nothing for files that are already upright or aren't JPEGs.
     * Safe: writes to a temporary file first and only then replaces the
     * original, so a failure leaves the original photo untouched.
     */
    fun uprightJpeg(file: File) {
        if (!file.isFile || file.extension.lowercase() !in setOf("jpg", "jpeg")) return
        val orientation = orientationOf(file)
        if (matrixFor(orientation) == null) return
        try {
            val bmp = decodeUpright(file) ?: return
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        } catch (e: OutOfMemoryError) {
            // Very large photo on a low-memory phone: keep the original;
            // decodeUpright() still shows it the right way up.
        } catch (e: Exception) { /* keep the original */ }
    }
}
