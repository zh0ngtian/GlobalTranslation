package com.example.globaltranslation.ui.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private data class FocusMarker(val point: Offset, val request: Long, val status: String)

@Composable
fun CameraPreview(
    flashEnabled: Boolean,
    modifier: Modifier = Modifier,
    focusEnabled: Boolean = true,
    onCaptureReady: (((Long) -> Unit)?) -> Unit,
    onCaptured: (Long, Bitmap) -> Unit,
    onCaptureError: (Long) -> Unit,
    onCameraError: () -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val captured by rememberUpdatedState(onCaptured)
    val failed by rememberUpdatedState(onCaptureError)
    val cameraError by rememberUpdatedState(onCameraError)
    val ready by rememberUpdatedState(onCaptureReady)
    val canFocus by rememberUpdatedState(focusEnabled)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var marker by remember { mutableStateOf<FocusMarker?>(null) }

    LaunchedEffect(marker) {
        if (marker?.status != "正在对焦") { delay(1800); marker = null }
    }
    LaunchedEffect(flashEnabled, camera) { camera?.cameraControl?.enableTorch(flashEnabled) }
    DisposableEffect(owner, previewView) {
        var disposed = false
        var focusRequest = 0L
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                previewView.performClick()
                val current = camera ?: return true
                if (!canFocus || disposed) return true
                val request = ++focusRequest
                val position = Offset(e.x, e.y)
                // PreviewView accounts for the visible crop, sensor orientation and display rotation.
                val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                if (!current.cameraInfo.isFocusMeteringSupported(action)) {
                    marker = FocusMarker(position, request, "此相机不支持点按对焦")
                    return true
                }
                marker = FocusMarker(position, request, "正在对焦")
                try {
                    val result = current.cameraControl.startFocusAndMetering(action)
                    result.addListener({
                        if (!disposed && request == focusRequest) {
                            val success = runCatching { result.get().isFocusSuccessful }.getOrDefault(false)
                            marker = FocusMarker(position, request, if (success) "已对焦" else "未能合焦，请重试")
                        }
                    }, mainExecutor)
                } catch (_: Exception) { marker = FocusMarker(position, request, "未能合焦，请重试") }
                return true
            }
        })
        previewView.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        future.addListener({
            previewView.doOnLayout {
                if (!disposed) {
                    try {
                        provider = future.get()
                        val viewPort = requireNotNull(previewView.viewPort)
                        val group = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview).addUseCase(imageCapture).build()
                        camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                        ready { token ->
                            imageCapture.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    try {
                                        val original = image.toBitmap()
                                        val crop = image.cropRect
                                        val rotation = image.imageInfo.rotationDegrees
                                        val bitmap = Bitmap.createBitmap(original, crop.left, crop.top, crop.width(), crop.height(),
                                            Matrix().apply { postRotate(rotation.toFloat()) }, true)
                                        if (bitmap !== original) original.recycle()
                                        mainExecutor.execute { if (!disposed) captured(token, bitmap) else bitmap.recycle() }
                                    } catch (_: Exception) { mainExecutor.execute { if (!disposed) failed(token) } }
                                    finally { image.close() }
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    mainExecutor.execute { if (!disposed) failed(token) }
                                }
                            })
                        }
                    } catch (_: Exception) { cameraError() }
                }
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            focusRequest++
            ready(null)
            previewView.setOnTouchListener(null)
            camera?.cameraControl?.cancelFocusAndMetering()
            camera?.cameraControl?.enableTorch(false)
            provider?.unbind(preview, imageCapture)
            camera = null
            executor.shutdown()
        }
    }
    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().testTag("camera_preview"))
        marker?.let { focus ->
            Canvas(Modifier.fillMaxSize().testTag("focus_indicator").semantics { contentDescription = focus.status }) {
                val side = 56f * density
                val color = when (focus.status) { "已对焦" -> Color(0xFF80E0A0); "正在对焦" -> Color(0xFFFFD166); else -> Color(0xFFFF8A80) }
                val center = Offset(focus.point.x.coerceIn(side / 2, size.width - side / 2),
                    focus.point.y.coerceIn(side / 2, size.height - side / 2))
                drawRect(color, center - Offset(side / 2, side / 2), Size(side, side), style = Stroke(2f * density))
                drawCircle(color, 2f * density, center)
            }
        }
    }
}
