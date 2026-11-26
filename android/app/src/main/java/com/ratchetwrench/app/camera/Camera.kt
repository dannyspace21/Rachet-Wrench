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
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.TextButton
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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ratchetwrench.app.data.Attachment
import com.ratchetwrench.app.domain.VinFinder
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

/**
 * Live camera preview bound to this screen's lifecycle.
 * [onCamera] receives the running camera (for the flashlight and zoom).
 * Tapping the preview focuses on that spot.
 */
@android.annotation.SuppressLint("ClickableViewAccessibility")
@Composable
private fun CameraPreview(modifier: Modifier, analysis: ImageAnalysis? = null, capture: ImageCapture? = null,
                          onCamera: (Camera) -> Unit = {}) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            provider.unbindAll()
            val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *listOfNotNull(preview, analysis, capture).toTypedArray())
            onCamera(camera)
            // Tap to focus (and meter light) on the tapped spot.
            view.setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    val point = view.meteringPointFactory.createPoint(e.x, e.y)
                    camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    v.performClick()
                }
                true
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    AndroidView({ view }, modifier)
}

/** Kept for existing callers/tests: see VinFinder.fromBarcode. */
fun vinFromBarcode(raw: String): String? = VinFinder.fromBarcode(raw)

/**
 * VIN scanner.  Each camera frame (analysed at up to 1920x1080 — the default
 * 640x480 is too coarse for the thin bars of a VIN barcode) is checked TWICE,
 * on the phone, offline:
 *   1. as a barcode  — a valid VIN is used straight away;
 *   2. as text       — for a printed VIN.  Text can be misread, so a VIN
 *                      found this way must be read the same in two frames,
 *                      and you confirm it before it's used.
 * Buttons: flashlight, zoom slider; tap the picture to focus.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VinScanScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    CameraPermissionGate(onCancel) {
        val ctx = LocalContext.current
        var status by remember { mutableStateOf<String?>(null) }
        var proposed by remember { mutableStateOf<String?>(null) } // text result awaiting confirmation
        var camera by remember { mutableStateOf<Camera?>(null) }
        var torch by remember { mutableStateOf(false) }
        // Shared with the analysis thread.
        val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        val paused = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        val textHits = remember { java.util.concurrent.ConcurrentHashMap<String, Int>() }
        val executor = remember { Executors.newSingleThreadExecutor() }
        val scanner = remember {
            BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_PDF417).build())
        }
        val reader = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        val main = remember { ContextCompat.getMainExecutor(ctx) }
        val analysis = remember {
            ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { a ->
                a.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || done.get() || paused.get()) { proxy.close(); return@setAnalyzer }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    // 1) Barcode.
                    scanner.process(image).addOnCompleteListener { task ->
                        val codes = if (task.isSuccessful) task.result.orEmpty() else emptyList()
                        for (c in codes) {
                            val raw = c.rawValue ?: continue
                            val vin = VinFinder.fromBarcode(raw)
                            if (vin != null && done.compareAndSet(false, true)) { main.execute { onResult(vin) }; break }
                            main.execute { status = "Barcode seen: $raw (not a valid VIN)" }
                        }
                        if (done.get()) { proxy.close(); return@addOnCompleteListener }
                        // 2) Printed text.
                        reader.process(image).addOnSuccessListener { text ->
                            for (vin in VinFinder.fromText(text.text)) {
                                val n = textHits.merge(vin, 1, Int::plus) ?: 1
                                if (n >= 2 && !done.get() && paused.compareAndSet(false, true)) main.execute { proposed = vin }
                            }
                        }.addOnCompleteListener { proxy.close() }
                    }
                }
            }
        }
        DisposableEffect(Unit) { onDispose { scanner.close(); reader.close(); executor.shutdown() } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(Modifier.fillMaxSize(), analysis = analysis, onCamera = { camera = it })
            Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp).height(120.dp).border(3.dp, Color(0xFFFB7A35), RoundedCornerShape(16.dp)))
            Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Point at the VIN barcode or the printed VIN", color = Color.White, fontWeight = FontWeight.Bold)
                Text(status ?: "Fill the box with the barcode. Tap the picture to focus. Turning the phone sideways helps with long barcodes.",
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
                camera?.let { cam ->
                    val zoomState = remember(cam) { SliderState(value = 0f) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Zoom", color = Color.White)
                        Slider(zoomState, onValueChange = { v -> zoomState.value = v; cam.cameraControl.setLinearZoom(v) }, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (camera?.cameraInfo?.hasFlashUnit() == true) {
                        SecondaryButton(if (torch) "Light off" else "Flashlight", "sun") {
                            torch = !torch; camera?.cameraControl?.enableTorch(torch)
                        }
                    }
                    SecondaryButton("Cancel") { onCancel() }
                }
            }
        }
        // A VIN read from printed text: show it so it can be checked first.
        proposed?.let { vin ->
            AlertDialog(onDismissRequest = { proposed = null; textHits.clear(); paused.set(false) },
                title = { Text("Is this the VIN?") },
                text = { Text(vin.chunked(4).joinToString(" "), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                confirmButton = { TextButton({ done.set(true); proposed = null; onResult(vin) }) { Text("Use this VIN") } },
                dismissButton = { TextButton({ proposed = null; textHits.clear(); paused.set(false) }) { Text("Keep scanning") } })
        }
    }
}

/**
 * Scans a "connect your phone" QR code (shown by the desktop app or in the
 * web app's Settings).  Only codes that start with ratchetwrench://pair are
 * accepted; the phone reads it ON THE PHONE (ML Kit, offline).
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun PairScanScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    CameraPermissionGate(onCancel) {
        val ctx = LocalContext.current
        var seenOther by remember { mutableStateOf(false) }
        val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        val executor = remember { Executors.newSingleThreadExecutor() }
        val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
        val analysis = remember {
            ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { a ->
                a.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || done.get()) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            for (c in codes) {
                                val raw = c.rawValue ?: continue
                                if (raw.startsWith("ratchetwrench://pair?") && done.compareAndSet(false, true)) {
                                    ContextCompat.getMainExecutor(ctx).execute { onResult(raw) }
                                } else seenOther = true
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
        }
        DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdown() } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(Modifier.fillMaxSize(), analysis = analysis)
            Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scan the QR code on your computer", color = Color.White, fontWeight = FontWeight.Bold)
                Text(if (seenOther) "That QR code isn't a Ratchet Wrench connect code." else
                    "Desktop app: “Connect a phone (QR code)”.  Web app: Settings → Connect a phone.",
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
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
