package com.example.globaltranslation.core.model

/** Pixels returned by a region reader are caller-owned. Coordinates always refer to the preview. */
data class PhotoRecognitionRegion(val pixels: Any, val bounds: TextBounds)
data class PhotoRecognitionInput(
    val preview: Any,
    val originalScale: Float = 1f,
    val readRegion: (suspend (TextBounds) -> PhotoRecognitionRegion)? = null
)
