@file:Suppress("unused")

package org.example.animelib

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.squareup.moshi.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// ---------- API DTO ----------

data class ApiEnvelope<T>(
    val data: T,
    val links: Map<String, Any?>? = null,
    val meta: Map<String, Any?>? = null,
)

data class OAuthTokenDto(
    @Json(name = "token_type") val tokenType: String,
    @Json(name = "expires_in") val expiresInSeconds: Long,
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
)

data class AuthorizationCodeGrant(
    @Json(name = "grant_type") val grantType: String = "authorization_code",
    @Json(name = "client_id") val clientId: String = "1",
    @Json(name = "redirect_uri") val redirectUri: String,
    @Json(name = "code_verifier") val codeVerifier: String,
    val code: String,
)

data class RefreshTokenGrant(
    @Json(name = "grant_type") val grantType: String = "refresh_token",
    @Json(name = "client_id") val clientId: String = "1",
    @Json(name = "refresh_token") val refreshToken: String,
    val scope: String = "",
)

data class AnimeDto(
    val id: Long,
    @Json(name = "slug_url") val slugUrl: String,
    val name: String,
    @Json(name = "rus_name") val russianName: String? = null,
    val teams: List<TeamDto> = emptyList(),
)

data class TeamDto(
    val id: Long,
    val name: String,
    val slug: String? = null,
    @Json(name = "slug_url") val slugUrl: String? = null,
)

data class TranslationTypeDto(
    val id: Int,
    val label: String,
)

data class EpisodeDto(
    val id: Long,
    @Json(name = "anime_id") val animeId: Long,
    val number: String,
    @Json(name = "number_secondary") val numberSecondary: String? = null,
    val season: String,
    val name: String = "",
    @Json(name = "item_number") val itemNumber: Int? = null,
    val players: List<PlayerDto> = emptyList(),
)

data class PlayerDto(
    val id: Long,
    @Json(name = "episode_id") val episodeId: Long,
    val player: String,
    @Json(name = "translation_type") val translationType: TranslationTypeDto,
    val team: TeamDto,
    val views: Long = 0,
    val src: String? = null,
    val video: VideoDto? = null,
    val subtitles: List<SubtitleDto> = emptyList(),
    val timecode: List<TimecodeDto> = emptyList(),
)

data class VideoDto(
    val id: Long? = null,
    val quality: List<VideoQualityDto> = emptyList(),
)

data class VideoQualityDto(
    val quality: Int,
    val href: String,
)

data class SubtitleDto(
    val format: String,
    val src: String,
    val label: String? = null,
)

data class TimecodeDto(
    val type: String? = null,
    val from: Double? = null,
    val to: Double? = null,
)

data class ConstantsDto(
    val videoServers: List<VideoServerDto> = emptyList(),
    val animeDistributionId: List<String> = emptyList(),
    val animeDistributionUrl: String = "",
)

data class VideoServerDto(
    val id: String,
    val label: String,
    val url: String,
)

// ---------- Retrofit ----------

interface AnimeLibApi {
    // There is no JSON username/password login in the AnimeLib frontend.
    // Login is an OAuth2 Authorization Code + PKCE browser flow at
    // https://auth.hentaicdn.org/auth/oauth/authorize.

    @POST("auth/oauth/token")
    suspend fun exchangeAuthorizationCode(
        @Body grant: AuthorizationCodeGrant,
    ): OAuthTokenDto

    @POST("auth/oauth/token")
    suspend fun refreshToken(
        @Body grant: RefreshTokenGrant,
    ): OAuthTokenDto

    @GET("auth/me")
    suspend fun me(): ApiEnvelope<Map<String, Any?>>

    @DELETE("auth/oauth/token")
    suspend fun logout(): retrofit2.Response<Unit>

    @GET("anime")
    suspend fun searchAnime(
        @Query("q") query: String,
        @Query("fields[]") fields: List<String> = listOf("rate_avg", "rate", "releaseDate"),
        @Query("page") page: Int = 1,
    ): ApiEnvelope<List<AnimeDto>>

    @GET("anime/{slug}")
    suspend fun animeDetails(
        @Path("slug") slug: String,
        @Query("fields[]") fields: List<String> = listOf("teams", "episodes_count"),
    ): ApiEnvelope<AnimeDto>

    @GET("episodes")
    suspend fun episodes(
        @Query("anime_id") animeSlug: String,
    ): ApiEnvelope<List<EpisodeDto>>

    @GET("episodes/{episodeId}")
    suspend fun episode(
        @Path("episodeId") episodeId: Long,
    ): ApiEnvelope<EpisodeDto>

    @GET("constants")
    suspend fun constants(
        @Query("fields[]") fields: List<String> = listOf(
            "videoServers",
            "animeDistributionId",
            "animeDistributionUrl",
        ),
    ): ApiEnvelope<ConstantsDto>
}

interface TokenStore {
    fun accessToken(): String?
}

class AnimeLibHeadersInterceptor(
    private val tokenStore: TokenStore,
    private val userAgent: String,
    private val timeZone: () -> String = { java.util.TimeZone.getDefault().id },
) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Site-Id", "5")
            .header("Origin", "https://v5.animelib.org")
            .header("Referer", "https://v5.animelib.org/")
            .header("Client-Time-Zone", timeZone())
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")

        tokenStore.accessToken()
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Authorization", "Bearer $it") }

        return chain.proceed(builder.build())
    }
}

// Refresh should be serialized in an OkHttp Authenticator or repository mutex.
// Never call refresh through the same client while holding an interceptor call.

// ---------- Player/source selection ----------

private val QUALITY_PRIORITY = listOf(2160, 1440, 1080, 720, 480, 360)

fun selectAnimeLibPlayer(
    episode: EpisodeDto,
    teamId: Long,
    translationType: Int,
): PlayerDto? = episode.players.firstOrNull { candidate ->
    candidate.player.equals("Animelib", ignoreCase = true) &&
        candidate.team.id == teamId &&
        candidate.translationType.id == translationType &&
        !candidate.video?.quality.isNullOrEmpty()
}

fun selectMaximumQuality(qualities: List<VideoQualityDto>): VideoQualityDto? {
    val byHeight = qualities.associateBy(VideoQualityDto::quality)
    return QUALITY_PRIORITY.firstNotNullOfOrNull(byHeight::get)
}

/** Replicates the frontend rule. Do not derive or mutate the href suffix. */
fun buildVideoUrl(
    source: VideoQualityDto,
    constants: ConstantsDto,
    serverId: String = "main",
): String {
    val animeIdInPath = Regex("""anime/([^/]+)/players""")
        .find(source.href)
        ?.groupValues
        ?.getOrNull(1)

    val baseUrl = if (
        animeIdInPath != null && animeIdInPath in constants.animeDistributionId
    ) {
        constants.animeDistributionUrl
    } else {
        constants.videoServers.first { it.id == serverId }.url
    }

    // The website uses string concatenation, not URI resolution. URI.resolve() could
    // remove an intentional path component from the server base.
    return baseUrl + source.href
}

// ---------- Media3 ----------

@OptIn(UnstableApi::class)
fun createMedia3Player(
    context: Context,
    videoUrl: String,
    userAgent: String,
    referer: String = "https://v5.animelib.org/",
): ExoPlayer {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setDefaultRequestProperties(
            mapOf(
                "Referer" to referer,
                "Origin" to "https://v5.animelib.org",
                "Accept-Encoding" to "identity",
            ),
        )

    val mediaSourceFactory = DefaultMediaSourceFactory(context)
        .setDataSourceFactory(httpFactory)

    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .also { player ->
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(videoUrl)
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build(),
            )
            // Media3 emits byte Range requests from DataSpec as needed. Do not set a
            // static Range header here; that would break seeks and length detection.
            player.prepare()
            player.playWhenReady = true
        }
}

// ---------- VLC ----------

fun openInVlc(context: Context, videoUrl: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(videoUrl), "video/mp4")
        setPackage("org.videolan.vlc")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        throw IllegalStateException("VLC for Android is not installed", e)
    }
}

// ---------- Minimal streaming loopback proxy for VLC ----------

/**
 * One-URL, loopback-only streaming proxy.
 *
 * - supports GET and HEAD;
 * - forwards Range and If-Range;
 * - preserves 200/206 and Content-Range/Content-Length;
 * - streams ResponseBody without buffering the video;
 * - uses an unguessable path and can be closed after playback.
 */
class LoopbackVideoProxy(
    private val upstreamUrl: HttpUrl,
    private val client: OkHttpClient,
    private val upstreamHeaders: Map<String, String>,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val serverRef = AtomicReference<ServerSocket?>()
    private val workerPool = Executors.newCachedThreadPool()
    private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private val token = ByteArray(24).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    @Volatile
    private var port: Int = -1

    fun start(): Uri {
        check(running.compareAndSet(false, true)) { "Proxy already started" }
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverRef.set(server)
        port = server.localPort

        thread(name = "animelib-loopback-accept", isDaemon = true) {
            try {
                while (running.get()) {
                    val socket = server.accept()
                    workerPool.execute { handle(socket) }
                }
            } catch (e: IOException) {
                if (running.get()) close()
            }
        }
        return Uri.parse("http://127.0.0.1:$port/video/$token")
    }

    private fun handle(socket: Socket) {
        socket.use { clientSocket ->
            clientSocket.soTimeout = 30_000
            val input = BufferedInputStream(clientSocket.getInputStream())
            val output = BufferedOutputStream(clientSocket.getOutputStream())

            try {
                val requestLine = readAsciiLine(input, 8 * 1024)
                val parts = requestLine.split(' ')
                if (parts.size != 3) return writeError(output, 400, "Bad Request")

                val method = parts[0]
                val path = parts[1].substringBefore('?')
                if (method != "GET" && method != "HEAD") {
                    return writeError(output, 405, "Method Not Allowed")
                }
                if (path != "/video/$token") {
                    return writeError(output, 404, "Not Found")
                }

                val requestHeaders = readHeaders(input)
                val upstream = Request.Builder()
                    .url(upstreamUrl)
                    .method(method, null)
                    .header("Accept-Encoding", "identity")
                    .apply {
                        upstreamHeaders.forEach { (name, value) -> header(name, value) }
                        requestHeaders["range"]?.let { header("Range", it) }
                        requestHeaders["if-range"]?.let { header("If-Range", it) }
                    }
                    .build()

                val call = client.newCall(upstream)
                activeCalls += call
                try {
                    call.execute().use { response ->
                        relayHeaders(output, response)
                        if (method == "GET" && response.code != 204 && response.code != 304) {
                            response.body?.byteStream()?.use { body ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (running.get()) {
                                    val read = body.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    output.flush()
                                }
                            }
                        }
                    }
                } finally {
                    activeCalls -= call
                }
            } catch (_: Exception) {
                // VLC may close a Range connection while seeking; that is normal.
            }
        }
    }

    private fun relayHeaders(output: BufferedOutputStream, response: Response) {
        val reason = response.message.ifBlank { reasonPhrase(response.code) }
        writeAscii(output, "HTTP/1.1 ${response.code} $reason\r\n")

        val passThrough = listOf(
            "Content-Type",
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "ETag",
            "Last-Modified",
            "Cache-Control",
        )
        passThrough.forEach { name ->
            response.header(name)?.let { value ->
                writeAscii(output, "$name: ${sanitizeHeader(value)}\r\n")
            }
        }
        writeAscii(output, "Connection: close\r\n")
        writeAscii(output, "Access-Control-Allow-Origin: *\r\n")
        writeAscii(output, "\r\n")
        output.flush()
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        repeat(100) {
            val line = readAsciiLine(input, 16 * 1024)
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) throw IOException("Malformed header")
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[name] = value
        }
        throw IOException("Too many headers")
    }

    private fun readAsciiLine(input: BufferedInputStream, maxLength: Int): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < maxLength) {
            val b = input.read()
            if (b == -1) throw EOFException()
            if (b == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            }
            bytes += b.toByte()
        }
        throw IOException("HTTP line too long")
    }

    private fun writeError(output: BufferedOutputStream, code: Int, reason: String) {
        writeAscii(
            output,
            "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        )
        output.flush()
    }

    private fun writeAscii(output: BufferedOutputStream, value: String) {
        output.write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun sanitizeHeader(value: String): String = value.replace("\r", "").replace("\n", "")

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        502 -> "Bad Gateway"
        else -> "Upstream"
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        serverRef.getAndSet(null)?.runCatching { close() }
        activeCalls.toList().forEach(Call::cancel)
        workerPool.shutdownNow()
    }
}
