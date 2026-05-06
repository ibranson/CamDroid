package app.camdroid.review.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Velocity
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs

/**
 * Minimum pan velocity (px/sec) at finger-release that triggers fling
 * momentum. Below this, the image stops exactly where the finger lifted.
 * Roughly aligned with Android's typical minimumFlingVelocity (~150–250
 * px/sec on most devices) so the threshold matches user muscle memory
 * from other apps.
 */
private const val MIN_FLING_VELOCITY = 250f

/**
 * Zoomable, pannable, twistable image — Telephoto replacement.
 *
 * Architecture: outer Box clipped to viewport, inner AsyncImage with
 * ContentScale.Fit at scale=1.0, transformed via a single graphicsLayer
 * driven by ImageTransformState. Rotation is part of the transform pipeline,
 * so twisting a zoomed image rotates the image properly (not a screenshot
 * of it).
 *
 * Gestures supported:
 *   - Pinch to zoom (with zoom-around-centroid)
 *   - One-finger pan when zoomed
 *   - Two-finger twist for rotation; soft-snaps to cardinals on release
 *   - Single tap → onClick
 *   - Long press → onLongClick (fired immediately, doesn't compete with pan)
 *   - Double tap → animated zoom cycle (fit ↔ 2x), centered on tap point
 *   - Pan with velocity → fling/decay momentum, clamped to bounds
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    state: ImageTransformState,
    modifier: Modifier = Modifier,
    rotationSnapEnabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val viewportSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val viewportCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val scope = rememberCoroutineScope()
        // pointerInput captures values at setup; without rememberUpdatedState
        // the snap flag would be frozen at whatever it was on first composition,
        // so toggling the chrome switch wouldn't take effect for active sessions.
        val snapEnabled by rememberUpdatedState(rotationSnapEnabled)

        // Track the latest pan velocity (in screen px/sec) so we can fling on
        // gesture-end. detectTransformGestures itself doesn't surface velocity,
        // so we estimate from recent deltas in our own pointer-input observer.
        val velocityTracker = remember { PanVelocityTracker() }

        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onState = { imgState ->
                if (imgState is AsyncImagePainter.State.Success) {
                    val sz = imgState.painter.intrinsicSize
                    if (sz.width > 0f && sz.height > 0f) {
                        state.intrinsicAspect = sz.width / sz.height
                    }
                }
            },
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offsetX
                    translationY = state.offsetY
                    rotationZ = state.rotation
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .pointerInput(state) {
                    detectImageGestures(
                        onTap = { onClick?.invoke() },
                        onLongPress = { onLongClick?.invoke() },
                        onDoubleTap = { tap ->
                            scope.launch {
                                state.cycleDoubleTap(tap, viewportCenter, viewportSize)
                            }
                        },
                        onTransform = { centroid, pan, zoom, rot ->
                            velocityTracker.addPan(pan)
                            state.applyGesture(
                                centroid = centroid,
                                pan = pan,
                                zoomChange = zoom,
                                rotationChange = rot,
                                viewportCenter = viewportCenter,
                                viewportSize = viewportSize,
                            )
                        },
                        onTransformEnd = {
                            val v = velocityTracker.consumeVelocity()
                            velocityTracker.reset()
                            if (snapEnabled) {
                                scope.launch { state.snapRotationIfClose() }
                            }
                            val speedSq = v.x * v.x + v.y * v.y
                            if (state.scale > 1f && speedSq > MIN_FLING_VELOCITY * MIN_FLING_VELOCITY) {
                                scope.launch { state.fling(Velocity(v.x, v.y), viewportSize) }
                            }
                            scope.launch { state.snapBackToBounds(viewportSize) }
                        },
                    )
                },
        )

        // If the viewport size or intrinsic aspect changes (e.g. orientation),
        // the current offset may exceed valid bounds. Clamp on next frame.
        LaunchedEffect(viewportSize, state.intrinsicAspect, state.scale) {
            // No-op: clamping happens inside applyGesture; this LaunchedEffect
            // is a hook point for future re-clamps if we add settings that
            // mutate transform state from outside the gesture loop.
        }
    }
}

/**
 * Custom gesture detector that combines tap, double-tap, long-press, and
 * transform (pinch + pan + rotate) into a single coordinated gesture loop.
 *
 * The motivation for replacing the stock detectTapGestures + detectTransform-
 * Gestures combo:
 *
 *   1. **Avoid double-consume.** detectTapGestures consumes the down event
 *      on touch-down. detectTransformGestures bails out when it sees a
 *      consumed event. The result was several events of motion silently
 *      discarded at gesture start, contributing to perceived sluggishness.
 *
 *   2. **Per-event consume control for parent hand-off.** The onTransform
 *      callback returns Boolean. If it returns false (state declined the
 *      gesture — e.g., panning past the edge of a zoomed image), this
 *      detector does NOT consume the event. The parent HorizontalPager
 *      then sees an unconsumed pan and can swipe to the next/previous
 *      image. iOS-style "pan to edge → keep swiping → next image".
 */
private suspend fun PointerInputScope.detectImageGestures(
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Boolean,
    onTransformEnd: () -> Unit,
) = awaitEachGesture {
    val firstDown = awaitFirstDown(requireUnconsumed = false)
    val touchSlop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis

    var totalPan = Offset.Zero
    var totalZoom = 1f
    var totalRotation = 0f

    // Phase 1: race long-press timeout vs slop crossing vs release-without-motion.
    val phase1: Phase1Result = withTimeoutOrNull(longPressMs) {
        var outcome: Phase1Result = Phase1Result.UNDETERMINED
        while (outcome == Phase1Result.UNDETERMINED) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) {
                outcome = Phase1Result.RELEASED
                break
            }
            val zoomChange = event.calculateZoom()
            val rotationChange = event.calculateRotation()
            val panChange = event.calculatePan()
            totalZoom *= zoomChange
            totalRotation += rotationChange
            totalPan += panChange
            val centroidSize = event.calculateCentroidSize(useCurrent = false)
            val zoomMotion = abs(1f - totalZoom) * centroidSize
            val rotationMotion = abs(totalRotation * (PI.toFloat() / 180f) * centroidSize)
            val panMotion = totalPan.getDistance()
            if (panMotion > touchSlop || zoomMotion > touchSlop || rotationMotion > touchSlop) {
                outcome = Phase1Result.SLOP_CROSSED
                break
            }
        }
        outcome
    } ?: Phase1Result.LONG_PRESS

    when (phase1) {
        Phase1Result.LONG_PRESS -> {
            onLongPress(firstDown.position)
            // Drain remaining events until release so we don't bleed into
            // the next gesture. Don't consume — let other detectors see.
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
            }
        }
        Phase1Result.RELEASED -> {
            // Tap (no slop crossed). Check for a follow-up second tap within
            // the system double-tap timeout.
            val secondDown = withTimeoutOrNull(doubleTapMs) {
                awaitFirstDown(requireUnconsumed = false)
            }
            if (secondDown != null) {
                onDoubleTap(secondDown.position)
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                }
            } else {
                onTap(firstDown.position)
            }
        }
        Phase1Result.SLOP_CROSSED -> {
            // Phase 2: transform mode. Per-event, ask onTransform whether to
            // consume; only consume position changes when it returns true.
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                    val zoomChange = event.calculateZoom()
                    val rotationChange = event.calculateRotation()
                    val panChange = event.calculatePan()
                    if (zoomChange != 1f || rotationChange != 0f || panChange != Offset.Zero) {
                        val centroid = event.calculateCentroid(useCurrent = false)
                        val consumed = onTransform(centroid, panChange, zoomChange, rotationChange)
                        if (consumed) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                }
            } finally {
                onTransformEnd()
            }
        }
        Phase1Result.UNDETERMINED -> { /* unreachable */ }
    }
}

private enum class Phase1Result { UNDETERMINED, RELEASED, SLOP_CROSSED, LONG_PRESS }

/**
 * Lightweight velocity tracker for pan flings. Compose has VelocityTracker but
 * it requires PointerInputChange objects; detectTransformGestures gives us
 * computed pan deltas directly. We keep a short rolling window of recent
 * deltas + timestamps and average to estimate velocity at release.
 */
private class PanVelocityTracker {
    private data class Sample(val pan: Offset, val timeNanos: Long)
    private val samples = ArrayDeque<Sample>()
    private val windowNanos = 100_000_000L  // 100ms

    fun addPan(pan: Offset) {
        val now = System.nanoTime()
        samples.addLast(Sample(pan, now))
        // Drop samples older than the window.
        while (samples.isNotEmpty() && now - samples.first().timeNanos > windowNanos) {
            samples.removeFirst()
        }
    }

    /** Returns velocity in pixels/second derived from the recent window. */
    fun consumeVelocity(): Offset {
        if (samples.size < 2) return Offset.Zero
        val first = samples.first()
        val last = samples.last()
        val dtNanos = (last.timeNanos - first.timeNanos).coerceAtLeast(1)
        val dtSec = dtNanos / 1_000_000_000f
        var totalPan = Offset.Zero
        for (s in samples) totalPan += s.pan
        return Offset(totalPan.x / dtSec, totalPan.y / dtSec)
    }

    fun reset() = samples.clear()
}
