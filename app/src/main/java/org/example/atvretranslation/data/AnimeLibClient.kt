package org.example.atvretranslation.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.TimeZone

class AnimeLibClient(private val tokenStore: SecureTokenStore) {
    private val refreshMutex = Mutex()

    suspend fun catalog(
        query: String = "",
        filters: CatalogFilters = CatalogFilters(),
    ): List<Anime> = catalogPage(query, filters).items

    suspend fun catalogPage(
        query: String = "",
        filters: CatalogFilters = CatalogFilters(),
        page: Int = 1,
    ): CatalogPage {
        val parameters = buildList {
            add("page" to page.coerceAtLeast(1).toString())
            if (query.isNotBlank()) add("q" to query)
            add("sort_by" to filters.sort.apiValue)
            add("sort_type" to "desc")
            if (filters.sort == CatalogSort.RATING) add("rate_min" to "50")
            filters.genreIds.forEach { add("genres[]" to it.toString()) }
            filters.typeIds.forEach { add("types[]" to it.toString()) }
            filters.statusIds.forEach { add("status[]" to it.toString()) }
            filters.yearMin?.let { add("year_min" to it.toString()) }
            filters.yearMax?.let { add("year_max" to it.toString()) }
        }.joinToString("&") { (name, value) -> "${name.urlEncoded()}=${value.urlEncoded()}" }
        val response = request("anime?$parameters")
        val meta = response.optJSONObject("meta")
        return CatalogPage(
            items = response.getJSONArray("data").mapObjects(::parseAnime),
            page = meta?.optInt("current_page", page) ?: page,
            hasNextPage = response.optJSONObject("links")?.optNullableString("next") != null,
        )
    }

    suspend fun catalogFilterOptions(): CatalogFilterOptions {
        val data = request(
            "constants?fields%5B%5D=genres&fields%5B%5D=types&fields%5B%5D=status",
        ).getJSONObject("data")
        fun parseOptions(name: String, labelKey: String): List<FilterOption> =
            data.getJSONArray(name).mapObjects { item ->
                if (!item.belongsToAnimeLib()) return@mapObjects null
                FilterOption(item.getLong("id"), item.optString(labelKey))
            }.filterNotNull().filter { it.label.isNotBlank() }.sortedBy { it.label }
        return CatalogFilterOptions(
            genres = parseOptions("genres", "name"),
            types = parseOptions("types", "label"),
            statuses = parseOptions("status", "label"),
        )
    }

    suspend fun anime(slug: String): Anime = request(
        "anime/${slug.urlEncoded()}?fields%5B%5D=teams&fields%5B%5D=episodes_count",
    ).getJSONObject("data").let(::parseAnime)

    suspend fun episodes(slug: String): List<Episode> = request(
        "episodes?anime_id=${slug.urlEncoded()}",
    ).getJSONArray("data").mapObjects(::parseEpisode)

    suspend fun episode(id: Long): Episode =
        request("episodes/$id").getJSONObject("data").let(::parseEpisode)

    suspend fun constants(): VideoConstants {
        val data = request(
            "constants?fields%5B%5D=videoServers&fields%5B%5D=animeDistributionId" +
                "&fields%5B%5D=animeDistributionUrl",
        ).getJSONObject("data")
        return VideoConstants(
            servers = data.getJSONArray("videoServers").mapObjects { item ->
                VideoServer(item.getString("id"), item.optString("label"), item.getString("url"))
            },
            distributionAnimeIds = data.getJSONArray("animeDistributionId")
                .mapStrings()
                .toSet(),
            distributionUrl = data.optString("animeDistributionUrl"),
        )
    }

    suspend fun exchangeAuthorizationCode(code: String, verifier: String, redirectUri: String) {
        val body = JSONObject()
            .put("grant_type", "authorization_code")
            .put("client_id", "1")
            .put("redirect_uri", redirectUri)
            .put("code_verifier", verifier)
            .put("code", code)
        tokenStore.write(parseTokens(post("auth/oauth/token", body, includeToken = false)))
    }

    suspend fun logout() = withContext(Dispatchers.IO) { tokenStore.clear() }

    fun isAuthenticated(): Boolean = tokenStore.read() != null

    private suspend fun request(path: String): JSONObject {
        val token = validAccessToken()
        return get(path, token)
    }

    private suspend fun validAccessToken(): String? {
        val current = tokenStore.read() ?: return null
        if (current.expiresAtEpochSeconds > nowEpochSeconds() + 60) return current.accessToken
        return refreshMutex.withLock {
            val newest = tokenStore.read() ?: return@withLock null
            if (newest.expiresAtEpochSeconds > nowEpochSeconds() + 60) {
                return@withLock newest.accessToken
            }
            runCatching {
                val body = JSONObject()
                    .put("grant_type", "refresh_token")
                    .put("client_id", "1")
                    .put("refresh_token", newest.refreshToken)
                    .put("scope", "")
                parseTokens(post("auth/oauth/token", body, includeToken = false))
            }.onSuccess(tokenStore::write).getOrElse {
                tokenStore.clear()
                null
            }?.accessToken
        }
    }

    private suspend fun get(path: String, accessToken: String?): JSONObject = withContext(Dispatchers.IO) {
        openConnection(API_BASE + path, "GET", accessToken).readJson()
    }

    private suspend fun post(path: String, body: JSONObject, includeToken: Boolean): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = openConnection(
                API_BASE + path,
                "POST",
                if (includeToken) tokenStore.read()?.accessToken else null,
            )
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            connection.readJson()
        }

    private fun openConnection(url: String, method: String, accessToken: String?): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Site-Id", "5")
            setRequestProperty("Origin", WEB_ORIGIN)
            setRequestProperty("Referer", "$WEB_ORIGIN/")
            setRequestProperty("Client-Time-Zone", TimeZone.getDefault().id)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            accessToken?.takeIf(String::isNotBlank)?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }

    private fun HttpURLConnection.readJson(): JSONObject = try {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw AnimeLibHttpException(code, text.take(500))
        JSONObject(text)
    } finally {
        disconnect()
    }

    private fun parseTokens(response: JSONObject): OAuthTokens {
        val expiresIn = response.optLong("expires_in", 0L)
        return OAuthTokens(
            accessToken = response.getString("access_token"),
            refreshToken = response.getString("refresh_token"),
            expiresAtEpochSeconds = nowEpochSeconds() + expiresIn,
        )
    }

    private fun parseAnime(json: JSONObject): Anime {
        val cover = json.optJSONObject("cover")
        return Anime(
            id = json.getLong("id"),
            slug = json.getString("slug_url"),
            name = json.optString("name"),
            russianName = json.optNullableString("rus_name"),
            coverThumbnail = cover?.optNullableString("thumbnail"),
            cover = cover?.optNullableString("default"),
            type = json.optJSONObject("type")?.optNullableString("label"),
            teams = json.optJSONArray("teams")?.mapObjects(::parseTeam).orEmpty(),
        )
    }

    private fun parseEpisode(json: JSONObject): Episode = Episode(
        id = json.getLong("id"),
        number = json.optString("number"),
        season = json.optString("season"),
        name = json.optString("name"),
        itemNumber = json.optInt("item_number").takeIf { json.has("item_number") && !json.isNull("item_number") },
        players = json.optJSONArray("players")?.mapObjects(::parsePlayer).orEmpty(),
    )

    private fun parsePlayer(json: JSONObject): PlayerSource {
        val translation = json.optJSONObject("translation_type") ?: JSONObject()
        val qualities = json.optJSONObject("video")
            ?.optJSONArray("quality")
            ?.mapObjects { item -> VideoQuality(item.getInt("quality"), item.getString("href")) }
            .orEmpty()
        val subtitles = json.optJSONArray("subtitles")
            ?.mapObjects { item ->
                val src = item.optNullableString("src")
                    ?: item.optNullableString("url")
                    ?: return@mapObjects null
                val format = item.optNullableString("format")
                    ?: src.substringBefore('?').substringAfterLast('.', "vtt")
                SubtitleTrack(
                    format = format.lowercase(),
                    src = resolveSubtitleUrl(src, WEB_ORIGIN),
                    label = item.optNullableString("label") ?: item.optNullableString("name"),
                    language = item.optNullableString("language")
                        ?: item.optNullableString("lang")
                        ?: item.optNullableString("locale"),
                )
            }
            ?.filterNotNull()
            .orEmpty()
        return PlayerSource(
            id = json.getLong("id"),
            provider = json.optString("player"),
            team = json.optJSONObject("team")?.let(::parseTeam) ?: Team(-1, "Неизвестная команда"),
            translationTypeId = translation.optInt("id"),
            translationType = translation.optString("label", "Перевод"),
            qualities = qualities,
            subtitles = subtitles,
        )
    }

    private fun parseTeam(json: JSONObject): Team = Team(json.getLong("id"), json.optString("name"))

    private fun JSONObject.belongsToAnimeLib(): Boolean {
        val sites = optJSONArray("site_ids") ?: return true
        return (0 until sites.length()).any { sites.optInt(it) == 5 }
    }

    companion object {
        const val WEB_ORIGIN = "https://v5.animelib.org"
        const val OAUTH_REDIRECT = "$WEB_ORIGIN/ru/front/auth/oauth/callback"
        const val USER_AGENT = "AnimeLibTV/0.3 (Android TV; Media3)"
        private const val API_BASE = "https://hapi.hentaicdn.org/api/"
    }
}

private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1_000L

class AnimeLibHttpException(val status: Int, message: String) : IOException("HTTP $status: $message")

private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { index -> transform(getJSONObject(index)) }

private fun JSONArray.mapStrings(): List<String> = List(length()) { index -> getString(index) }
