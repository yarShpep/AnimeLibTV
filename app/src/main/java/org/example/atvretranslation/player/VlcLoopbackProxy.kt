package org.example.atvretranslation.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import org.example.atvretranslation.data.AnimeLibClient
import org.example.atvretranslation.data.SubtitleTrack
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object VlcProxyRegistry {
    private const val VLC_PACKAGE = "org.videolan.vlc"
    private const val VLC_VIDEO_ACTIVITY = "org.videolan.vlc.gui.video.VideoPlayerActivity"
    private var proxy: StreamingProxy? = null
    private var serviceContext: Context? = null

    @Synchronized
    fun open(
        context: Context,
        items: List<VlcQueueItem>,
    ): Result<Unit> = runCatching {
        require(items.isNotEmpty())
        close()
        val newProxy = StreamingProxy(items)
        proxy = newProxy
        val localUrls = newProxy.start()
        serviceContext = context.applicationContext
        ContextCompat.startForegroundService(
            context,
            Intent(context, VlcProxyService::class.java),
        )
        val directIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(localUrls.playlist, "application/x-mpegURL")
            setClassName(VLC_PACKAGE, VLC_VIDEO_ACTIVITY)
            putExtra("title", items.first().title)
            localUrls.currentSubtitle?.let { putExtra("subtitles_location", it.toString()) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(directIntent)
        } catch (error: ActivityNotFoundException) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(localUrls.playlist, "application/x-mpegURL")
                setPackage(VLC_PACKAGE)
                putExtra("title", items.first().title)
                localUrls.currentSubtitle?.let { putExtra("subtitles_location", it.toString()) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallbackIntent)
            } catch (fallbackError: ActivityNotFoundException) {
                close()
                fallbackError.addSuppressed(error)
                throw fallbackError
            }
        }
    }

    @Synchronized
    fun close() {
        proxy?.close()
        proxy = null
        serviceContext?.stopService(Intent(serviceContext, VlcProxyService::class.java))
        serviceContext = null
    }
}

data class VlcQueueItem(
    val title: String,
    val upstreamUrls: List<String>,
    val subtitle: SubtitleTrack? = null,
) {
    init {
        require(upstreamUrls.isNotEmpty())
    }

    constructor(
        title: String,
        upstreamUrl: String,
        subtitle: SubtitleTrack? = null,
    ) : this(title, listOf(upstreamUrl), subtitle)
}

private data class ProxyUrls(val playlist: Uri, val currentSubtitle: Uri?)

private class StreamingProxy(
    private val items: List<VlcQueueItem>,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool()
    private val token = ByteArray(24).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null

    fun start(): ProxyUrls {
        check(running.compareAndSet(false, true))
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(name = "vlc-loopback-accept", isDaemon = true) {
            while (running.get()) {
                runCatching { socket.accept() }
                    .onSuccess { client -> workers.execute { handle(client) } }
                    .onFailure { if (running.get()) close() }
            }
        }
        val base = "http://127.0.0.1:${socket.localPort}"
        return ProxyUrls(
            playlist = Uri.parse("$base/playlist/$token.m3u"),
            currentSubtitle = items.firstOrNull()?.subtitle?.let {
                Uri.parse("$base/subtitle/$token/0.${subtitleExtension(it)}")
            },
        )
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            runCatching {
                val requestLine = readLine(input, 8 * 1024).split(' ')
                if (requestLine.size != 3) return writeEmpty(output, 400, "Bad Request")
                val method = requestLine[0]
                val path = requestLine[1].substringBefore('?')
                if (method != "GET" && method != "HEAD") {
                    return writeEmpty(output, 405, "Method Not Allowed")
                }
                if (path == "/playlist/$token.m3u") {
                    readHeaders(input)
                    return writePlaylist(output, method, socket.localPort)
                }
                val videoIndex = Regex("^/video/$token/(\\d+)\\.mp4$")
                    .matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
                val subtitleIndex = Regex("^/subtitle/$token/(\\d+)\\.[a-z0-9]+$")
                    .matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
                val selectedUpstreams = when {
                    videoIndex != null -> items.getOrNull(videoIndex)?.upstreamUrls
                    subtitleIndex != null -> items.getOrNull(subtitleIndex)?.subtitle?.src?.let(::listOf)
                    else -> null
                } ?: return writeEmpty(output, 404, "Not Found")
                val clientHeaders = readHeaders(input)
                var lastFailure: Throwable? = null
                retry@ for (attempt in DNS_RETRY_DELAYS_MS.indices) {
                    var onlyDnsFailures = true
                    selectedUpstreams.forEachIndexed { index, selectedUpstream ->
                        val upstream = runCatching {
                            openUpstream(selectedUpstream, method, clientHeaders)
                        }.getOrElse { error ->
                            lastFailure = error
                            if (error !is UnknownHostException) onlyDnsFailures = false
                            Log.w(
                                TAG,
                                "VLC $method $path upstream ${index + 1}/${selectedUpstreams.size} " +
                                    "failed (${error.javaClass.simpleName}: ${error.message}); " +
                                    "range=${clientHeaders["range"]}",
                            )
                            return@forEachIndexed
                        }
                        onlyDnsFailures = false
                        try {
                            if (upstream.responseCode !in 200..399 &&
                                index < selectedUpstreams.lastIndex
                            ) {
                                Log.w(
                                    TAG,
                                    "VLC upstream ${index + 1}/${selectedUpstreams.size} returned " +
                                        upstream.responseCode,
                                )
                                return@forEachIndexed
                            }
                            relayUpstream(upstream, output, method)
                            return
                        } finally {
                            upstream.disconnect()
                        }
                    }
                    val delayMs = DNS_RETRY_DELAYS_MS[attempt]
                    if (onlyDnsFailures && delayMs > 0 && running.get()) {
                        Log.w(TAG, "VLC CDN DNS failed; retrying in ${delayMs}ms")
                        Thread.sleep(delayMs)
                        continue@retry
                    }
                    break@retry
                }
                Log.e(TAG, "Every VLC upstream failed", lastFailure)
                writeEmpty(output, 502, "Bad Gateway")
            }.onFailure { error ->
                if (error.isExpectedDisconnect()) {
                    Log.d(TAG, "VLC closed a proxy request")
                } else {
                    Log.e(TAG, "VLC proxy request failed", error)
                }
            }
        }
    }

    private fun Throwable.isExpectedDisconnect(): Boolean =
        generateSequence(this) { it.cause }.any { cause ->
            cause is SocketException && cause.message.orEmpty().lowercase().let { message ->
                message.contains("broken pipe") ||
                    message.contains("connection reset") ||
                    message.contains("connection abort")
            }
        }

    private fun openUpstream(
        url: String,
        method: String,
        clientHeaders: Map<String, String>,
    ): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Referer", "${AnimeLibClient.WEB_ORIGIN}/")
        setRequestProperty("Origin", AnimeLibClient.WEB_ORIGIN)
        setRequestProperty("User-Agent", AnimeLibClient.USER_AGENT)
        setRequestProperty("Accept-Encoding", "identity")
        clientHeaders["range"]?.let { setRequestProperty("Range", it) }
        clientHeaders["if-range"]?.let { setRequestProperty("If-Range", it) }
        responseCode
    }

    private fun relayUpstream(
        upstream: HttpURLConnection,
        output: BufferedOutputStream,
        method: String,
    ) {
        val status = upstream.responseCode
        val reason = upstream.responseMessage.orEmpty().ifBlank { "Upstream" }
        writeAscii(output, "HTTP/1.1 $status ${sanitize(reason)}\r\n")
        listOf(
            "Content-Type",
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "ETag",
            "Last-Modified",
            "Cache-Control",
        ).forEach { name ->
            upstream.getHeaderField(name)?.let {
                writeAscii(output, "$name: ${sanitize(it)}\r\n")
            }
        }
        writeAscii(output, "Connection: close\r\n\r\n")
        output.flush()
        if (method != "GET") return
        val body = if (status in 200..399) upstream.inputStream else upstream.errorStream
        body?.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (running.get()) {
                val read = stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.flush()
        }
    }

    private fun writePlaylist(output: BufferedOutputStream, method: String, port: Int) {
        val base = "http://127.0.0.1:$port"
        val playlist = buildVlcPlaylist(base, token, items).toByteArray(Charsets.UTF_8)
        writeAscii(output, "HTTP/1.1 200 OK\r\n")
        writeAscii(output, "Content-Type: application/x-mpegURL; charset=utf-8\r\n")
        writeAscii(output, "Content-Length: ${playlist.size}\r\n")
        writeAscii(output, "Cache-Control: no-store\r\nConnection: close\r\n\r\n")
        if (method == "GET") output.write(playlist)
        output.flush()
    }

    private fun subtitleExtension(subtitle: SubtitleTrack): String =
        when (subtitle.format.lowercase()) {
            "ass", "ssa" -> "ass"
            "srt", "subrip" -> "srt"
            "ttml", "xml" -> "ttml"
            else -> "vtt"
        }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        repeat(100) {
            val line = readLine(input, 16 * 1024)
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            require(separator > 0)
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        error("Too many headers")
    }

    private fun readLine(input: BufferedInputStream, maxLength: Int): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < maxLength) {
            val next = input.read()
            if (next == -1) throw EOFException()
            if (next == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            bytes += next.toByte()
        }
        error("HTTP line too long")
    }

    private fun writeEmpty(output: BufferedOutputStream, status: Int, reason: String) {
        writeAscii(output, "HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        output.flush()
    }

    private fun writeAscii(output: BufferedOutputStream, value: String) =
        output.write(value.toByteArray(Charsets.US_ASCII))

    private fun sanitize(value: String): String = value.replace("\r", "").replace("\n", "")

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        workers.shutdownNow()
    }

    private companion object {
        const val TAG = "VlcLoopbackProxy"
        val DNS_RETRY_DELAYS_MS = longArrayOf(1_000, 4_000, 6_000, 0)
    }
}

internal fun buildVlcPlaylist(base: String, token: String, items: List<VlcQueueItem>): String =
    buildString {
        append("#EXTM3U\n")
        items.forEachIndexed { index, item ->
            append("#EXTINF:-1,")
            append(item.title.replace('\r', ' ').replace('\n', ' ').replace(',', ' '))
            append('\n')
            item.subtitle?.let {
                val extension = when (it.format.lowercase()) {
                    "ass", "ssa" -> "ass"
                    "srt", "subrip" -> "srt"
                    "ttml", "xml" -> "ttml"
                    else -> "vtt"
                }
                append("#EXTVLCOPT:sub-file=$base/subtitle/$token/$index.$extension\n")
            }
            append("$base/video/$token/$index.mp4\n")
        }
    }
