package org.example.atvretranslation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import org.example.atvretranslation.auth.createOAuthAttempt
import org.example.atvretranslation.auth.TvCursorWebView
import org.example.atvretranslation.data.Anime
import org.example.atvretranslation.data.AnimeLibClient
import org.example.atvretranslation.data.CatalogFilterOptions
import org.example.atvretranslation.data.CatalogFilters
import org.example.atvretranslation.data.CatalogSort
import org.example.atvretranslation.data.Episode
import org.example.atvretranslation.data.FilterOption
import org.example.atvretranslation.data.PlaybackProgress
import org.example.atvretranslation.data.PlayerSource
import org.example.atvretranslation.data.SubtitleTrack
import org.example.atvretranslation.data.preferredForPlayback
import org.example.atvretranslation.data.resolveContinueTarget
import org.example.atvretranslation.data.sortedByPlaybackPriority
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Background = Color(0xFF080A0F)
private val Panel = Color(0xFF131620)
private val Purple = Color(0xFF8B5CF6)
private val SoftText = Color(0xFFB6B8C2)
private val TvColorScheme = darkColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    background = Background,
    onBackground = Color.White,
    surface = Panel,
    onSurface = Color.White,
)

@Composable
fun AnimeLibTvApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = TvColorScheme) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(modifier = Modifier.fillMaxSize().background(Background)) {
                if (state.filterVisible) {
                    CatalogFilterScreen(
                        options = state.filterOptions,
                        current = state.filters,
                        onApply = viewModel::applyFilters,
                        onClose = viewModel::hideFilters,
                    )
                } else {
                    when (state.screen) {
                        Screen.HOME -> HomeScreen(state, viewModel)
                        Screen.DETAILS -> DetailsScreen(state, viewModel)
                        Screen.SOURCES -> SourcesScreen(state, viewModel)
                        Screen.PLAYER -> state.playback?.let {
                            PlayerScreen(
                                request = it,
                                onProgress = viewModel::recordPlaybackProgress,
                                onPreviousEpisode = viewModel::playPreviousEpisode,
                                onNextEpisode = viewModel::playNextEpisode,
                                onOpenVlc = viewModel::playInVlc,
                            )
                        }
                    }
                }
                if (state.loading) LoadingBadge()
                state.error?.let { ErrorBanner(it, viewModel::clearError) }
                if (state.authVisible) {
                    OAuthWebView(
                        onCode = viewModel::finishAuth,
                        onClose = viewModel::hideAuth,
                        onError = viewModel::failAuth,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: AppUiState,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("AnimeLib", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(" TV", fontSize = 28.sp, color = Purple, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = if (state.availableUpdate != null) onInstallUpdate else onCheckUpdate,
            enabled = !state.updateBusy,
            modifier = Modifier.padding(end = 12.dp),
        ) {
            Text(
                when {
                    state.updateBusy -> "Обновление…"
                    state.availableUpdate != null -> "Установить ${state.availableUpdate.versionName}"
                    else -> "Проверить обновления"
                },
            )
        }
        Text(
            if (state.authenticated) "Аккаунт подключён" else "Гостевой режим",
            color = SoftText,
            modifier = Modifier.padding(end = 18.dp),
        )
        Button(onClick = if (state.authenticated) onLogout else onLogin) {
            Text(if (state.authenticated) "Выйти" else "Войти")
        }
    }
}

@Composable
private fun HomeScreen(state: AppUiState, viewModel: AppViewModel) {
    val initialFocus = remember { FocusRequester() }
    val catalogGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var exitArmed by remember { mutableStateOf(false) }
    val isCatalogScrolled = catalogGridState.firstVisibleItemIndex > 0 ||
        catalogGridState.firstVisibleItemScrollOffset > 0
    LaunchedEffect(state.filterVisible) {
        if (!state.filterVisible) initialFocus.requestFocus()
    }
    LaunchedEffect(state.query, state.filters) {
        catalogGridState.scrollToItem(0)
        exitArmed = false
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(3_000L)
            exitArmed = false
        }
    }
    BackHandler(enabled = !state.authVisible && (isCatalogScrolled || !exitArmed)) {
        exitArmed = true
        if (isCatalogScrolled) {
            coroutineScope.launch { catalogGridState.animateScrollToItem(0) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(
            state = state,
            onLogin = viewModel::showAuth,
            onLogout = viewModel::logout,
            onCheckUpdate = { viewModel.checkForUpdates() },
            onInstallUpdate = viewModel::installAvailableUpdate,
        )
        state.updateMessage?.let {
            Text(
                it,
                color = SoftText,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                onSearch = viewModel::loadCatalog,
                placeholder = "Название аниме",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            Button(onClick = viewModel::loadCatalog) { Text("Найти") }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = viewModel::showFilters,
                modifier = Modifier.focusRequester(initialFocus),
                colors = ButtonDefaults.colors(
                    containerColor = if (state.filters.activeCount > 0) Purple else Color(0xFF343847),
                ),
            ) {
                Text(
                    if (state.filters.activeCount > 0) {
                        "Фильтры · ${state.filters.activeCount}"
                    } else {
                        "Фильтры"
                    },
                )
            }
        }
        LazyVerticalGrid(
            state = catalogGridState,
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (state.query.isBlank() && state.continueWatching.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue-watching") {
                    Column {
                        Text(
                            "Продолжить просмотр",
                            modifier = Modifier.padding(top = 20.dp, bottom = 10.dp),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().height(126.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(state.continueWatching, key = PlaybackProgress::animeId) { progress ->
                                ContinueWatchingCard(progress) { viewModel.continueWatching(progress) }
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-title") {
                Text(
                    if (state.query.isBlank()) state.filters.sort.label else "Результаты поиска",
                    modifier = Modifier.padding(top = 18.dp, bottom = 12.dp),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            itemsIndexed(state.catalog, key = { _, anime -> anime.id }) { index, anime ->
                if (index == state.catalog.lastIndex) {
                    LaunchedEffect(state.catalog.size, index) { viewModel.loadMoreCatalog() }
                }
                AnimeCard(anime) { viewModel.selectAnime(anime) }
            }
            if (state.catalogLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-loading-more") {
                    Text(
                        "Загружаю ещё…",
                        color = SoftText,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val editorFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            editorFocus.requestFocus()
            keyboard?.show()
        }
    }
    Box(
        modifier = modifier
            .height(54.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .border(2.dp, Color(0xFF343847), RoundedCornerShape(12.dp))
            .onFocusChanged { focus ->
                if (!focus.hasFocus) editing = false
            }
            .clickable { editing = true },
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !editing,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(editorFocus)
                .focusProperties { canFocus = editing }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            singleLine = true,
            cursorBrush = SolidColor(Purple),
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search,
                showKeyboardOnFocus = false,
            ),
            keyboardActions = KeyboardActions(onSearch = {
                editing = false
                keyboard?.hide()
                onSearch()
            }),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = SoftText, fontSize = 18.sp)
                inner()
            },
        )
    }
}

@Composable
private fun ContinueWatchingCard(progress: PlaybackProgress, onClick: () -> Unit) {
    val fraction = if (progress.durationMs > 0L) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(
        onClick = onClick,
        modifier = Modifier.width(330.dp).height(116.dp),
        colors = CardDefaults.colors(containerColor = Panel),
        scale = CardDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.fillMaxSize()) {
            RemoteImage(
                progress.animeCover,
                progress.animeName,
                Modifier.width(82.dp).fillMaxHeight(),
            )
            Column(Modifier.weight(1f).padding(14.dp)) {
                Text(
                    progress.animeName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (progress.completed) {
                        "Серия ${progress.episodeNumber} просмотрена"
                    } else {
                        "Серия ${progress.episodeNumber} · ${formatTime(progress.positionMs)}"
                    },
                    color = SoftText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF343847))) {
                    Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Purple))
                }
            }
        }
    }
}

@Composable
private fun AnimeCard(anime: Anime, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        colors = CardDefaults.colors(containerColor = Panel),
    ) {
        Column(Modifier.fillMaxWidth()) {
            RemoteImage(
                url = anime.coverThumbnail ?: anime.cover,
                contentDescription = anime.displayName,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.69f),
            )
            Text(
                anime.displayName,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 9.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            anime.type?.let {
                Text(
                    it,
                    color = SoftText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                )
            } ?: Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DetailsScreen(state: AppUiState, viewModel: AppViewModel) {
    val anime = state.selectedAnime ?: return
    val progress = state.continueWatching.firstOrNull { it.animeId == anime.id }
    val continueTarget = progress?.let { resolveContinueTarget(state.episodes, it) }
    val episodeQuery = state.episodeQuery.trim()
    val visibleEpisodes = if (episodeQuery.isBlank()) {
        state.episodes
    } else {
        state.episodes.filter { episode ->
            episode.number.contains(episodeQuery, ignoreCase = true) ||
                episode.itemNumber?.toString() == episodeQuery
        }.sortedByDescending { it.number.equals(episodeQuery, ignoreCase = true) }
    }
    Row(Modifier.fillMaxSize().padding(48.dp)) {
        Column(Modifier.width(250.dp)) {
            RemoteImage(
                anime.cover ?: anime.coverThumbnail,
                anime.displayName,
                Modifier.fillMaxWidth().aspectRatio(0.69f),
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = { viewModel.back() }, modifier = Modifier.fillMaxWidth()) {
                Text("← Назад")
            }
        }
        Spacer(Modifier.width(42.dp))
        Column(Modifier.fillMaxSize()) {
            Text(anime.displayName, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            if (anime.name != anime.displayName) {
                Text(anime.name, color = SoftText, fontSize = 18.sp)
            }
            Text(
                "${anime.type ?: "Аниме"} · ${state.episodes.size} серий",
                color = SoftText,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )
            if (progress != null && continueTarget != null) {
                Button(
                    onClick = { viewModel.continueWatching(progress) },
                    colors = ButtonDefaults.colors(containerColor = Purple),
                ) {
                    val isNext = continueTarget.episode.id != progress.episodeId
                    Text(
                        if (isNext) {
                            "▶ Следующая серия ${continueTarget.episode.number}"
                        } else {
                            "▶ Продолжить серию ${progress.episodeNumber} · ${formatTime(progress.positionMs)}"
                        },
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    value = state.episodeQuery,
                    onValueChange = viewModel::setEpisodeQuery,
                    onSearch = {},
                    placeholder = "Номер серии",
                    modifier = Modifier.weight(1f),
                )
                if (state.episodeQuery.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { viewModel.setEpisodeQuery("") }) { Text("Очистить") }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(210.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                items(visibleEpisodes, key = Episode::id) { episode ->
                    Card(
                        onClick = { viewModel.selectEpisode(episode) },
                        colors = CardDefaults.colors(containerColor = Panel),
                        scale = CardDefaults.scale(focusedScale = 1.04f),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("Серия ${episode.number}", fontWeight = FontWeight.SemiBold)
                            if (episode.name.isNotBlank()) {
                                Text(
                                    episode.name,
                                    color = SoftText,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogFilterScreen(
    options: CatalogFilterOptions,
    current: CatalogFilters,
    onApply: (CatalogFilters) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    val firstFilterFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFilterFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Фильтры каталога", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(28.dp))
            Button(
                onClick = { onApply(draft) },
                colors = ButtonDefaults.colors(containerColor = Purple),
            ) { Text("Показать") }
            Text(
                "Выбрано: ${draft.activeCount}",
                color = SoftText,
                modifier = Modifier.padding(start = 18.dp),
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = { draft = CatalogFilters() }) { Text("Сбросить") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onClose) { Text("Закрыть") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).padding(vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                FilterSectionTitle("Сортировка")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CatalogSort.entries.forEachIndexed { index, sort ->
                        FilterChoice(
                            label = sort.label,
                            selected = draft.sort == sort,
                            modifier = if (index == 0) Modifier.focusRequester(firstFilterFocus) else Modifier,
                        ) {
                            draft = draft.copy(sort = sort)
                        }
                    }
                }
            }
            item {
                FilterSectionTitle("Годы выпуска")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val presets = listOf(
                        Triple("Все годы", null, null),
                        Triple("2020+", 2020, null),
                        Triple("2010-е", 2010, 2019),
                        Triple("2000-е", 2000, 2009),
                        Triple("До 2000", null, 1999),
                    )
                    presets.forEach { (label, min, max) ->
                        FilterChoice(label, draft.yearMin == min && draft.yearMax == max) {
                            draft = draft.copy(yearMin = min, yearMax = max)
                        }
                    }
                }
            }
            item {
                FilterSectionTitle("Тип")
                FilterOptionsFlow(options.types, draft.typeIds) { id ->
                    draft = draft.copy(typeIds = draft.typeIds.toggled(id))
                }
            }
            item {
                FilterSectionTitle("Статус")
                FilterOptionsFlow(options.statuses, draft.statusIds) { id ->
                    draft = draft.copy(statusIds = draft.statusIds.toggled(id))
                }
            }
            item {
                FilterSectionTitle("Жанры · можно выбрать несколько")
                FilterOptionsFlow(options.genres, draft.genreIds) { id ->
                    draft = draft.copy(genreIds = draft.genreIds.toggled(id))
                }
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(text: String) {
    Text(
        text,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterOptionsFlow(
    options: List<FilterOption>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { option ->
            FilterChoice(option.label, option.id in selectedIds) { onToggle(option.id) }
        }
    }
}

@Composable
private fun FilterChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Purple else Color(0xFF2A2E3A),
        ),
    ) { Text(label) }
}

private fun Set<Long>.toggled(id: Long): Set<Long> =
    if (id in this) this - id else this + id

@Composable
private fun SourcesScreen(state: AppUiState, viewModel: AppViewModel) {
    val episode = state.selectedEpisode ?: return
    val sources = episode.players.filter { it.isNative && it.qualities.isNotEmpty() }
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.back() }) { Text("← К сериям") }
            Spacer(Modifier.width(22.dp))
            Column {
                Text(episode.displayName, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Перевод и качество", color = SoftText)
            }
        }
        Spacer(Modifier.height(28.dp))
        if (!state.loading && sources.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(28.dp),
            ) {
                Text("Внутренний источник AnimeLib недоступен", fontSize = 22.sp)
                Text(
                    if (state.authenticated) {
                        "Для этой серии API не вернул прямые источники. Внешние iframe-плееры намеренно не открываются."
                    } else {
                        "Каталог доступен без аккаунта, но прямые MP4 и полные качества обычно приходят после входа."
                    },
                    color = SoftText,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
                if (!state.authenticated) {
                    Button(onClick = viewModel::showAuth) { Text("Войти через AnimeLib") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(sources, key = PlayerSource::id) { source ->
                    SourceRow(source, viewModel)
                }
            }
        }
    }
}

@Composable
private fun SourceRow(source: PlayerSource, viewModel: AppViewModel) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(20.dp),
    ) {
        Text(source.label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        val subtitles = source.subtitles.preferredForPlayback()
        Text(
            if (subtitles.isEmpty()) {
                "Субтитры не предоставлены этим источником"
            } else {
                "Субтитры: " + subtitles.joinToString { subtitle ->
                    "${subtitle.displayName} (${subtitle.format.uppercase()})"
                }
            },
            color = SoftText,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            source.qualities.sortedByPlaybackPriority().forEach { quality ->
                Button(
                    onClick = { viewModel.play(source, quality) },
                    colors = ButtonDefaults.colors(
                        containerColor = if (quality.height >= 2160) Purple else Color(0xFF2A2E3A),
                    ),
                ) {
                    Text(if (quality.height == 2160) "4K" else "${quality.height}p")
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(
    request: PlaybackRequest,
    onProgress: (request: PlaybackRequest, positionMs: Long, durationMs: Long) -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onOpenVlc: () -> Unit,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val candidateUrls = remember(request.url, request.fallbackUrls) {
        (listOf(request.url) + request.fallbackUrls).distinct()
    }
    var candidateIndex by remember(candidateUrls) { mutableIntStateOf(0) }
    var playerMessage by remember(candidateUrls) { mutableStateOf<String?>(null) }
    val mediaItemForUrl: (String) -> MediaItem = remember(request.subtitles) {
        { url ->
            MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.VIDEO_MP4)
                .setSubtitleConfigurations(
                    request.subtitles.mapIndexed { index, subtitle ->
                        subtitle.toMedia3Configuration(isDefault = index == 0)
                    },
                )
                .build()
        }
    }
    DisposableEffect(hostView, candidateUrls) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }
    val player = remember(candidateUrls, request.startPositionMs, request.subtitles) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(AnimeLibClient.USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "${AnimeLibClient.WEB_ORIGIN}/",
                    "Origin" to AnimeLibClient.WEB_ORIGIN,
                    "Accept-Encoding" to "identity",
                ),
            )
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory))
            .build()
            .apply {
                setMediaItem(mediaItemForUrl(candidateUrls.first()), request.startPositionMs)
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setSelectTextByDefault(request.subtitles.isNotEmpty())
                    .setSelectUndeterminedTextLanguage(true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, request.subtitles.isEmpty())
                    .build()
                prepare()
                playWhenReady = true
            }
    }
    val switchToNextCandidate: () -> Boolean = {
        val nextIndex = candidateIndex + 1
        if (nextIndex >= candidateUrls.size) {
            false
        } else {
            val resumePositionMs = player.currentPosition.coerceAtLeast(request.startPositionMs)
            candidateIndex = nextIndex
            playerMessage = "Переключаю видеосервер…"
            player.setMediaItem(mediaItemForUrl(candidateUrls[nextIndex]), resumePositionMs)
            player.prepare()
            player.playWhenReady = true
            true
        }
    }
    LaunchedEffect(player, candidateIndex) {
        val attemptedIndex = candidateIndex
        delay(CDN_STARTUP_TIMEOUT_MS)
        if (
            candidateIndex == attemptedIndex &&
            player.playbackState == Player.STATE_BUFFERING
        ) {
            if (!switchToNextCandidate()) {
                playerMessage = "Не удалось загрузить видео ни с одного сервера"
            }
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            delay(5_000L)
            onProgress(request, player.currentPosition, player.safeDuration())
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playerMessage = null
                if (playbackState == Player.STATE_ENDED) {
                    onProgress(request, player.currentPosition, player.safeDuration())
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.isCdnFallbackEligible() && switchToNextCandidate()) return
                playerMessage = if (error.isCdnFallbackEligible()) {
                    "Не удалось загрузить видео ни с одного сервера"
                } else {
                    "Ошибка воспроизведения: ${error.errorCodeName}"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            onProgress(request, player.currentPosition, player.safeDuration())
            player.removeListener(listener)
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                TvPlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = true
                    controllerShowTimeoutMs = 4_000
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            if (visibility != View.VISIBLE) post { requestFocus() }
                        },
                    )
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setShowSubtitleButton(request.subtitles.isNotEmpty())
                    this.player = player
                    configureTvPlayerActions(
                        request = request,
                        onPreviousEpisode = onPreviousEpisode,
                        onNextEpisode = onNextEpisode,
                        onOpenVlc = onOpenVlc,
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                    post {
                        requestFocus()
                        showController()
                    }
                }
            },
            update = {
                it.player = player
                it.setShowSubtitleButton(request.subtitles.isNotEmpty())
                it.configureTvPlayerActions(
                    request = request,
                    onPreviousEpisode = onPreviousEpisode,
                    onNextEpisode = onNextEpisode,
                    onOpenVlc = onOpenVlc,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
        playerMessage?.let { message ->
            Text(
                text = message,
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC11131A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

private const val CDN_STARTUP_TIMEOUT_MS = 25_000L

private fun PlaybackException.isCdnFallbackEligible(): Boolean = errorCode in 2000..2999

@OptIn(UnstableApi::class)
private class TvPlayerView(context: Context) : PlayerView(context) {
    private var consumeKeyUp = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isTvNavigationKey()) {
            if (event.action == KeyEvent.ACTION_DOWN && !isControllerFullyVisible) {
                showController()
                consumeKeyUp = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && consumeKeyUp) {
                consumeKeyUp = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

private fun KeyEvent.isTvNavigationKey(): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    -> true
    else -> false
}

private fun PlayerView.configureTvPlayerActions(
    request: PlaybackRequest,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onOpenVlc: () -> Unit,
) {
    checkNotNull(findViewById<TextView>(org.example.atvretranslation.R.id.tv_episode_number)).apply {
        text = "Серия ${request.episodeNumber}"
        contentDescription = text
    }
    checkNotNull(findViewById<View>(org.example.atvretranslation.R.id.tv_previous_episode)).apply {
        val available = request.previousEpisodeNumber != null
        isEnabled = available
        alpha = if (available) 1f else 0.3f
        contentDescription = request.previousEpisodeNumber?.let { "Предыдущая серия: $it" }
            ?: "Предыдущей серии нет"
        setOnClickListener { onPreviousEpisode() }
    }
    checkNotNull(findViewById<View>(org.example.atvretranslation.R.id.tv_next_episode)).apply {
        val available = request.nextEpisodeNumber != null
        isEnabled = available
        alpha = if (available) 1f else 0.3f
        contentDescription = request.nextEpisodeNumber?.let { "Следующая серия: $it" }
            ?: "Следующей серии нет"
        setOnClickListener { onNextEpisode() }
    }
    checkNotNull(findViewById<View>(org.example.atvretranslation.R.id.tv_open_vlc)).apply {
        setOnClickListener { onOpenVlc() }
    }
}

private fun SubtitleTrack.toMedia3Configuration(isDefault: Boolean): MediaItem.SubtitleConfiguration {
    val mimeType = when (format.lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
    return MediaItem.SubtitleConfiguration.Builder(Uri.parse(src))
        .setMimeType(mimeType)
        .setLabel(displayName)
        .apply {
            language?.takeIf(String::isNotBlank)?.let(::setLanguage)
            if (isDefault) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        }
        .build()
}

private fun Player.safeDuration(): Long = duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthWebView(
    onCode: (String, String) -> Unit,
    onClose: () -> Unit,
    onError: (String) -> Unit,
) {
    val attempt = remember { createOAuthAttempt() }
    Box(Modifier.fillMaxSize().background(Background)) {
        AndroidView(
            factory = { context ->
                CookieManager.getInstance().setAcceptCookie(true)
                TvCursorWebView(context).apply {
                    webView.setBackgroundColor(android.graphics.Color.rgb(8, 10, 15))
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView.webChromeClient = WebChromeClient()
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = intercept(request.url)

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                            intercept(Uri.parse(url))

                        private fun intercept(uri: Uri): Boolean {
                            val isCallback = uri.scheme == "https" &&
                                uri.host == "v5.animelib.org" &&
                                uri.path == "/ru/front/auth/oauth/callback"
                            if (!isCallback) return false
                            val state = uri.getQueryParameter("state")
                            val code = uri.getQueryParameter("code")
                            when {
                                state != attempt.state -> onError("OAuth state не совпал")
                                code.isNullOrBlank() -> onError(uri.getQueryParameter("error") ?: "Нет OAuth code")
                                else -> onCode(code, attempt.verifier)
                            }
                            return true
                        }
                    }
                    webView.loadUrl(attempt.authorizationUrl)
                    post { requestWebViewFocus() }
                }
            },
            modifier = Modifier.fillMaxSize().padding(top = 74.dp),
            onRelease = { container ->
                container.webView.stopLoading()
                container.webView.loadUrl("about:blank")
                container.webView.clearHistory()
                container.webView.removeAllViews()
                container.webView.destroy()
                container.removeAllViews()
            },
        )
        Row(
            Modifier.fillMaxWidth().height(74.dp).background(Panel).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Вход AnimeLib · стрелки двигают курсор · OK нажимает")
            Spacer(Modifier.weight(1f))
            Button(onClick = onClose) { Text("Закрыть") }
        }
    }
}

@Composable
private fun LoadingBadge() {
    Box(
        Modifier.padding(28.dp).background(Purple, RoundedCornerShape(50)).padding(16.dp)
            .size(width = 150.dp, height = 24.dp),
        contentAlignment = Alignment.Center,
    ) { Text("Загрузка…") }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFD34D5E)).padding(horizontal = 36.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, modifier = Modifier.weight(1f), maxLines = 2)
        Button(onClick = onDismiss) { Text("OK") }
    }
}
