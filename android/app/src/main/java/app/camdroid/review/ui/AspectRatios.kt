package app.camdroid.review.ui

/**
 * A target aspect ratio for the framing overlay. Both orientations are
 * provided as separate presets (e.g. 4:5 portrait AND 5:4 landscape) — the
 * user picks the specific orientation they want, rather than the app
 * second-guessing their intent based on image orientation.
 */
data class AspectRatio(val label: String, val w: Float, val h: Float) {
    val ratio: Float get() = w / h
}

object AspectRatios {
    val SQUARE = AspectRatio("1:1", 1f, 1f)

    val ALL: List<AspectRatio> = listOf(
        SQUARE,
        AspectRatio("4:5", 4f, 5f),       // Instagram portrait
        AspectRatio("5:4", 5f, 4f),       // 8x10 landscape print
        AspectRatio("2:3", 2f, 3f),       // 35mm portrait
        AspectRatio("3:2", 3f, 2f),       // 35mm landscape
        AspectRatio("3:4", 3f, 4f),       // 4x3 sensor portrait
        AspectRatio("4:3", 4f, 3f),       // 4x3 sensor landscape
        AspectRatio("9:16", 9f, 16f),     // vertical video / Stories
        AspectRatio("16:9", 16f, 9f),     // HDTV / video
        AspectRatio("1:2", 1f, 2f),       // tall poster
        AspectRatio("2:1", 2f, 1f),       // wide print
        AspectRatio("2.35:1", 2.35f, 1f), // cinematic
    )
}
