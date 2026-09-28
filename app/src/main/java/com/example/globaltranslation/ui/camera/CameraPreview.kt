package com.example.globaltranslation.ui.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    flashEnabled: Boolean,
    modifier: Modifier = Modifier,
    onCaptureReady: (((Long) -> Unit)?) -> Unit,
    onCaptured: (Long, Bitmap) -> Unit,
    onCaptureError: (Long) -> Unit,
    onCameraError: () -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val captured by rememberUpdatedState(onCaptured)
    val failed by rememberUpdatedState(onCaptureError)
    val cameraError by rememberUpdatedState(onCameraError)
    val ready by rememberUpdatedState(onCaptureReady)
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(flashEnabled, camera) { camera?.cameraControl?.enableTorch(flashEnabled) }
    DisposableEffect(owner, previewView) {
        var disposed = false
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        future.addListener({
            if (!disposed) {
                try {
                    provider = future.get()
                    camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    ready { token ->
                        imageCapture.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                try {
                                    val original = image.toBitmap()
                                    val rotation = image.imageInfo.rotationDegrees
                                    val bitmap = if (rotation == 0) original else Bitmap.createBitmap(original, 0, 0,
                                        original.width, original.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                                    if (bitmap !== original) original.recycle()
                                    mainExecutor.execute { if (!disposed) captured(token, bitmap) }
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
        }, mainExecutor)
        onDispose {
            disposed = true
            ready(null)
            camera?.cameraControl?.enableTorch(false)
            provider?.unbind(preview, imageCapture)
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
