package com.example.skyedge.ui.inspection

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot

fun Modifier.correctionGestures(
    enabled: Boolean,
    viewSize: IntSize,
    imageWidth: Int,
    imageHeight: Int,
    onTap: (x: Float, y: Float) -> Unit,
    onBox: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit,
    onDragPreview: (start: Offset?, end: Offset?) -> Unit = { _, _ -> },
): Modifier {
    if (!enabled || viewSize.width <= 0 || viewSize.height <= 0) return this
    return pointerInput(viewSize, imageWidth, imageHeight) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            var end = start
            var totalMove = 0f
            onDragPreview(start, end)
            val dragged = drag(down.id) { change ->
                val delta = change.position - change.previousPosition
                totalMove += hypot(delta.x.toDouble(), delta.y.toDouble()).toFloat()
                end = change.position
                onDragPreview(start, end)
                change.consume()
            }
            onDragPreview(null, null)
            if (!dragged || totalMove < viewConfiguration.touchSlop) {
                InspectionImageMapper
                    .mapToImagePixel(start, viewSize, imageWidth, imageHeight)
                    ?.let { (x, y) -> onTap(x, y) }
            } else {
                InspectionImageMapper
                    .boxFromDrag(start, end, viewSize, imageWidth, imageHeight)
                    ?.let { box -> onBox(box[0], box[1], box[2], box[3]) }
            }
        }
    }
}
