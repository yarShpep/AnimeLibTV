package org.example.atvretranslation.player

import org.example.atvretranslation.data.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcPlaylistTest {
    @Test
    fun `playlist keeps current episode first and exposes next episode`() {
        val playlist = buildVlcPlaylist(
            base = "http://127.0.0.1:1234",
            token = "secret",
            items = listOf(
                VlcQueueItem("Series 1", "https://cdn/1.mp4", SubtitleTrack("ass", "https://cdn/1.ass")),
                VlcQueueItem("Series 2", "https://cdn/2.mp4"),
            ),
        )

        assertTrue(playlist.startsWith("#EXTM3U\n#EXTINF:-1,Series 1"))
        assertTrue(playlist.contains("#EXTVLCOPT:sub-file=http://127.0.0.1:1234/subtitle/secret/0.ass"))
        assertEquals(
            listOf(
                "http://127.0.0.1:1234/video/secret/0.mp4",
                "http://127.0.0.1:1234/video/secret/1.mp4",
            ),
            playlist.lineSequence().filter { it.endsWith(".mp4") }.toList(),
        )
    }
}
