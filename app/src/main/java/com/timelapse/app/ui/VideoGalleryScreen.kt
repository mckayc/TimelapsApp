package com.timelapse.app.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAddedMs: Long,
    val durationMs: Long,
    val sizeBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGalleryScreen(
    onBack: () -> Unit,
    onPlayVideo: (Uri) -> Unit
) {
    val context = LocalContext.current

    var videos       by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<VideoItem?>(null) }

    LaunchedEffect(Unit) {
        videos    = loadTimelapsVideos(context)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Timelapses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                videos.isEmpty() -> {
                    EmptyGallery(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(
                        modifier            = Modifier.fillMaxSize(),
                        contentPadding      = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(videos, key = { it.id }) { video ->
                            VideoCard(
                                video     = video,
                                onPlay    = { onPlayVideo(video.uri) },
                                onDelete  = { deleteTarget = video }
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { video ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon    = { Icon(Icons.Default.DeleteForever, null) },
            title   = { Text("Delete video?") },
            text    = { Text("\"${video.displayName}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            context.contentResolver.delete(video.uri, null, null)
                            videos      = videos.filter { it.id != video.id }
                        } catch (e: Exception) {
                            Log.e("Gallery", "Delete failed", e)
                        }
                        deleteTarget = null
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun VideoCard(
    video    : VideoItem,
    onPlay   : () -> Unit,
    onDelete : () -> Unit
) {
    val context   = LocalContext.current
    var thumbnail by remember(video.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(video.id) {
        thumbnail = loadThumbnail(context, video)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        shape     = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier            = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment   = Alignment.CenterVertically
        ) {
            Box(
                modifier          = Modifier
                    .size(width = 112.dp, height = 70.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment  = Alignment.Center
            ) {
                val bmp = thumbnail
                if (bmp != null) {
                    Image(
                        bitmap        = bmp.asImageBitmap(),
                        contentDescription = "Video thumbnail",
                        contentScale  = ContentScale.Crop,
                        modifier      = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.VideoFile, null, tint = Color.Gray, modifier = Modifier.size(32.dp))
                }
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = "Play",
                    tint     = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(32.dp)
                )
                if (video.durationMs > 0) {
                    Text(
                        text     = formatDuration(video.durationMs),
                        color    = Color.White,
                        style    = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = video.displayName,
                    style    = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = formatDate(video.dateAddedMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text  = formatSize(video.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun EmptyGallery(modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.VideoLibrary, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("No timelapses yet", style = MaterialTheme.typography.titleMedium)
        Text("Record your first timelapse and it will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private suspend fun loadTimelapsVideos(context: Context): List<VideoItem> =
    withContext(Dispatchers.IO) {
        val results = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        else
            null
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            arrayOf("Movies/TimelapsApp%")
        else
            null

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol      = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id  = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    results += VideoItem(
                        id          = id,
                        uri         = uri,
                        displayName = cursor.getString(nameCol) ?: "video_$id.mp4",
                        dateAddedMs = cursor.getLong(dateCol) * 1000L,
                        durationMs  = cursor.getLong(durCol),
                        sizeBytes   = cursor.getLong(sizeCol)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("Gallery", "Load failed", e)
        }
        results
    }

private suspend fun loadThumbnail(context: Context, video: VideoItem): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(video.uri, Size(320, 180), null)
            } else {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, video.uri)
                val bmp = retriever.getFrameAtTime(0)
                retriever.release()
                bmp
            }
        }.getOrNull()
    }

private val dateFormatter = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
private fun formatDate(ms: Long)  = dateFormatter.format(Date(ms))
private fun formatDuration(ms: Long): String {
    val m = TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return String.format("%d:%02d", m, s)
}
private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000f)
    bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000f)
    else               -> "$bytes B"
}
