package org.example.atvretranslation.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.atvretranslation.data.AnimeLibClient
import java.net.URI

private val imageCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<Bitmap?>(initialValue = url?.let(imageCache::get), key1 = url) {
        if (url.isNullOrBlank() || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URI(url).toURL().openConnection().apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Referer", "${AnimeLibClient.WEB_ORIGIN}/")
                    setRequestProperty("User-Agent", AnimeLibClient.USER_AGENT)
                }
                connection.getInputStream().use(BitmapFactory::decodeStream)
                    ?.also { imageCache.put(url, it) }
            }.getOrNull()
        }
    }

    if (bitmap == null) {
        Box(modifier.background(Color(0xFF181B24)))
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}
