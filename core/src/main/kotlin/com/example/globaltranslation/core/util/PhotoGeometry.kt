package com.example.globaltranslation.core.util

import com.example.globaltranslation.core.model.TextBounds

data class PhotoTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun map(bounds: TextBounds) = TextBounds(bounds.left * scale + offsetX, bounds.top * scale + offsetY,
        bounds.right * scale + offsetX, bounds.bottom * scale + offsetY)
}

fun fitPhoto(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): PhotoTransform {
    require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
    val scale = minOf(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
    return PhotoTransform(scale, (viewWidth - imageWidth * scale) / 2f, (viewHeight - imageHeight * scale) / 2f)
}
