package app.camdroid.review.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas

private val UNLOCKED_COLOR = Color(0xFF2E7D32)
private val HALF_COLOR = Color(0xFFF9A825)
private val LOCKED_COLOR = Color(0xFFC62828)

@Composable
fun LockButton(
    lockState: LockState,
    halfLockSecondsRemaining: Int,
    unseenCount: Int,
    secondsLocked: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 1Hz pulse for LOCKED-after-5s. Always run the transition (so call sites
    // are stable across recompositions) and apply the value conditionally.
    val transition = rememberInfiniteTransition(label = "lockPulse")
    val pulseValue by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    val pulseActive = lockState == LockState.LOCKED && secondsLocked >= LOCKED_PULSE_DELAY_SECONDS
    val effectiveAlpha = if (pulseActive) pulseValue else 1f

    val tint = when (lockState) {
        LockState.UNLOCKED -> UNLOCKED_COLOR
        LockState.HALF_LOCKED -> HALF_COLOR
        LockState.LOCKED -> LOCKED_COLOR
    }
    val icon = when (lockState) {
        LockState.UNLOCKED -> Icons.Filled.LockOpen
        LockState.HALF_LOCKED -> Icons.Filled.LockClock
        LockState.LOCKED -> Icons.Filled.Lock
    }
    val description = when (lockState) {
        LockState.UNLOCKED -> "Auto-advance unlocked. Tap to half-lock."
        LockState.HALF_LOCKED -> "Auto-advance half-locked (${halfLockSecondsRemaining}s). Tap to lock."
        LockState.LOCKED -> "Auto-advance locked. Tap to unlock."
    }

    // Animate the half-lock countdown ring smoothly between integer ticks.
    val ringTarget = halfLockSecondsRemaining.toFloat() / HALF_LOCK_TIMEOUT_SECONDS
    val ringProgress by animateFloatAsState(
        targetValue = if (lockState == LockState.HALF_LOCKED) ringTarget else 0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "ringProgress",
    )

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .alpha(effectiveAlpha),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        if (lockState == LockState.HALF_LOCKED) {
            Canvas(modifier = Modifier.size(34.dp)) {
                drawArc(
                    color = HALF_COLOR,
                    startAngle = -90f,
                    sweepAngle = ringProgress * 360f,
                    useCenter = false,
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            }
        }
        if (lockState == LockState.LOCKED && unseenCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .background(LOCKED_COLOR, CircleShape)
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (unseenCount > 99) "99+" else unseenCount.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

