package app.camdroid.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.camdroid.review.data.ImageSummary

/**
 * EXIF panel — single component used by both the persistent toggle and the
 * long-press transient popup. Shows shooting essentials in a compact row of
 * monospace pills.
 */
@Composable
fun ExifPanel(
    image: ImageSummary?,
    modifier: Modifier = Modifier,
) {
    if (image == null) return
    val ex = image.exif

    val items = buildList {
        ex.iso?.let { add("ISO $it") }
        ex.shutter?.let { add(it) }
        ex.aperture?.let { add("f/$it") }
        ex.focalLength?.let { add("${it.toInt()}mm") }
        if (ex.width != null && ex.height != null) add("${ex.width} × ${ex.height}")
    }
    if (items.isEmpty()) return

    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach {
                    Text(
                        text = it,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
