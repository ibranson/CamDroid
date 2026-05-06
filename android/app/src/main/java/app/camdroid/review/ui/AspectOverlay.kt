package app.camdroid.review.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Aspect-ratio framing overlay. Draws translucent black bars on whichever
 * pair of sides falls outside the target ratio (letterbox, pillarbox, or
 * both for very-narrow sub-frames), plus a 1.5px opaque inner border that
 * crisply delimits the framing window.
 *
 * The overlay is screen-anchored — bars never move, regardless of how the
 * image inside is panned/zoomed. This is the standard "framing tool" model:
 * "would this shot work as an N:M print?" rather than an art-crop preview.
 *
 * Renders nothing if [ratio] is null.
 */
@Composable
fun AspectOverlay(
    ratio: AspectRatio?,
    modifier: Modifier = Modifier,
    barAlpha: Float = 0.65f,
) {
    if (ratio == null) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasW = size.width
        val canvasH = size.height
        if (canvasW <= 0f || canvasH <= 0f) return@Canvas

        val containerRatio = canvasW / canvasH
        val target = ratio.ratio

        val (frameW, frameH) = if (target > containerRatio) {
            // Frame is wider than the screen → full-width, letterbox top/bottom.
            canvasW to (canvasW / target)
        } else {
            // Frame is taller (or equal) → full-height, pillarbox left/right.
            (canvasH * target) to canvasH
        }

        val frameLeft = (canvasW - frameW) / 2f
        val frameTop = (canvasH - frameH) / 2f
        val frameRight = frameLeft + frameW
        val frameBottom = frameTop + frameH

        val barColor = Color.Black.copy(alpha = barAlpha)

        // Translucent bars on the four sides as needed. We draw them as full
        // strips along each axis to ensure no rounding gap shows through at
        // the corners.
        if (frameTop > 0f) {
            drawRect(barColor, topLeft = Offset(0f, 0f), size = Size(canvasW, frameTop))
            drawRect(
                barColor,
                topLeft = Offset(0f, frameBottom),
                size = Size(canvasW, canvasH - frameBottom),
            )
        }
        if (frameLeft > 0f) {
            drawRect(
                barColor,
                topLeft = Offset(0f, frameTop),
                size = Size(frameLeft, frameH),
            )
            drawRect(
                barColor,
                topLeft = Offset(frameRight, frameTop),
                size = Size(canvasW - frameRight, frameH),
            )
        }

        // Crisp opaque inner border framing the visible window. Stroke is
        // drawn centered on the rectangle's edges; using a small width keeps
        // it subtle but unambiguous against both bright and dim image areas.
        val borderWidth = 1.5.dp.toPx()
        drawRect(
            color = Color.Black,
            topLeft = Offset(frameLeft, frameTop),
            size = Size(frameW, frameH),
            style = Stroke(width = borderWidth),
        )
    }
}
