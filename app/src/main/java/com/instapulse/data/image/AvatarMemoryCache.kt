package com.instapulse.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instapulse.ui.theme.IgPulseGradient
import com.instapulse.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * High-performance, low-memory LRU cache for 88x88 RGB_565 downsampled avatars.
 * Max memory consumption: 12MB (holds ~800 avatars in memory without GC pauses).
 */
object AvatarMemoryCache {
    private const val MAX_CACHE_SIZE_BYTES = 12 * 1024 * 1024 // 12MB

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }
    }

    fun get(url: String): Bitmap? = cache.get(url)

    fun put(url: String, bitmap: Bitmap) {
        cache.put(url, bitmap)
    }

    fun trimToSize(maxBytes: Int) {
        cache.trimToSize(maxBytes)
    }

    fun evictAll() {
        cache.evictAll()
    }

    suspend fun loadAvatarBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        cache.get(url)?.let { return@withContext it }

        var connection: HttpURLConnection? = null
        try {
            val u = URL(url)
            connection = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            }
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }

            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return@withContext null

            // Decode downsampled RGB_565 (2 bytes per pixel)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = 2
            }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null
            val scaled = if (raw.width != 88 || raw.height != 88) {
                val s = Bitmap.createScaledBitmap(raw, 88, 88, true)
                if (s != raw) {
                    raw.recycle()
                }
                s
            } else {
                raw
            }

            cache.put(url, scaled)
            scaled
        } catch (_: Exception) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {}
        }
    }
}

/**
 * Ultra-low memory Composable avatar using AvatarMemoryCache.
 * While loading or offline, draws a crisp GPU monogram ring using drawWithCache.
 */
@Composable
fun AvatarImage(
    avatarUrl: String,
    username: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    hasDoubleRing: Boolean = false,
    borderColor: Color = Color.Transparent
) {
    var bitmap by remember(avatarUrl) {
        mutableStateOf(if (avatarUrl.isNotEmpty()) AvatarMemoryCache.get(avatarUrl) else null)
    }

    LaunchedEffect(avatarUrl) {
        if (avatarUrl.isNotEmpty() && bitmap == null) {
            val loaded = AvatarMemoryCache.loadAvatarBitmap(avatarUrl)
            if (loaded != null) {
                bitmap = loaded
            }
        }
    }

    val initial = remember(username) {
        username.firstOrNull()?.uppercase() ?: "?"
    }

    val ringModifier = if (hasDoubleRing) {
        modifier
            .size(size)
            .clip(CircleShape)
            .drawWithCache {
                val brush = Brush.sweepGradient(IgPulseGradient)
                onDrawWithContent {
                    drawContent()
                    drawCircle(brush = brush, radius = this.size.minDimension / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                }
            }
    } else {
        modifier
            .size(size)
            .clip(CircleShape)
            .border(1.5.dp, borderColor, CircleShape)
    }

    Box(
        modifier = ringModifier,
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null && !currentBitmap.isRecycled) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = username,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            // Crisp GPU-drawn gradient monogram ring with initials (zero allocations)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .drawWithCache {
                        val bgBrush = Brush.radialGradient(
                            colors = listOf(Color(0xFF22283A), Color(0xFF131724))
                        )
                        val ringBrush = Brush.sweepGradient(
                            colors = listOf(Color(0x55DD2A7B), Color(0x5538BDF8), Color(0x55DD2A7B))
                        )
                        onDrawBehind {
                            drawCircle(brush = bgBrush)
                            drawCircle(
                                brush = ringBrush,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = (size.value * 0.38f).sp
                )
            }
        }
    }
}
