// PhotosTest.kt — photos must come out the right way up.
// Makes a small two-colour "photo" stored sideways with an EXIF
// orientation note (exactly what phone cameras do), then checks that
// Photos.uprightJpeg() turns the pixels upright and drops the EXIF note.
package com.ratchetwrench.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import com.ratchetwrench.app.camera.Photos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 80x40 image: left half red, right half blue, saved as JPEG with this EXIF orientation. */
    private fun sidewaysPhoto(orientation: Int): File {
        val bmp = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        for (x in 0 until 80) for (y in 0 until 40) bmp.setPixel(x, y, if (x < 40) Color.RED else Color.BLUE)
        val f = tmp.newFile("photo.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        ExifInterface(f.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        return f
    }

    private fun isRed(c: Int) = Color.red(c) > 200 && Color.blue(c) < 60
    private fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 60

    @Test
    fun portraitPhotoIsTurnedUpright() {
        // "Rotate 90° clockwise to view" — what a phone held upright saves.
        val f = sidewaysPhoto(ExifInterface.ORIENTATION_ROTATE_90)
        Photos.uprightJpeg(f)
        val out = BitmapFactory.decodeFile(f.path)
        assertEquals("now portrait: width", 40, out.width)
        assertEquals("now portrait: height", 80, out.height)
        // Turning clockwise puts the old left (red) half on top.
        assertTrue("top is red", isRed(out.getPixel(20, 10)))
        assertTrue("bottom is blue", isBlue(out.getPixel(20, 70)))
        assertTrue("EXIF note removed", Photos.orientationOf(f) in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
    }

    @Test
    fun mirroredPhotoIsUnflipped() {
        val f = sidewaysPhoto(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        Photos.uprightJpeg(f)
        val out = BitmapFactory.decodeFile(f.path)
        assertEquals(80, out.width)
        assertTrue("left is now blue", isBlue(out.getPixel(10, 20)))
        assertTrue("right is now red", isRed(out.getPixel(70, 20)))
    }

    @Test
    fun uprightPhotoIsLeftAlone() {
        val f = sidewaysPhoto(ExifInterface.ORIENTATION_NORMAL)
        val before = f.readBytes()
        Photos.uprightJpeg(f)
        assertTrue("file unchanged", before.contentEquals(f.readBytes()))
    }

    @Test
    fun thumbnailsHonourTheNoteForOlderPhotos() {
        // Photos saved by older app versions still carry the EXIF note.
        val f = sidewaysPhoto(ExifInterface.ORIENTATION_ROTATE_270)
        val shown = Photos.decodeUpright(f.readBytes())!!
        assertEquals(40, shown.width)
        assertEquals(80, shown.height)
        // Turning counter-clockwise puts the old right (blue) half on top.
        assertTrue("top is blue", isBlue(shown.getPixel(20, 10)))
    }
}
