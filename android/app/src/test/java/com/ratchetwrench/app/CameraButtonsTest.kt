// CameraButtonsTest.kt — the camera screens' buttons must be clearly visible
// on the dark panel over the live picture, in light AND dark mode (they used
// to take the theme's text colour and disappear).  Renders the panel exactly
// as the VIN scanner draws it and checks the button text is bright, and saves
// a picture of each theme to build/camera-shots/.
package com.ratchetwrench.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ratchetwrench.app.camera.OverlayButton
import com.ratchetwrench.app.ui.RatchetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraButtonsTest {
    @get:Rule val rule = createComposeRule()

    private fun check(theme: String) {
        rule.setContent {
            RatchetTheme(theme) {
                Box(Modifier.size(411.dp, 260.dp).background(Color.Black)) {
                    Column(Modifier.padding(16.dp).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(16.dp)) {
                        Text("Point at the VIN barcode or the printed VIN", color = Color.White)
                        Row(Modifier.testTag("buttons"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OverlayButton("Flashlight", "sun") {}
                            OverlayButton("Cancel") {}
                        }
                    }
                }
            }
        }
        val img = rule.onNodeWithTag("buttons").captureToImage().asAndroidBitmap()
        java.io.File("build/camera-shots").mkdirs()
        java.io.FileOutputStream("build/camera-shots/$theme.png").use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // The button TEXT must be bright: count near-white pixels in the middle
        // band where the words are (the outline alone isn't enough).
        var bright = 0
        for (x in 0 until img.width) for (y in img.height * 35 / 100 until img.height * 65 / 100) {
            val p = img.getPixel(x, y)
            if ((p shr 16 and 0xff) > 200 && (p shr 8 and 0xff) > 200 && (p and 0xff) > 200) bright++
        }
        assertTrue("$theme: buttons are hard to see ($bright bright pixels)", bright > 1500)
    }

    @Test fun visibleInLightMode() = check("light")
    @Test fun visibleInDarkMode() = check("dark")
}
