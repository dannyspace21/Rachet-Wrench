// Camera.kt — the in-app camera (CameraX), used for:
//   * VinScanScreen — reads the VIN barcode (door jamb / windshield / under
//                     the hood) with Google ML Kit barcode scanning, ON THE
//                     PHONE.  Only a VIN with a correct check digit is accepted.
//   * CaptureScreen — takes one or more photos (entry photos, door stickers).
//                     Photos are saved in the app's private folder.
// The camera permission is requested only when one of these screens opens.
package com.ratchetwrench.app.camera

import android.Manifest
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.ratchetwrench.app.data.Attachment
import com.ratchetwrench.app.domain.Vin
import com.ratchetwrench.app.ui.PhotoThumb
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.SecondaryButton
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows [content] once camera permission is granted; asks for it first. */
@Composable
fun CameraPermissionGate(onCancel: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (granted) content() else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Camera access is needed to scan. You can also type the VIN or pick photos from your gallery.")
        PrimaryButton("Allow camera") { ask.launch(Manifest.permission.CAMERA) }
        SecondaryButton("Cancel") { onCancel() }
    }
}

/** Live camera preview bound to this screen's lifecycle. */
@Composable
private fun CameraPreview(modifier: Modifier, analysis: ImageAnalysis? = null, capture: ImageCapture? = null) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *listOfNotNull(preview, analysis, capture).toTypedArray())
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    AndroidView({ view }, modifier)
}

/** VIN barcodes are usually Code 39 / Code 128 / Data Matrix.  Some add a
 *  leading "I" (import) or trailing check character — we try the variants. */
fun vinFromBarcode(raw: String): String? {
    val clean = raw.uppercase().filter { it.isLetterOrDigit() }
    val candidates = listOf(clean, clean.drop(1), clean.dropLast(1), clean.take(17))
    return candidates.map { Vin.normalize(it) }.firstOrNull { it.length == 17 && Vin.isValid(it) }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun VinScanScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    CameraPermissionGate(onCancel) {
        val ctx = LocalContext.current
        var lastSeen by remember { mutableStateOf<String?>(null) }
        var done by remember { mutableStateOf(false) }
        val executor = remember { Executors.newSingleThreadExecutor() }
        val scanner = remember {
            BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_PDF417).build())
        }
        val analysis = remember {
            ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { a ->
                a.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || done) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            for (c in codes) {
                                val raw = c.rawValue ?: continue
                                lastSeen = raw
                                val vin = vinFromBarcode(raw)
                                if (vin != null && !done) {
                                    done = true
                                    ContextCompat.getMainExecutor(ctx).execute { onResult(vin) }
                                }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
        }
        DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdown() } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(Modifier.fillMaxSize(), analysis = analysis)
            Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp).height(120.dp).border(3.dp, Color(0xFFFB7A35), RoundedCornerShape(16.dp)))
            Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Point at the VIN barcode", color = Color.White, fontWeight = FontWeight.Bold)
                Text(lastSeen?.let { "Seen: $it (not a valid VIN)" } ?: "Door jamb sticker, windshield corner, or under the hood. Works offline.", color = Color.White)
                SecondaryButton("Cancel") { onCancel() }
            }
        }
    }
}

@Composable
fun CaptureScreen(onDone: (List<String>) -> Unit, onCancel: () -> Unit) {
    CameraPermissionGate(onCancel) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        // rememberSaveable: the photos taken so far survive the screen being
        // rebuilt (e.g. turning the phone), instead of disappearing.
        val shots = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
            mutableStateListOf<String>()
        }
        var busy by remember { mutableStateOf(false) }
        val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }

        // Tell the camera how the phone is physically held (works even with
        // auto-rotate off), so sideways shots are saved sideways and upright
        // shots upright.
        DisposableEffect(Unit) {
            val listener = object : OrientationEventListener(ctx) {
                override fun onOrientationChanged(degrees: Int) {
                    if (degrees == ORIENTATION_UNKNOWN) return
                    capture.targetRotation = when (degrees) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    }
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(Modifier.fillMaxSize(), capture = capture)
            Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (shots.isEmpty()) "Take one or more photos" else "${shots.size} photo(s) taken", color = Color.White, fontWeight = FontWeight.Bold)
                // Small previews of the photos taken so far (newest first).
                if (shots.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shots.reversed().forEach { PhotoThumb(Attachment(id = it, localPath = it), 64.dp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(if (busy) "Saving…" else "Take photo", "camera", enabled = !busy) {
                        val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
                        val file = File(dir, "${UUID.randomUUID()}.jpg")
                        busy = true
                        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(ctx),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    scope.launch {
                                        // Turn the pixels upright (cameras save them sideways + an EXIF note).
                                        withContext(Dispatchers.IO) { Photos.uprightJpeg(file) }
                                        shots.add(file.absolutePath); busy = false
                                    }
                                }
                                override fun onError(exception: ImageCaptureException) { busy = false }
                            })
                    }
                    SecondaryButton(if (shots.isEmpty()) "Cancel" else "Done") { if (shots.isEmpty()) onCancel() else onDone(shots.toList()) }
                }
            }
        }
    }
}
