package com.muh.usbcameraviewer.gallery

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.muh.usbcameraviewer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                GalleryUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                GalleryUiState.Empty ->
                    Text(
                        stringResource(R.string.gallery_empty),
                        Modifier.align(Alignment.Center).padding(32.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                is GalleryUiState.Content -> {
                    GalleryGrid(items = s.items, onOpen = { viewerIndex = it })
                    viewerIndex?.let { index ->
                        FullScreenViewer(
                            items = s.items,
                            startIndex = index.coerceAtMost(s.items.lastIndex),
                            onClose = { viewerIndex = null },
                            onDelete = viewModel::delete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryGrid(
    items: List<MediaItem>,
    onOpen: (Int) -> Unit,
) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items.size) { index ->
            val item = items[index]
            Box(
                Modifier
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .clickable { onOpen(index) },
            ) {
                AsyncImage(
                    model =
                        ImageRequest.Builder(context)
                            .data(item.uri)
                            .apply { if (item.isVideo) decoderFactory(VideoFrameDecoder.Factory()) }
                            .crossfade(true)
                            .build(),
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                if (item.isVideo) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = stringResource(R.string.cd_video_badge),
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenViewer(
    items: List<MediaItem>,
    startIndex: Int,
    onClose: () -> Unit,
    onDelete: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    var confirmDelete by remember { mutableStateOf<MediaItem?>(null) }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            if (item.isVideo) VideoPlayer(item) else ZoomableImage(item)
        }
        val current = items[pagerState.currentPage.coerceAtMost(items.lastIndex)]
        androidx.compose.foundation.layout.Row(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = {
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = if (current.isVideo) "video/mp4" else "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(current.uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                context.startActivity(Intent.createChooser(send, null))
            }) {
                Icon(Icons.Filled.Share, stringResource(R.string.cd_share), tint = Color.White)
            }
            IconButton(onClick = { confirmDelete = current }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.cd_delete), tint = Color.White)
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = Color.White,
            )
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body, item.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDelete(item)
                    if (items.size == 1) onClose()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ZoomableImage(item: MediaItem) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    AsyncImage(
        model = item.uri,
        contentDescription = item.displayName,
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offsetX += pan.x
                        offsetY += pan.y
                        if (scale == 1f) {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
    )
}

@Composable
private fun VideoPlayer(item: MediaItem) {
    // Zero-dependency playback: show video frame + play button that hands
    // off to the system player. Keeps app offline-only and small.
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model =
                ImageRequest.Builder(context)
                    .data(item.uri)
                    .decoderFactory(VideoFrameDecoder.Factory())
                    .build(),
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.parse(item.uri), "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            },
            modifier = Modifier.align(Alignment.Center).size(80.dp),
        ) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = item.displayName,
                tint = Color.White,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
