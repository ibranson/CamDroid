package app.camdroid.review.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Resistance factor for rubber-band over-pan: 1.0 = no resistance (free
 *  pan beyond bounds), 0.0 = hard clamp. 0.5 gives a comfortable "stretchy"
 *  feel that matches platform conventions. Values past the boundary scale
 *  by this factor — drag 100px past edge → image moves 50px past edge. */
private const val RUBBER_BAND_FACTOR = 0.5f

/**
 * Single source of truth for the image's transform: scale, translation, rotation.
 *
 * Replaces Telephoto's ZoomableState. Unlike Telephoto, this state's transform
 * pipeline puts rotation INSIDE the same matrix as scale and offset — so when
 * the user twists the image while zoomed, they see the rotated image properly,
 * not a rotated screenshot of a zoomed view.
 *
 * The state holds plain Compose state (mutableFloatStateOf) for live gesture
 * updates and uses suspend functions for animated transitions (fling, double-tap
 * cycle, rotation snap).
 */
class ImageTransformState(
    val minScale: Float = 1f,
    val maxScale: Float = 5f,
    val rotationSnapToleranceDeg: Float = 5f,
    private val doubleTapZoomTarget: Float = 2f,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    var rotation by mutableFloatStateOf(0f)
        private set

    /** Image's intrinsic width/height ratio. Set once when the image loads;
     *  used to compute boundary clamps that match the actual rendered size. */
    var intrinsicAspect: Float = 1f
        internal set

    /** Apply a single gesture frame.
     *
     *  Returns true if the gesture was consumed locally (image transformed).
     *  Returns false to signal "hand off this event to the parent" — used
     *  when the user pans past the horizontal edge of a zoomed image (or
     *  any horizontal pan at fit zoom): the calling gesture detector then
     *  declines to consume the pointer event so the parent HorizontalPager
     *  can swipe to the next/previous image.
     *
     *  centroid is in viewport-local coords; pan is screen-pixel delta;
     *  zoom is multiplicative scale change; rotation is degrees delta. */
    fun applyGesture(
        centroid: Offset,
        pan: Offset,
        zoomChange: Float,
        rotationChange: Float,
        viewportCenter: Offset,
        viewportSize: androidx.compose.ui.geometry.Size,
    ): Boolean {
        // Hand-off rule: pure-horizontal pan at the horizontal boundary should
        // be passed up to the parent pager so the user can swipe-to-navigate
        // by continuing to drag past the edge. Diagonal pans (significant
        // vertical component) are kept local so the user can still pan
        // around the zoomed image — only "intentional" horizontal swipes
        // hand off.
        val isPurePan = zoomChange == 1f && rotationChange == 0f
        val isHorizontalDominant = abs(pan.x) > 2f * abs(pan.y) && pan.x != 0f
        if (isPurePan && isHorizontalDominant) {
            val (maxX, _) = computeMaxOffset(scale, viewportSize)
            val tolerance = 0.5f
            val atLeftEdge = offsetX >= maxX - tolerance && pan.x > 0f
            val atRightEdge = offsetX <= -maxX + tolerance && pan.x < 0f
            val atFitZoom = scale <= minScale + 0.001f
            if (atFitZoom || atLeftEdge || atRightEdge) {
                return false  // hand off to pager
            }
        }

        val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
        val effectiveZoom = if (scale > 0f) newScale / scale else 1f

        val cxRel = centroid.x - viewportCenter.x
        val cyRel = centroid.y - viewportCenter.y
        val rawOffsetX = cxRel * (1f - effectiveZoom) + offsetX * effectiveZoom + pan.x
        val rawOffsetY = cyRel * (1f - effectiveZoom) + offsetY * effectiveZoom + pan.y

        val (maxX, maxY) = computeMaxOffset(newScale, viewportSize)
        val newOffsetX = applyRubberBand(rawOffsetX, -maxX, maxX)
        val newOffsetY = applyRubberBand(rawOffsetY, -maxY, maxY)

        scale = newScale
        offsetX = newOffsetX
        offsetY = newOffsetY
        rotation += rotationChange
        return true
    }

    /** Apply rubber-band resistance: a value past [min..max] is reduced by a
     *  factor of [RUBBER_BAND_FACTOR] beyond the boundary, giving the
     *  classic iOS-style "stretchy edge" feel. */
    private fun applyRubberBand(value: Float, min: Float, max: Float): Float {
        return when {
            value > max -> max + (value - max) * RUBBER_BAND_FACTOR
            value < min -> min + (value - min) * RUBBER_BAND_FACTOR
            else -> value
        }
    }

    /** If the offset is currently outside the valid bounds (e.g. due to
     *  rubber-band overdrag during a gesture), animate it back to the
     *  nearest valid position. Called from the gesture-end observer. */
    suspend fun snapBackToBounds(viewportSize: androidx.compose.ui.geometry.Size) {
        val (maxX, maxY) = computeMaxOffset(scale, viewportSize)
        val targetX = offsetX.coerceIn(-maxX, maxX)
        val targetY = offsetY.coerceIn(-maxY, maxY)
        if (targetX == offsetX && targetY == offsetY) return
        coroutineScope {
            if (targetX != offsetX) {
                launch { animate(offsetX, targetX) { v, _ -> offsetX = v } }
            }
            if (targetY != offsetY) {
                launch { animate(offsetY, targetY) { v, _ -> offsetY = v } }
            }
        }
    }

    /** Animated double-tap zoom cycle (fit ↔ doubleTapZoomTarget).
     *  Tap position is in viewport-local coords. */
    suspend fun cycleDoubleTap(
        tapPosition: Offset,
        viewportCenter: Offset,
        viewportSize: androidx.compose.ui.geometry.Size,
    ) {
        val target = if (scale > 1.05f) 1f else doubleTapZoomTarget
        if (target == 1f) {
            // Animate back to fit, centered. Three parallel animations.
            coroutineScope {
                launch { animate(scale, 1f) { v, _ -> scale = v } }
                launch { animate(offsetX, 0f) { v, _ -> offsetX = v } }
                launch { animate(offsetY, 0f) { v, _ -> offsetY = v } }
            }
        } else {
            // Compute target offset that keeps the tap point under the user's finger.
            val factor = target / scale
            val cxRel = tapPosition.x - viewportCenter.x
            val cyRel = tapPosition.y - viewportCenter.y
            val targetOffsetXRaw = cxRel * (1f - factor) + offsetX * factor
            val targetOffsetYRaw = cyRel * (1f - factor) + offsetY * factor
            val (maxX, maxY) = computeMaxOffset(target, viewportSize)
            val targetOffsetX = targetOffsetXRaw.coerceIn(-maxX, maxX)
            val targetOffsetY = targetOffsetYRaw.coerceIn(-maxY, maxY)
            coroutineScope {
                launch { animate(scale, target) { v, _ -> scale = v } }
                launch { animate(offsetX, targetOffsetX) { v, _ -> offsetX = v } }
                launch { animate(offsetY, targetOffsetY) { v, _ -> offsetY = v } }
            }
        }
    }

    /** Fling momentum on offset. Called when the user lifts fingers with
     *  non-zero pan velocity. Decays exponentially and clamps to bounds. */
    suspend fun fling(
        velocity: Velocity,
        viewportSize: androidx.compose.ui.geometry.Size,
    ) {
        val (maxX, maxY) = computeMaxOffset(scale, viewportSize)
        coroutineScope {
            launch {
                val anim = AnimationState(initialValue = offsetX, initialVelocity = velocity.x)
                anim.animateDecay(exponentialDecay(frictionMultiplier = 1.2f)) {
                    val clamped = value.coerceIn(-maxX, maxX)
                    offsetX = clamped
                    if (clamped != value) cancelAnimation()
                }
            }
            launch {
                val anim = AnimationState(initialValue = offsetY, initialVelocity = velocity.y)
                anim.animateDecay(exponentialDecay(frictionMultiplier = 1.2f)) {
                    val clamped = value.coerceIn(-maxY, maxY)
                    offsetY = clamped
                    if (clamped != value) cancelAnimation()
                }
            }
        }
    }

    /** Soft-snap rotation to nearest cardinal if within tolerance. Animated
     *  for visual smoothness. Always takes the SHORTEST angular path —
     *  e.g., a release at 355° animates +5° to 360°, not -355° to 0°. */
    suspend fun snapRotationIfClose() {
        val current = rotation
        // Nearest multiple of 90° in the same numeric range as current.
        // round(355/90) = 4, * 90 = 360 → animate 355 → 360 (short way).
        // round(5/90) = 0, * 90 = 0 → animate 5 → 0 (short way).
        // round(725/90) = 8, * 90 = 720 → animate 725 → 720 (short way).
        val target = (current / 90f).roundToInt() * 90f
        if (abs(target - current) <= rotationSnapToleranceDeg) {
            animate(rotation, target) { v, _ -> rotation = v }
        }
    }

    fun resetToFit() {
        scale = 1f; offsetX = 0f; offsetY = 0f; rotation = 0f
    }

    /** Computes maximum |offset| in each axis given the current scale and the
     *  image's rendered fit dimensions. Returns (maxX, maxY). When scale ≤ 1
     *  the image fits inside the viewport, so offsets are pinned to 0. */
    private fun computeMaxOffset(
        atScale: Float,
        viewportSize: androidx.compose.ui.geometry.Size,
    ): Pair<Float, Float> {
        val vw = viewportSize.width
        val vh = viewportSize.height
        if (vw <= 0f || vh <= 0f || atScale <= 1f) return 0f to 0f
        val viewportAspect = vw / vh
        // Compute the rendered image's fit-width and fit-height (at scale=1).
        // Image fits height-bound iff intrinsicAspect < viewportAspect.
        val fitWidth: Float
        val fitHeight: Float
        if (intrinsicAspect > viewportAspect) {
            fitWidth = vw
            fitHeight = vw / intrinsicAspect
        } else {
            fitHeight = vh
            fitWidth = vh * intrinsicAspect
        }
        val renderedWidth = fitWidth * atScale
        val renderedHeight = fitHeight * atScale
        val maxX = max(0f, (renderedWidth - vw) / 2f)
        val maxY = max(0f, (renderedHeight - vh) / 2f)
        // Note: rotation is intentionally not factored into clamp bounds.
        // For the small rotations typical of level-correction, the additional
        // bounding-box extent is negligible. For larger rotations, we accept
        // a small over-pan rather than the much more complex rotation-aware
        // clamping math.
        return min(maxX, Float.MAX_VALUE) to min(maxY, Float.MAX_VALUE)
    }
}

@Composable
fun rememberImageTransformState(
    minScale: Float = 1f,
    maxScale: Float = 5f,
): ImageTransformState = remember { ImageTransformState(minScale, maxScale) }
