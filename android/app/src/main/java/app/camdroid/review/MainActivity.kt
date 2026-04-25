package app.camdroid.review

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.drop
import app.camdroid.review.data.EventStream
import app.camdroid.review.data.ImageSummary
import app.camdroid.review.ui.ExifPanel
import app.camdroid.review.ui.LockButton
import app.camdroid.review.ui.MainViewModel
import app.camdroid.review.ui.SettingsDialog
import app.camdroid.review.ui.UiState
import app.camdroid.review.ui.theme.CamDroidReviewTheme
import coil3.compose.AsyncImage
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CamDroidReviewTheme {
                val vm: MainViewModel = viewModel()
                val ui by vm.ui.collectAsState()
                ReviewScreen(ui = ui, current = vm.currentImage, vm = vm)
            }
        }
    }
}

@Composable
private fun ReviewScreen(ui: UiState, current: ImageSummary?, vm: MainViewModel) {
    ImmersiveSystemBars(visible = ui.chromeVisible)
    if (ui.settingsDialogOpen) {
        SettingsDialog(
            sessionId = ui.sessionId,
            cameraModel = ui.cameraModel,
            eventLogVisible = ui.showEventLog,
            onToggleEventLog = { vm.toggleEventLog() },
            onDismiss = { vm.closeSettingsDialog() },
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Image pager fills the canvas. Each page is a zoomable preview.
            // Tapping anywhere on the image (handled by ZoomableAsyncImage's
            // onClick) toggles chrome. When zoomed in, the pager is locked so
            // pan gestures don't accidentally swipe to the next image.
            if (ui.recentImages.isNotEmpty()) {
                ImagePager(ui = ui, vm = vm)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { vm.toggleChrome() })
                        },
                ) {
                    EmptyState()
                }
            }

            // Long-press EXIF popup. Renders OUTSIDE the chrome AnimatedVisibility
            // because the long-press is a deliberate user request that must be
            // honored even in immersive mode.
            if (ui.exifLongPressActive && current != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                ) {
                    ExifPanel(
                        image = current,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 100.dp),
                    )
                }
            }

            // All chrome (status icons, thumbnail strip) toggles together with a fade.
            AnimatedVisibility(
                visible = ui.chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                ) {
                    StatusCluster(
                        ui = ui,
                        onCycleLock = { vm.cycleLock() },
                        onToggleExif = { vm.toggleExifPanel() },
                        onOpenSettings = { vm.openSettingsDialog() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 12.dp),
                    )
                    if (current != null) {
                        FavoriteToggle(
                            favorite = current.favorite,
                            onClick = { vm.toggleFavoriteOnCurrent() },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 8.dp, start = 12.dp),
                        )
                    }
                    if (ui.recentImages.isNotEmpty()) {
                        ThumbnailStrip(
                            images = ui.recentImages,
                            currentId = current?.id,
                            onSelect = { vm.setCurrentImage(it) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                        )
                    }
                    // Persistent EXIF panel (toggled via the chrome button).
                    // Suppressed while the long-press transient is showing so
                    // we don't double-render the same data.
                    if (ui.exifPanelToggled && !ui.exifLongPressActive && current != null) {
                        ExifPanel(
                            image = current,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 100.dp),
                        )
                    }
                    if (ui.showEventLog) {
                        EventLogOverlay(ui = ui, modifier = Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
    }
}

/**
 * Drives Android's WindowInsetsController so the system status/nav bars hide and
 * show in lockstep with our chrome. When hidden, swiping from a screen edge will
 * temporarily reveal the bars (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) without
 * flipping our app's chromeVisible flag.
 */
@Composable
private fun ImmersiveSystemBars(visible: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as Activity).window
        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * HorizontalPager of zoomable images. recentImages is sorted newest-first,
 * so page 0 = newest. Pager state and ViewModel.currentImageId are kept in
 * sync in both directions.
 */
@Composable
private fun ImagePager(ui: UiState, vm: MainViewModel) {
    val images = ui.recentImages
    val pagerState = rememberPagerState(
        initialPage = images.indexOfFirst { it.id == ui.currentImageId }.coerceAtLeast(0),
        pageCount = { images.size },
    )

    // External -> pager: when ViewModel switches the current image (e.g. new
    // capture arrives, thumbnail tapped), animate the pager to the right page.
    LaunchedEffect(ui.currentImageId, images) {
        val idx = images.indexOfFirst { it.id == ui.currentImageId }
        if (idx >= 0 && idx != pagerState.currentPage) {
            pagerState.animateScrollToPage(idx)
        }
    }

    // Pager -> external: when the user swipes and the pager settles, push the
    // newly-current image's id back to the ViewModel.
    //
    // drop(1) is critical: snapshotFlow emits the *current* value immediately
    // on subscribe, and this LaunchedEffect re-keys whenever `images` changes
    // (i.e., on every new capture). Without drop(1), the re-key would feed
    // back the stale settledPage value from before the new image arrived,
    // racing against Effect A's auto-advance animation and snapping the
    // ViewModel's currentImageId back to whatever image happens to live at
    // that stale index in the new list — i.e. yanking the user away from the
    // just-captured shot to the previous one.
    LaunchedEffect(pagerState, images) {
        snapshotFlow { pagerState.settledPage }
            .drop(1)
            .collect { page ->
                if (page in images.indices) {
                    val id = images[page].id
                    if (id != ui.currentImageId) vm.setCurrentImage(id)
                }
            }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // Stable per-page key so the pager tracks items by image id rather
        // than by slot index. When a new capture arrives and shifts the list,
        // the pager updates currentPage to keep the user on the same logical
        // image (critical for LOCKED / HALF_LOCKED to actually feel "locked").
        key = { pageIdx -> images[pageIdx].id },
        // Each page renders an AsyncImage wrapped in Telephoto's zoomable
        // modifier. Pinch-zoom, double-tap (default fit <-> 2x), boundary
        // fling, and one-finger pan when zoomed are all handled by Telephoto.
        // Tap toggles chrome.
    ) { page ->
        val img = images[page]
        // maxZoomFactor controls how far the user can pinch-zoom in.
        // 5x on the 2048px preview is well past 1-source-pixel-per-screen-pixel
        // (which sits around ~2x on most phones); above that, the preview
        // image will visibly soften since we're upscaling JPG bytes. True
        // pixel-peep against the full-resolution camera JPG is a future
        // refinement (swap to full.jpg above some zoom threshold).
        //
        // Key the zoom state on img.id so a fresh state is created whenever
        // the underlying image at this page index changes (e.g. when a new
        // capture arrives and we auto-advance — the new shot starts at fit,
        // not whatever zoom level the prior shot was last at).
        val zoomState = key(img.id) {
            rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 5f))
        }
        // Report any pinch/pan activity to the ViewModel so the half-lock
        // inactivity timer doesn't snap the user away mid-pixel-peep. Also
        // dismiss the EXIF long-press popup if the user starts zooming —
        // when they zoom, they want the image, not an overlay obscuring it.
        LaunchedEffect(zoomState) {
            snapshotFlow { zoomState.contentTransformation }
                .drop(1)
                .collect {
                    vm.recordActivity()
                    val zf = zoomState.zoomFraction
                    if (zf != null && zf > 0f) {
                        vm.setExifLongPress(false)
                    }
                }
        }
        // Parallel pointer-tracking layer. Tracks "is any finger currently
        // on the image" as authoritative state, used to:
        //   (a) freeze the half-lock countdown while pressed (touch = stasis)
        //   (b) dismiss the EXIF long-press popup on full release.
        // Uses requireUnconsumed = false so Telephoto's gesture consumption
        // doesn't hide events from us.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(img.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        vm.setPressActive(true)
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            vm.setPressActive(false)
                            vm.setExifLongPress(false)
                        }
                    }
                },
        ) {
            AsyncImage(
                model = "${Config.BASE_URL}${img.previewUrl}",
                contentDescription = img.id,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(
                        state = zoomState,
                        onClick = { vm.toggleChrome() },
                        onLongClick = {
                            // Per design: long-press triggers EXIF only at fit.
                            // When zoomed, long-press just records activity (so
                            // the half-lock timer pauses). The release-on-finger-up
                            // dismissal still applies via the parallel pointer
                            // tracker above.
                            val zf = zoomState.zoomFraction
                            val atFit = zf == null || zf == 0f
                            if (atFit) {
                                vm.setExifLongPress(true)
                            } else {
                                vm.recordActivity()
                            }
                        },
                    ),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Waiting for first capture…", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("Press the shutter on the camera.", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

/* ---------- Status icon cluster (top-right) ---------- */

@Composable
private fun StatusCluster(
    ui: UiState,
    onCycleLock: () -> Unit,
    onToggleExif: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.45f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            LockButton(
                lockState = ui.lockState,
                halfLockSecondsRemaining = ui.halfLockSecondsRemaining,
                unseenCount = ui.unseenCount,
                secondsLocked = ui.secondsLocked,
                onClick = onCycleLock,
            )
            StatusIcon(
                icon = Icons.Filled.PhotoCamera,
                tint = cameraColor(ui.cameraState),
                description = "Camera: ${ui.cameraState}",
            )
            StatusIcon(
                icon = Icons.Filled.WifiTethering,
                tint = wsColor(ui.wsState),
                description = "Pi: ${ui.wsState.name}",
            )
            // EXIF toggle: filled when persistent panel is on, outline when off.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleExif),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (ui.exifPanelToggled) Icons.Filled.Info else Icons.Outlined.Info,
                    contentDescription = if (ui.exifPanelToggled) "Hide EXIF" else "Show EXIF",
                    tint = if (ui.exifPanelToggled) Color(0xFFFFD600) else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            // Settings gear: white fill with thin black outline so it reads on any background.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, Color.Black, CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color, description: String) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.size(28.dp),
    )
}

/* ---------- Favorite toggle (top-left) ---------- */

@Composable
private fun FavoriteToggle(favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.45f),
        shape = CircleShape,
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            if (favorite) {
                Icon(Icons.Filled.Star, contentDescription = "Unfavorite", tint = Color(0xFFFFD600))
            } else {
                Icon(Icons.Outlined.StarBorder, contentDescription = "Favorite", tint = Color.White)
            }
        }
    }
}

/* ---------- Thumbnail strip (bottom edge) ---------- */

@Composable
private fun ThumbnailStrip(
    images: List<ImageSummary>,
    currentId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    LaunchedEffect(currentId, images) {
        val idx = images.indexOfFirst { it.id == currentId }
        if (idx >= 0) state.animateScrollToItem(idx)
    }
    Surface(
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            items(images, key = { it.id }) { img ->
                ThumbCell(img = img, selected = img.id == currentId, onClick = { onSelect(img.id) })
            }
        }
    }
}

@Composable
private fun ThumbCell(img: ImageSummary, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) Color.White else Color.Transparent
    Box(
        modifier = Modifier
            .size(72.dp)
            .border(2.dp, borderColor, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF222222))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = "${Config.BASE_URL}${img.thumbUrl}",
            contentDescription = img.id,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (img.favorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "favorite",
                tint = Color(0xFFFFD600),
                modifier = Modifier.padding(2.dp).size(14.dp).align(Alignment.TopEnd),
            )
        }
    }
}

/* ---------- Debug log overlay (toggled, dev aid) ---------- */

@Composable
private fun EventLogOverlay(ui: UiState, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.padding(top = 60.dp).fillMaxWidth(0.85f).fillMaxHeight(0.45f),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "WS=${ui.wsState.name}  cam=${ui.cameraState}  ${ui.cameraModel}",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn {
                items(ui.eventLog) { line ->
                    Text(
                        line,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/* ---------- Color helpers ---------- */

private fun wsColor(s: EventStream.ConnectionState): Color = when (s) {
    EventStream.ConnectionState.CONNECTED -> Color(0xFF2E7D32)
    EventStream.ConnectionState.CONNECTING -> Color(0xFFF9A825)
    EventStream.ConnectionState.UNCONNECTED -> Color(0xFF757575)
    EventStream.ConnectionState.FAILED -> Color(0xFFC62828)
}

private fun cameraColor(state: String): Color = when (state) {
    "ptp_ready" -> Color(0xFF2E7D32)
    "ptp_degraded" -> Color(0xFFF9A825)
    "unknown", "no_usb" -> Color(0xFF757575)
    else -> Color(0xFFC62828)
}
