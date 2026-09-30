package com.example.globaltranslation.ui.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private data class FocusMarker(val point: Offset, val request: Long, val status: String)
private data class ZoomStatus(
    val ratio: Float = 1f,
    val minRatio: Float = 1f,
    val maxRatio: Float = 1f,
)

private const val CAMERA_LOG_TAG = "GT.Camera"

@Composable
fun CameraPreview(
    flashEnabled: Boolean,
    modifier: Modifier = Modifier,
    focusEnabled: Boolean = true,
    onCaptureReady: (((Long) -> Unit)?) -> Unit,
    onCaptured: (Long, Bitmap) -> Unit,
    onCaptureError: (Long) -> Unit,
    onCameraError: (String) -> Unit,
    onCameraNotice: (String) -> Unit,
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
    val cameraNotice by rememberUpdatedState(onCameraNotice)
    val ready by rememberUpdatedState(onCaptureReady)
    val canFocus by rememberUpdatedState(focusEnabled)
    val torchEnabled by rememberUpdatedState(flashEnabled)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var marker by remember { mutableStateOf<FocusMarker?>(null) }
    var zoomStatus by remember { mutableStateOf(ZoomStatus()) }
    var zoomHandler by remember { mutableStateOf<(Float) -> Unit>({}) }
    var focusHandler by remember { mutableStateOf<(Offset) -> Unit>({}) }

    LaunchedEffect(marker) {
        if (marker?.status != "正在对焦") { delay(1800); marker = null }
    }
    LaunchedEffect(flashEnabled, camera) {
        val activeCamera = camera ?: return@LaunchedEffect
        try {
            val request = activeCamera.cameraControl.enableTorch(flashEnabled)
            request.addListener({
                runCatching { request.get() }.onFailure { failure ->
                    val cause = failure.cause ?: failure
                    val requestIsStale = camera !== activeCamera
                    if (requestIsStale || cause is CameraControl.OperationCanceledException) {
                        Log.d(CAMERA_LOG_TAG, "Torch request cancelled during camera transition; enabled=$flashEnabled")
                    } else {
                        Log.w(CAMERA_LOG_TAG, "[CAM-201] Torch request failed; enabled=$flashEnabled", failure)
                        if (flashEnabled) cameraNotice("相机提示 CAM-201：无法开启补光灯，仍可继续拍照。")
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (failure: Exception) {
            Log.w(CAMERA_LOG_TAG, "[CAM-201] Torch request could not be submitted; enabled=$flashEnabled", failure)
            if (flashEnabled) cameraNotice("相机提示 CAM-201：无法开启补光灯，仍可继续拍照。")
        }
    }
    DisposableEffect(owner, previewView) {
        var disposed = false
        var focusRequest = 0L
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var lastBindFailure: Throwable? = null
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }

        fun reportError(code: String, message: String, failure: Throwable?) {
            Log.e(CAMERA_LOG_TAG, "[$code] $message", failure)
            cameraError("相机错误 $code：$message")
        }

        fun reportNotice(code: String, message: String, failure: Throwable? = null) {
            Log.w(CAMERA_LOG_TAG, "[$code] $message", failure)
            cameraNotice("相机提示 $code：$message")
        }

        fun bindCamera(desiredZoom: Float): Boolean {
            val currentProvider = provider ?: return false
            return try {
                ready(null)
                camera?.cameraControl?.cancelFocusAndMetering()
                currentProvider.unbind(preview, imageCapture)
                val viewPort = requireNotNull(previewView.viewPort)
                val group = UseCaseGroup.Builder().setViewPort(viewPort)
                    .addUseCase(preview).addUseCase(imageCapture).build()
                val rebound = currentProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                lastBindFailure = null
                camera = rebound
                val state = runCatching { rebound.cameraInfo.zoomState.value }.getOrElse { failure ->
                    reportNotice("CAM-101", "无法读取相机缩放范围，将暂时使用 1.0 倍。", failure)
                    null
                }
                val appliedZoom = desiredZoom.coerceIn(
                    state?.minZoomRatio ?: 1f,
                    state?.maxZoomRatio ?: 1f,
                )
                rebound.cameraControl.setZoomRatio(appliedZoom)
                zoomStatus = ZoomStatus(
                    ratio = appliedZoom,
                    minRatio = state?.minZoomRatio ?: 1f,
                    maxRatio = state?.maxZoomRatio ?: 1f,
                )
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
                true
            } catch (failure: Exception) {
                lastBindFailure = failure
                Log.e(
                    CAMERA_LOG_TAG,
                    "[CAM-BIND] Failed to bind default back camera",
                    failure,
                )
                false
            }
        }

        fun changeZoom(scaleFactor: Float) {
            if (!canFocus || disposed) return
            val current = camera ?: return
            val state = current.cameraInfo.zoomState.value ?: return
            val target = (zoomStatus.ratio * scaleFactor)
                .coerceIn(zoomStatus.minRatio, zoomStatus.maxRatio)
            current.cameraControl.setZoomRatio(target.coerceIn(state.minZoomRatio, state.maxZoomRatio))
            zoomStatus = zoomStatus.copy(ratio = target)
        }
        zoomHandler = ::changeZoom

        fun focusAt(position: Offset) {
            previewView.performClick()
            val current = camera ?: return
            if (!canFocus || disposed) return
            val request = ++focusRequest
            // PreviewView accounts for the visible crop, sensor orientation and display rotation.
            val point = previewView.meteringPointFactory.createPoint(position.x, position.y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            if (!current.cameraInfo.isFocusMeteringSupported(action)) {
                marker = FocusMarker(position, request, "此相机不支持点按对焦")
                return
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
            } catch (_: Exception) {
                marker = FocusMarker(position, request, "未能合焦，请重试")
            }
        }
        focusHandler = ::focusAt
        future.addListener({
            previewView.doOnLayout {
                if (!disposed) {
                    val resolvedProvider = try {
                        future.get()
                    } catch (failure: Exception) {
                        reportError(
                            "CAM-001",
                            "相机服务初始化失败；请重新打开应用，若仍失败请重启手机。",
                            failure,
                        )
                        return@doOnLayout
                    }
                    provider = resolvedProvider
                    if (previewView.viewPort == null) {
                        reportError("CAM-002", "相机取景区域尚未准备好；请返回后重试。", null)
                        return@doOnLayout
                    }
                    if (!bindCamera(1f)) {
                        reportError(
                            "CAM-003",
                            "无法连接后置相机；请检查相机权限，并确认其他应用没有占用摄像头。",
                            lastBindFailure,
                        )
                        return@doOnLayout
                    }

                }
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            focusRequest++
            ready(null)
            camera?.cameraControl?.cancelFocusAndMetering()
            camera?.cameraControl?.enableTorch(false)
            provider?.unbind(preview, imageCapture)
            camera = null
            zoomHandler = {}
            focusHandler = {}
            executor.shutdown()
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().testTag("camera_preview").semantics {
                contentDescription = String.format(
                    Locale.ROOT,
                    "相机取景，当前 %.1f 倍，范围 %.1f 到 %.1f 倍",
                    zoomStatus.ratio,
                    zoomStatus.minRatio,
                    zoomStatus.maxRatio,
                )
            },
        )
        Box(
            Modifier.fillMaxSize().testTag("camera_gestures").pointerInput(zoomHandler) {
                detectTransformGestures(panZoomLock = true) { _, _, zoom, _ ->
                    if (zoom != 1f) zoomHandler(zoom)
                }
            }.pointerInput(focusHandler) {
                detectTapGestures(onTap = focusHandler)
            },
        )
        if (zoomStatus.ratio !in 0.98f..1.02f) {
            Text(
                text = "%.1f×".format(zoomStatus.ratio),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp)
                    .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("camera_zoom")
                    .semantics { contentDescription = "相机缩放" },
            )
        }
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
