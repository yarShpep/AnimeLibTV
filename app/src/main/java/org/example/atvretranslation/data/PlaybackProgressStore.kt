package org.example.atvretranslation.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PlaybackProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback_progress", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<PlaybackProgress> = readAll()
        .sortedByDescending(PlaybackProgress::updatedAtMs)

    @Synchronized
    fun forAnime(animeId: Long): PlaybackProgress? =
        readAll().firstOrNull { it.animeId == animeId }

    @Synchronized
    fun save(progress: PlaybackProgress) {
        val updated = readAll()
            .filterNot { it.animeId == progress.animeId }
            .plus(progress)
            .sortedByDescending(PlaybackProgress::updatedAtMs)
            .take(MAX_ITEMS)
        val array = JSONArray()
        updated.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun readAll(): List<PlaybackProgress> = runCatching {
        val array = JSONArray(preferences.getString(KEY_ITEMS, "[]"))
        List(array.length()) { index -> array.getJSONObject(index).toProgress() }
    }.getOrElse {
        preferences.edit().remove(KEY_ITEMS).apply()
        emptyList()
    }

    private fun PlaybackProgress.toJson(): JSONObject = JSONObject()
        .put("anime_id", animeId)
        .put("anime_slug", animeSlug)
        .put("anime_name", animeName)
        .put("anime_cover", animeCover)
        .put("episode_id", episodeId)
        .put("episode_number", episodeNumber)
        .put("episode_name", episodeName)
        .put("episode_item_number", episodeItemNumber)
        .put("team_id", teamId)
        .put("translation_type_id", translationTypeId)
        .put("quality", quality)
        .put("position_ms", positionMs)
        .put("duration_ms", durationMs)
        .put("completed", completed)
        .put("updated_at_ms", updatedAtMs)

    private fun JSONObject.toProgress(): PlaybackProgress = PlaybackProgress(
        animeId = getLong("anime_id"),
        animeSlug = getString("anime_slug"),
        animeName = getString("anime_name"),
        animeCover = if (isNull("anime_cover")) null else optString("anime_cover"),
        episodeId = getLong("episode_id"),
        episodeNumber = optString("episode_number"),
        episodeName = optString("episode_name"),
        episodeItemNumber = if (isNull("episode_item_number")) null else optInt("episode_item_number"),
        teamId = getLong("team_id"),
        translationTypeId = getInt("translation_type_id"),
        quality = getInt("quality"),
        positionMs = getLong("position_ms"),
        durationMs = getLong("duration_ms"),
        completed = optBoolean("completed"),
        updatedAtMs = getLong("updated_at_ms"),
    )

    private companion object {
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 30
    }
}
