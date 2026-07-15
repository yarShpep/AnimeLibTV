package org.example.atvretranslation.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSelectionTest {
    @Test
    fun `quality order prefers only qualities actually returned by API`() {
        val available = listOf(
            VideoQuality(720, "720.mp4"),
            VideoQuality(1440, "1440.mp4"),
            VideoQuality(1080, "1080.mp4"),
        )

        assertEquals(listOf(1440, 1080, 720), available.sortedByPlaybackPriority().map { it.height })
    }

    @Test
    fun `url builder concatenates opaque server path without URI normalization`() {
        val constants = VideoConstants(
            servers = listOf(VideoServer("main", "Основной", "https://video.example/.аs/")),
            distributionAnimeIds = emptySet(),
            distributionUrl = "https://distribution.example/",
        )
        val source = VideoQuality(2160, "anime/12/players/59520/video_2160.mp4")

        assertEquals(
            "https://video.example/.аs/anime/12/players/59520/video_2160.mp4",
            buildVideoUrl(source, constants),
        )
    }

    @Test
    fun `distribution list switches only the documented base URL`() {
        val constants = VideoConstants(
            servers = listOf(VideoServer("main", "Основной", "https://main.example/opaque/")),
            distributionAnimeIds = setOf("12"),
            distributionUrl = "https://distribution.example/",
        )

        assertEquals(
            "https://distribution.example/anime/12/players/7/video.mp4",
            buildVideoUrl(VideoQuality(1080, "anime/12/players/7/video.mp4"), constants),
        )
    }

    @Test
    fun `subtitle variants keep one track per language and prefer ass`() {
        val subtitles = listOf(
            SubtitleTrack("vtt", "https://example/sub.vtt", label = "Русский"),
            SubtitleTrack("ass", "https://example/sub.ass", label = "Русский"),
            SubtitleTrack("vtt", "https://example/en.vtt", label = "English"),
        )

        assertEquals(
            listOf("https://example/sub.ass", "https://example/en.vtt"),
            subtitles.preferredForPlayback().map(SubtitleTrack::src),
        )
    }

    @Test
    fun `subtitle url uses source directly and resolves browser-relative paths`() {
        assertEquals(
            "https://sub.example/file.ass",
            resolveSubtitleUrl("https://sub.example/file.ass", "https://v5.animelib.org"),
        )
        assertEquals(
            "https://sub.example/file.vtt",
            resolveSubtitleUrl("//sub.example/file.vtt", "https://v5.animelib.org"),
        )
        assertEquals(
            "https://v5.animelib.org/storage/file.srt",
            resolveSubtitleUrl("/storage/file.srt", "https://v5.animelib.org"),
        )
    }

    @Test
    fun `incomplete episode resumes from stored position`() {
        val episodes = listOf(episode(1, "1", 1), episode(2, "2", 2))

        val target = resolveContinueTarget(episodes, progress(episodeId = 1, completed = false))

        assertEquals(1L, target?.episode?.id)
        assertEquals(42_000L, target?.positionMs)
    }

    @Test
    fun `completed episode continues with next episode from start`() {
        val episodes = listOf(episode(1, "1", 1), episode(2, "2", 2))

        val target = resolveContinueTarget(episodes, progress(episodeId = 1, completed = true))

        assertEquals(2L, target?.episode?.id)
        assertEquals(0L, target?.positionMs)
    }

    private fun episode(id: Long, number: String, itemNumber: Int) = Episode(
        id = id,
        number = number,
        season = "1",
        name = "",
        itemNumber = itemNumber,
    )

    private fun progress(episodeId: Long, completed: Boolean) = PlaybackProgress(
        animeId = 10,
        animeSlug = "10--test-anime",
        animeName = "Test",
        animeCover = null,
        episodeId = episodeId,
        episodeNumber = episodeId.toString(),
        episodeName = "",
        episodeItemNumber = episodeId.toInt(),
        teamId = 1,
        translationTypeId = 2,
        quality = 1080,
        positionMs = 42_000,
        durationMs = 100_000,
        completed = completed,
        updatedAtMs = 1,
    )
}
