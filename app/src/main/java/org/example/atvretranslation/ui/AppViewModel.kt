package org.example.atvretranslation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.atvretranslation.data.Anime
import org.example.atvretranslation.data.AnimeLibClient
import org.example.atvretranslation.data.CatalogFilterOptions
import org.example.atvretranslation.data.CatalogFilters
import org.example.atvretranslation.data.Episode
import org.example.atvretranslation.data.PlaybackProgress
import org.example.atvretranslation.data.PlaybackProgressStore
import org.example.atvretranslation.data.PlayerSource
import org.example.atvretranslation.data.SecureTokenStore
import org.example.atvretranslation.data.SubtitleTrack
import org.example.atvretranslation.data.VideoConstants
import org.example.atvretranslation.data.VideoQuality
import org.example.atvretranslation.data.VideoStreamCandidate
import org.example.atvretranslation.data.buildVideoCandidates
import org.example.atvretranslation.data.preferredForPlayback
import org.example.atvretranslation.data.resolveContinueTarget
import org.example.atvretranslation.data.sortedByPlaybackPriority
import org.example.atvretranslation.player.VlcProxyRegistry
import org.example.atvretranslation.player.VlcQueueItem
import org.example.atvretranslation.update.AppUpdate
import org.example.atvretranslation.update.UpdateManager

enum class Screen { HOME, DETAILS, SOURCES, PLAYER }

data class PlaybackRequest(
    val videoCandidates: List<VideoStreamCandidate>,
    val title: String,
    val quality: Int,
    val startPositionMs: Long,
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
    val subtitles: List<SubtitleTrack>,
    val previousEpisodeNumber: String?,
    val nextEpisodeNumber: String?,
)

data class AppUiState(
    val screen: Screen = Screen.HOME,
    val catalog: List<Anime> = emptyList(),
    val catalogPage: Int = 1,
    val catalogHasNextPage: Boolean = true,
    val catalogLoadingMore: Boolean = false,
    val filterOptions: CatalogFilterOptions = CatalogFilterOptions(),
    val filters: CatalogFilters = CatalogFilters(),
    val filterVisible: Boolean = false,
    val continueWatching: List<PlaybackProgress> = emptyList(),
    val selectedAnime: Anime? = null,
    val episodes: List<Episode> = emptyList(),
    val episodeQuery: String = "",
    val selectedEpisode: Episode? = null,
    val constants: VideoConstants? = null,
    val playback: PlaybackRequest? = null,
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val authVisible: Boolean = false,
    val authenticated: Boolean = false,
    val availableUpdate: AppUpdate? = null,
    val updateBusy: Boolean = false,
    val updateMessage: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AnimeLibClient(SecureTokenStore(application))
    private val progressStore = PlaybackProgressStore(application)
    private val _state = MutableStateFlow(
        AppUiState(
            authenticated = client.isAuthenticated(),
            continueWatching = progressStore.all(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        loadInitialCatalog()
        checkForUpdates(silent = true)
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun setEpisodeQuery(value: String) = _state.update { it.copy(episodeQuery = value) }

    fun showFilters() = _state.update { it.copy(filterVisible = true) }

    fun hideFilters() = _state.update { it.copy(filterVisible = false) }

    fun applyFilters(filters: CatalogFilters) {
        _state.update { it.copy(filters = filters, filterVisible = false) }
        loadCatalog()
    }

    fun loadCatalog() {
        runRequest {
            val snapshot = _state.value
            val page = client.catalogPage(snapshot.query.trim(), snapshot.filters)
            _state.update {
                it.copy(
                    catalog = page.items,
                    catalogPage = page.page,
                    catalogHasNextPage = page.hasNextPage,
                    screen = Screen.HOME,
                )
            }
        }
    }

    fun loadMoreCatalog() {
        val snapshot = _state.value
        if (
            snapshot.loading || snapshot.catalogLoadingMore ||
            !snapshot.catalogHasNextPage || snapshot.screen != Screen.HOME
        ) return
        _state.update { it.copy(catalogLoadingMore = true) }
        viewModelScope.launch {
            val current = _state.value
            runCatching {
                client.catalogPage(
                    query = current.query.trim(),
                    filters = current.filters,
                    page = current.catalogPage + 1,
                )
            }.onSuccess { page ->
                _state.update { state ->
                    state.copy(
                        catalog = (state.catalog + page.items).distinctBy(Anime::id),
                        catalogPage = page.page,
                        catalogHasNextPage = page.hasNextPage,
                    )
                }
            }.onFailure { throwable ->
                _state.update {
                    it.copy(error = throwable.message ?: throwable.javaClass.simpleName)
                }
            }
            _state.update { it.copy(catalogLoadingMore = false) }
        }
    }

    fun selectAnime(anime: Anime) {
        _state.update {
            it.copy(
                screen = Screen.DETAILS,
                selectedAnime = anime,
                episodes = emptyList(),
                episodeQuery = "",
                selectedEpisode = null,
                playback = null,
            )
        }
        runRequest {
            val details = async { client.anime(anime.slug) }
            val episodes = async { client.episodes(anime.slug) }
            _state.update {
                it.copy(selectedAnime = details.await(), episodes = episodes.await())
            }
        }
    }

    fun selectEpisode(episode: Episode) {
        _state.update { it.copy(screen = Screen.SOURCES, selectedEpisode = episode) }
        runRequest {
            val fullEpisode = async { client.episode(episode.id) }
            val constants = async { _state.value.constants ?: client.constants() }
            _state.update {
                it.copy(selectedEpisode = fullEpisode.await(), constants = constants.await())
            }
        }
    }

    fun play(source: PlayerSource, quality: VideoQuality) {
        val anime = _state.value.selectedAnime ?: return setError("Не выбрано аниме")
        val episode = _state.value.selectedEpisode ?: return
        val stored = progressStore.forAnime(anime.id)
            ?.takeIf { it.episodeId == episode.id && !it.completed }
            ?.positionMs
            ?: 0L
        playResolved(anime, episode, source, quality, stored)
    }

    fun playPreviousEpisode() = playAdjacentEpisode(forward = false)

    fun playNextEpisode() = playAdjacentEpisode(forward = true)

    private fun playAdjacentEpisode(forward: Boolean) {
        val snapshot = _state.value
        if (snapshot.loading) return
        val current = snapshot.playback ?: return
        val anime = snapshot.selectedAnime ?: return setError("Не выбрано аниме")
        val target = findAdjacentEpisode(snapshot.episodes, current.episodeId, forward)
            ?: return setError(
                if (forward) "Следующей серии пока нет"
                else "Предыдущей серии нет",
            )

        runRequest {
            val loadedEpisode = client.episode(target.id)
            val constants = snapshot.constants ?: client.constants()
            val source = chooseSource(loadedEpisode, current.teamId, current.translationTypeId)
                ?: error("Для серии ${target.number} нет встроенного плеера AnimeLib")
            val quality = source.qualities.firstOrNull { it.height == current.quality }
                ?: source.qualities.sortedByPlaybackPriority().first()

            _state.update {
                it.copy(selectedEpisode = loadedEpisode, constants = constants)
            }
            playResolved(anime, loadedEpisode, source, quality, startPositionMs = 0L)
        }
    }

    fun playInVlc() {
        val snapshot = _state.value
        if (snapshot.loading) return
        val current = snapshot.playback ?: return
        val currentItem = VlcQueueItem(
            title = current.title,
            upstreamUrls = current.videoCandidates
                .filter { it.quality == current.quality }
                .map(VideoStreamCandidate::url),
            subtitle = current.subtitles.firstOrNull(),
        )
        val next = findAdjacentEpisode(snapshot.episodes, current.episodeId, forward = true)
        if (next == null) {
            VlcProxyRegistry.open(getApplication(), listOf(currentItem))
                .onFailure { setError(it.message ?: "Не удалось открыть VLC") }
            return
        }

        runRequest {
            val loadedEpisode = client.episode(next.id)
            val source = chooseSource(loadedEpisode, current.teamId, current.translationTypeId)
                ?: error("Для серии ${next.number} нет встроенного плеера AnimeLib")
            val quality = source.qualities.firstOrNull { it.height == current.quality }
                ?: source.qualities.sortedByPlaybackPriority().first()
            val constants = snapshot.constants ?: client.constants()
            VlcProxyRegistry.open(
                getApplication(),
                listOf(
                    currentItem,
                    VlcQueueItem(
                        title = "${loadedEpisode.displayName} · ${source.team.name}",
                        upstreamUrls = buildVideoCandidates(
                            quality,
                            source.qualities,
                            constants,
                            source.videoDomain,
                        ).filter { it.quality == quality.height }
                            .map(VideoStreamCandidate::url),
                        subtitle = source.subtitles.preferredForPlayback().firstOrNull(),
                    ),
                ),
            ).getOrThrow()
        }
    }

    fun checkForUpdates(silent: Boolean = false) {
        if (_state.value.updateBusy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    updateBusy = true,
                    updateMessage = if (silent) it.updateMessage else "Проверяю обновления…",
                )
            }
            runCatching { UpdateManager.check(getApplication()) }
                .onSuccess { update ->
                    _state.update {
                        it.copy(
                            availableUpdate = update,
                            updateMessage = when {
                                update != null -> "Доступна версия ${update.versionName}"
                                silent -> null
                                else -> "Установлена актуальная версия"
                            },
                        )
                    }
                }
                .onFailure { error ->
                    if (!silent) {
                        _state.update {
                            it.copy(
                                updateMessage = error.message
                                    ?: "Не удалось проверить обновление",
                            )
                        }
                    }
                }
            _state.update { it.copy(updateBusy = false) }
        }
    }

    fun installAvailableUpdate() {
        val update = _state.value.availableUpdate ?: return checkForUpdates()
        if (_state.value.updateBusy) return
        viewModelScope.launch {
            _state.update {
                it.copy(updateBusy = true, updateMessage = "Скачиваю ${update.versionName}…")
            }
            runCatching { UpdateManager.downloadAndInstall(getApplication(), update) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            updateMessage = error.message
                                ?: "Не удалось скачать обновление",
                        )
                    }
                }
            _state.update { it.copy(updateBusy = false) }
        }
    }

    fun continueWatching(progress: PlaybackProgress) {
        runRequest {
            val details = async { client.anime(progress.animeSlug) }
            val episodesDeferred = async { client.episodes(progress.animeSlug) }
            val anime = details.await()
            val episodes = episodesDeferred.await()
            val target = resolveContinueTarget(episodes, progress)
            if (target == null) {
                _state.update {
                    it.copy(screen = Screen.DETAILS, selectedAnime = anime, episodes = episodes)
                }
                return@runRequest
            }

            val fullEpisode = async { client.episode(target.episode.id) }
            val constants = async { _state.value.constants ?: client.constants() }
            val loadedEpisode = fullEpisode.await()
            _state.update {
                it.copy(
                    screen = Screen.SOURCES,
                    selectedAnime = anime,
                    episodes = episodes,
                    episodeQuery = "",
                    selectedEpisode = loadedEpisode,
                    constants = constants.await(),
                )
            }

            val nativeSources = loadedEpisode.players.filter { it.isNative && it.qualities.isNotEmpty() }
            val source = nativeSources.firstOrNull {
                it.team.id == progress.teamId && it.translationTypeId == progress.translationTypeId
            } ?: nativeSources.firstOrNull()
            val quality = source?.qualities?.firstOrNull { it.height == progress.quality }
                ?: source?.qualities?.sortedByPlaybackPriority()?.firstOrNull()
            if (source != null && quality != null) {
                playResolved(anime, loadedEpisode, source, quality, target.positionMs)
            }
        }
    }

    fun recordPlaybackProgress(
        request: PlaybackRequest,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (positionMs < 5_000L && durationMs <= 0L) return
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceIn(0L, safeDuration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val completed = safeDuration > 0L &&
            (safePosition.toDouble() / safeDuration >= 0.95 || safeDuration - safePosition <= 30_000L)
        progressStore.save(
            PlaybackProgress(
                animeId = request.animeId,
                animeSlug = request.animeSlug,
                animeName = request.animeName,
                animeCover = request.animeCover,
                episodeId = request.episodeId,
                episodeNumber = request.episodeNumber,
                episodeName = request.episodeName,
                episodeItemNumber = request.episodeItemNumber,
                teamId = request.teamId,
                translationTypeId = request.translationTypeId,
                quality = request.quality,
                positionMs = safePosition,
                durationMs = safeDuration,
                completed = completed,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        _state.update { it.copy(continueWatching = progressStore.all()) }
    }

    fun showAuth() = _state.update { it.copy(authVisible = true, error = null) }

    fun hideAuth() = _state.update { it.copy(authVisible = false) }

    fun failAuth(message: String) =
        _state.update { it.copy(authVisible = false, error = message) }

    fun finishAuth(code: String, verifier: String) {
        runRequest {
            client.exchangeAuthorizationCode(code, verifier, AnimeLibClient.OAUTH_REDIRECT)
            _state.update { it.copy(authVisible = false, authenticated = true) }
            _state.value.selectedEpisode?.let(::selectEpisode)
        }
    }

    fun logout() {
        viewModelScope.launch {
            client.logout()
            _state.update { it.copy(authenticated = false) }
        }
    }

    fun back(): Boolean {
        if (_state.value.authVisible) {
            hideAuth()
            return true
        }
        if (_state.value.filterVisible) {
            hideFilters()
            return true
        }
        return when (_state.value.screen) {
            Screen.PLAYER -> {
                _state.update { it.copy(screen = Screen.SOURCES, playback = null) }
                true
            }
            Screen.SOURCES -> {
                _state.update { it.copy(screen = Screen.DETAILS, selectedEpisode = null) }
                true
            }
            Screen.DETAILS -> {
                _state.update {
                    it.copy(
                        screen = Screen.HOME,
                        selectedAnime = null,
                        episodes = emptyList(),
                        episodeQuery = "",
                    )
                }
                true
            }
            Screen.HOME -> false
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun loadInitialCatalog() {
        runRequest {
            val catalog = async { client.catalogPage(filters = _state.value.filters) }
            val options = async { client.catalogFilterOptions() }
            val page = catalog.await()
            _state.update {
                it.copy(
                    catalog = page.items,
                    catalogPage = page.page,
                    catalogHasNextPage = page.hasNextPage,
                    filterOptions = options.await(),
                )
            }
        }
    }

    private fun playResolved(
        anime: Anime,
        episode: Episode,
        source: PlayerSource,
        quality: VideoQuality,
        startPositionMs: Long,
    ) {
        val constants = _state.value.constants ?: return setError("Не загружены адреса видеосерверов")
        runCatching {
            buildVideoCandidates(quality, source.qualities, constants, source.videoDomain)
        }.onSuccess { candidates ->
            _state.update {
                it.copy(
                    screen = Screen.PLAYER,
                    playback = PlaybackRequest(
                        videoCandidates = candidates,
                        title = "${episode.displayName} · ${source.team.name}",
                        quality = quality.height,
                        startPositionMs = startPositionMs,
                        animeId = anime.id,
                        animeSlug = anime.slug,
                        animeName = anime.displayName,
                        animeCover = anime.coverThumbnail ?: anime.cover,
                        episodeId = episode.id,
                        episodeNumber = episode.number,
                        episodeName = episode.name,
                        episodeItemNumber = episode.itemNumber,
                        teamId = source.team.id,
                        translationTypeId = source.translationTypeId,
                        subtitles = source.subtitles.preferredForPlayback(),
                        previousEpisodeNumber = findAdjacentEpisode(
                            episodes = it.episodes,
                            currentEpisodeId = episode.id,
                            forward = false,
                        )?.number,
                        nextEpisodeNumber = findAdjacentEpisode(
                            episodes = it.episodes,
                            currentEpisodeId = episode.id,
                            forward = true,
                        )?.number,
                    ),
                )
            }
        }
            .onFailure { setError(it.message ?: "Не удалось построить URL видео") }
    }

    private fun setError(message: String) = _state.update { it.copy(error = message) }

    private fun findAdjacentEpisode(
        episodes: List<Episode>,
        currentEpisodeId: Long,
        forward: Boolean,
    ): Episode? {
        val ordered = episodes.sortedWith(
            compareBy<Episode> { it.itemNumber ?: Int.MAX_VALUE }
                .thenBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }
                .thenBy(Episode::id),
        )
        val currentIndex = ordered.indexOfFirst { it.id == currentEpisodeId }
        if (currentIndex < 0) return null
        return ordered.getOrNull(currentIndex + if (forward) 1 else -1)
    }

    private fun chooseSource(
        episode: Episode,
        teamId: Long,
        translationTypeId: Int,
    ): PlayerSource? {
        val sources = episode.players.filter { it.isNative && it.qualities.isNotEmpty() }
        return sources.firstOrNull {
            it.team.id == teamId && it.translationTypeId == translationTypeId
        } ?: sources.firstOrNull { it.team.id == teamId }
            ?: sources.firstOrNull { it.translationTypeId == translationTypeId }
            ?: sources.firstOrNull()
    }

    private fun runRequest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { block() }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(error = throwable.message ?: throwable.javaClass.simpleName)
                    }
                }
            _state.update { it.copy(loading = false) }
        }
    }
}
