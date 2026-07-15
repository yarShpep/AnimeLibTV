package org.example.atvretranslation.data

data class Anime(
    val id: Long,
    val slug: String,
    val name: String,
    val russianName: String?,
    val coverThumbnail: String?,
    val cover: String?,
    val type: String?,
    val teams: List<Team> = emptyList(),
) {
    val displayName: String get() = russianName?.takeIf(String::isNotBlank) ?: name
}

data class Team(val id: Long, val name: String)

data class Episode(
    val id: Long,
    val number: String,
    val season: String,
    val name: String,
    val itemNumber: Int?,
    val players: List<PlayerSource> = emptyList(),
) {
    val displayName: String
        get() = buildString {
            append("Серия ")
            append(number)
            if (name.isNotBlank()) append(" · ").append(name)
        }
}

data class PlayerSource(
    val id: Long,
    val provider: String,
    val team: Team,
    val translationTypeId: Int,
    val translationType: String,
    val qualities: List<VideoQuality>,
    val subtitles: List<SubtitleTrack> = emptyList(),
) {
    val isNative: Boolean get() = provider.equals("Animelib", ignoreCase = true)
    val label: String get() = "${team.name} · $translationType"
}

data class VideoQuality(val height: Int, val href: String)

data class SubtitleTrack(
    val format: String,
    val src: String,
    val label: String? = null,
    val language: String? = null,
) {
    val displayName: String
        get() = label?.takeIf(String::isNotBlank)
            ?: language?.takeIf(String::isNotBlank)
            ?: "Субтитры"
}

data class VideoConstants(
    val servers: List<VideoServer>,
    val distributionAnimeIds: Set<String>,
    val distributionUrl: String,
)

data class VideoServer(val id: String, val label: String, val url: String)

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
)

data class FilterOption(val id: Long, val label: String)

enum class CatalogSort(val apiValue: String, val label: String) {
    POPULAR("rating_score", "Популярные"),
    RATING("rate_avg", "По рейтингу"),
    VIEWS("views", "По просмотрам"),
    NEWEST("created_at", "Новинки"),
    EPISODES("episodes_count", "Больше серий"),
}

data class CatalogFilters(
    val genreIds: Set<Long> = emptySet(),
    val typeIds: Set<Long> = emptySet(),
    val statusIds: Set<Long> = emptySet(),
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val sort: CatalogSort = CatalogSort.POPULAR,
) {
    val activeCount: Int
        get() = genreIds.size + typeIds.size + statusIds.size +
            (if (yearMin != null || yearMax != null) 1 else 0) +
            (if (sort != CatalogSort.POPULAR) 1 else 0)
}

data class CatalogFilterOptions(
    val genres: List<FilterOption> = emptyList(),
    val types: List<FilterOption> = emptyList(),
    val statuses: List<FilterOption> = emptyList(),
)

data class CatalogPage(
    val items: List<Anime>,
    val page: Int,
    val hasNextPage: Boolean,
)

data class PlaybackProgress(
    val animeId: Long,
    val animeSlug: String,
    val animeName: String,
    val animeCover: String?,
    val episodeId: Long,
    val episodeNumber: String,
    val episodeName: String,
    val episodeItemNumber: Int?,
    val teamId: Long,
    val translationTypeId: Int,
    val quality: Int,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtMs: Long,
)

data class ContinueTarget(val episode: Episode, val positionMs: Long)

fun resolveContinueTarget(
    episodes: List<Episode>,
    progress: PlaybackProgress,
): ContinueTarget? {
    val ordered = episodes.sortedWith(
        compareBy<Episode> { it.itemNumber ?: Int.MAX_VALUE }
            .thenBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }
            .thenBy(Episode::id),
    )
    val currentIndex = ordered.indexOfFirst { it.id == progress.episodeId }
    if (currentIndex < 0) return null
    if (progress.completed && currentIndex + 1 < ordered.size) {
        return ContinueTarget(ordered[currentIndex + 1], 0L)
    }
    return ContinueTarget(ordered[currentIndex], progress.positionMs.coerceAtLeast(0L))
}

val QualityPriority = listOf(2160, 1440, 1080, 720, 480, 360)

fun List<VideoQuality>.sortedByPlaybackPriority(): List<VideoQuality> =
    sortedWith(compareBy { quality ->
        QualityPriority.indexOf(quality.height).takeIf { it >= 0 } ?: Int.MAX_VALUE
    })

private val SubtitleFormatPriority = listOf("ass", "ssa", "vtt", "srt", "subrip", "ttml")

/**
 * AnimeLib обычно возвращает один перевод в нескольких форматах (ASS + VTT).
 * Оставляем по одному варианту на язык/название, как делает web-плеер, и предпочитаем ASS.
 */
fun List<SubtitleTrack>.preferredForPlayback(): List<SubtitleTrack> =
    groupBy { subtitle ->
        subtitle.language?.takeIf(String::isNotBlank)
            ?: subtitle.label?.takeIf(String::isNotBlank)
            ?: "default"
    }.values.map { variants ->
        variants.minBy { subtitle ->
            SubtitleFormatPriority.indexOf(subtitle.format.lowercase()).takeIf { it >= 0 }
                ?: Int.MAX_VALUE
        }
    }

fun resolveSubtitleUrl(src: String, webOrigin: String): String = when {
    src.startsWith("https://", ignoreCase = true) || src.startsWith("http://", ignoreCase = true) -> src
    src.startsWith("//") -> "https:$src"
    src.startsWith("/") -> webOrigin.trimEnd('/') + src
    else -> webOrigin.trimEnd('/') + "/" + src
}

fun buildVideoUrl(
    quality: VideoQuality,
    constants: VideoConstants,
    serverId: String = "main",
): String {
    val animeId = Regex("""anime/([^/]+)/players""")
        .find(quality.href)
        ?.groupValues
        ?.getOrNull(1)
    val base = if (animeId != null && animeId in constants.distributionAnimeIds) {
        constants.distributionUrl
    } else {
        constants.servers.firstOrNull { it.id == serverId }?.url
            ?: error("Сервер видео '$serverId' отсутствует в /constants")
    }
    return base + quality.href
}
