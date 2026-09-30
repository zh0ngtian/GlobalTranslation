package com.example.globaltranslation.ui.camera

import android.graphics.Bitmap
import android.graphics.Matrix
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
private data class PhysicalLens(val id: String, val intrinsicZoomRatio: Float)
private data class ZoomStatus(
    val ratio: Float = 1f,
    val minRatio: Float = 1f,
    val maxRatio: Float = 1f,
    val physicalLens: PhysicalLens? = null,
)

private const val TELEPHOTO_MIN_RATIO = 1.5f
private const val LENS_SWITCH_BACK_HYSTERESIS = 0.85f

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
    val torchEnabled by rememberUpdatedState(flashEnabled)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var marker by remember { mutableStateOf<FocusMarker?>(null) }
    var zoomStatus by remember { mutableStateOf(ZoomStatus()) }
    var zoomHandler by remember { mutableStateOf<(Float) -> Unit>({}) }
    var focusHandler by remember { mutableStateOf<(Offset) -> Unit>({}) }

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
        var telephotoLenses = emptyList<PhysicalLens>()
        var activePhysicalLens: PhysicalLens? = null
        var defaultMinZoom = 1f
        var defaultMaxZoom = 1f
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }

        fun selectorFor(lens: PhysicalLens?): CameraSelector = if (lens == null) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .setPhysicalCameraId(lens.id)
                .build()
        }

        fun bindCamera(lens: PhysicalLens?, desiredGlobalZoom: Float): Boolean {
            val currentProvider = provider ?: return false
            return try {
                ready(null)
                camera?.cameraControl?.cancelFocusAndMetering()
                currentProvider.unbind(preview, imageCapture)
                val viewPort = requireNotNull(previewView.viewPort)
                val group = UseCaseGroup.Builder().setViewPort(viewPort)
                    .addUseCase(preview).addUseCase(imageCapture).build()
                val rebound = currentProvider.bindToLifecycle(owner, selectorFor(lens), group)
                camera = rebound
                activePhysicalLens = lens
                val state = rebound.cameraInfo.zoomState.value
                val intrinsic = lens?.intrinsicZoomRatio ?: 1f
                val localZoom = (desiredGlobalZoom / intrinsic).coerceIn(
                    state?.minZoomRatio ?: 1f,
                    state?.maxZoomRatio ?: 1f,
                )
                rebound.cameraControl.setZoomRatio(localZoom)
                rebound.cameraControl.enableTorch(torchEnabled)
                val globalMin = if (lens == null) state?.minZoomRatio ?: defaultMinZoom else defaultMinZoom
                val globalMax = maxOf(defaultMaxZoom, intrinsic * (state?.maxZoomRatio ?: 1f))
                zoomStatus = ZoomStatus(desiredGlobalZoom.coerceIn(globalMin, globalMax), globalMin, globalMax, lens)
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
            } catch (_: Exception) {
                false
            }
        }

        fun lensForZoom(target: Float): PhysicalLens? {
            val current = activePhysicalLens
            if (current != null && target < current.intrinsicZoomRatio &&
                target >= current.intrinsicZoomRatio * LENS_SWITCH_BACK_HYSTERESIS) {
                return current
            }
            return telephotoLenses.lastOrNull { it.intrinsicZoomRatio <= target }
        }

        fun changeZoom(scaleFactor: Float) {
            if (!canFocus || disposed) return
            val current = camera ?: return
            val state = current.cameraInfo.zoomState.value ?: return
            val target = (zoomStatus.ratio * scaleFactor)
                .coerceIn(zoomStatus.minRatio, zoomStatus.maxRatio)
            val desiredLens = lensForZoom(target)
            if (desiredLens != activePhysicalLens) {
                if (!bindCamera(desiredLens, target)) {
                    // Some vendors advertise a physical lens that cannot serve this use-case
                    // combination. Stop retrying it and restore the logical camera.
                    telephotoLenses = telephotoLenses.filterNot { it == desiredLens }
                    if (!bindCamera(null, target)) cameraError()
                }
                return
            }
            val intrinsic = activePhysicalLens?.intrinsicZoomRatio ?: 1f
            val localTarget = (target / intrinsic).coerceIn(state.minZoomRatio, state.maxZoomRatio)
            current.cameraControl.setZoomRatio(localTarget)
            zoomStatus = zoomStatus.copy(ratio = target, physicalLens = activePhysicalLens)
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
                    try {
                        provider = future.get()
                        if (!bindCamera(null, 1f)) throw IllegalStateException("Unable to bind camera")
                        val defaultInfo = requireNotNull(camera).cameraInfo
                        val initialZoom = defaultInfo.zoomState.value
                        defaultMinZoom = initialZoom?.minZoomRatio ?: 1f
                        defaultMaxZoom = initialZoom?.maxZoomRatio ?: 1f
                        telephotoLenses = if (defaultInfo.isLogicalMultiCameraSupported) {
                            defaultInfo.physicalCameraInfos.mapNotNull { info ->
                                val id = info.cameraSelector.physicalCameraId ?: return@mapNotNull null
                                val ratio = info.intrinsicZoomRatio
                                if (ratio >= TELEPHOTO_MIN_RATIO) PhysicalLens(id, ratio) else null
                            }.distinctBy { it.id }.sortedBy { it.intrinsicZoomRatio }
                        } else emptyList()
                        zoomStatus = ZoomStatus(
                            ratio = initialZoom?.zoomRatio ?: 1f,
                            minRatio = defaultMinZoom,
                            maxRatio = maxOf(
                                defaultMaxZoom,
                                telephotoLenses.maxOfOrNull { it.intrinsicZoomRatio } ?: 1f,
                            ),
                        )
                    } catch (_: Exception) { cameraError() }
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
            val usingTelephoto = zoomStatus.physicalLens != null
            Text(
                text = "%.1f×%s".format(zoomStatus.ratio, if (usingTelephoto) " · 长焦" else ""),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp)
                    .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("camera_zoom")
                    .semantics { contentDescription = if (usingTelephoto) "已切换长焦" else "相机缩放" },
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
