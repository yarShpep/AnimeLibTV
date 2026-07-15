# AnimeLib → лёгкое Android TV-приложение: технический анализ

Дата проверки: **15 июля 2026**, часовой пояс Europe/Moscow.

## Короткий вывод

Браузер не нужен для каталога, списка серий, выбора перевода, получения источников и воспроизведения. Frontend AnimeLib делает это через JSON API `https://hapi.hentaicdn.org/api` и собственный Media API-плеер.

Подтверждённая цепочка:

```text
GET /anime?q=...
  → GET /anime/{slug}?fields[]=teams
  → GET /episodes?anime_id={slug}
  → GET /episodes/{episodeId}
  → players[]: player + team + translation_type + video.quality[]
  → GET /constants?fields[]=videoServers&...
  → server.url + selectedQuality.href
  → Media3 либо VLC (прямо или через loopback proxy)
```

Главный результат исследования: **полный список качеств приходит в JSON эпизода**, в `players[].video.quality[]`; frontend не создаёт `_2160.mp4` и не перебирает суффиксы. Элемент качества имеет как минимум:

```json
{ "quality": 2160, "href": "<opaque relative href from API>" }
```

Frontend передаёт этот массив в свой `VideoPlayer`, отображает максимум через `maxBy(video.quality, "quality")` и строит `src` из динамических constants плюс `href`. Поэтому единственная корректная реализация 4K — выбрать `quality == 2160`, **только если такой элемент действительно вернул API**.

Авторизация — OAuth 2 Authorization Code + PKCE, а не JSON login/password. Runtime без браузера возможен после получения токенов. Для первичного входа предпочтителен внешний user-agent/Custom Tab; текущий web client использует callback на домене `v5.animelib.org`, поэтому стороннему Android-клиенту нужен согласованный redirect URI/App Link со стороны AnimeLib. Без такой регистрации полностью чистый AppAuth flow нельзя считать гарантированным. Скрытый WebView — технически возможный, но хрупкий и небезопасный fallback; login-страница содержит CSRF, cookies и reCAPTCHA.

## Что было проверено

- Живая watch-страница `v5.animelib.org` и её текущие Vite-бандлы.
- Бандлы `common-C6qJFgKw.js`, `video-player-CJukuEwU.js`, `auth-CdeApF4s.js`, `episodes-Bz_FsJho.js`.
- Реальные API-запросы к одному указанному аниме/эпизоду, без массового обхода каталога.
- `HEAD` и `Range: bytes=0-1023` для указанного MP4; полное видео не скачивалось.
- Текущий исходный код VLC for Android, commit `b849dd1128eb2bb804365f3001a99138a426f15e`.

Ограничение: встроенная авторизованная вкладка браузера в этой сессии была недоступна. Bearer/cookies пользователя не извлекались. Поэтому серверное различие «обычный аккаунт vs Premium» для 2160p нельзя доказать текущим токеном; оно помечено ниже как неподтверждённое.

## 1. Карта endpoint

База JSON API: `https://hapi.hentaicdn.org/api/`.

### Каталог и playback

| METHOD + URL | Назначение | Query/body | Заголовки | Auth | Сокращённый ответ |
|---|---|---|---|---|---|
| `GET /anime` | Поиск/каталог AnimeLib | `q`, `page`; frontend также передаёт `fields[]=rate_avg`, `rate`, `releaseDate`. Каталог поддерживает `sort_by`, `sort_type`, years, genres, tags и др. | См. общий набор ниже | Нет для публичных данных | `{"data":[{"id":12,"slug_url":"12--one-piece-anime",...}]}` |
| `GET /anime/{slug}` | Карточка аниме | Для выбора перевода: `fields[]=teams`; можно добавить `episodes_count` | Общие | Нет для публичной карточки | `{"data":{"id":12,"slug_url":"...","teams":[...]}}` |
| `GET /episodes` | Список серий | `anime_id=12--one-piece-anime` | Общие | Нет для списка | `{"data":[{"id":101248,"number":"1",...}]}` |
| `GET /episodes/{episodeId}` | **Player/source endpoint** | Для viewer — без query | Общие; Bearer критичен для состава player data | Публичный ответ есть, но внутренний AnimeLib player в проверке без Bearer отсутствовал | `{"data":{"id":101906,"players":[...]}}` |
| `GET /constants` | Базы CDN и distribution routing | `fields[]=videoServers&fields[]=animeDistributionId&fields[]=animeDistributionUrl` | Общие | Нет в проверке | `{"data":{"videoServers":[...],"animeDistributionId":[...],"animeDistributionUrl":"..."}}` |
| `POST /anime/{anime}/players/{player}/view` | Учёт просмотра | В frontend передаётся progress | Общие + Bearer | Да/сессионная функция | Не нужен для получения URL |

Для указанного One Piece `GET /episodes` вернул 1169 summary-записей одним массивом. В TV-клиенте список следует кешировать и виртуализировать, но это не требует парсинга всего каталога.

Сокращённый живой `/constants` на момент проверки:

```json
{
  "data": {
    "videoServers": [
      {"id":"main","label":"Основной","url":"https://video1.cdnlibs.org/.аs/"},
      {"id":"secondary_1","label":"Резервный 1","url":"https://video2.anilib.me/.аs/"},
      {"id":"secondary_2","label":"Резервный 2","url":"https://video3.anilib.me/.аs/"}
    ],
    "animeDistributionId": ["1", "2", "..."],
    "animeDistributionUrl": "https://video2.cdnlibs.org/"
  }
}
```

Сегмент `.аs` содержит mixed-script/необычные символы и должен считаться opaque. Не перепечатывать и не «исправлять» его вручную. На момент проверки distribution list содержал 1947 значений и не содержал anime ID `12`; это изменяемое состояние, а не константа приложения.

Сокращённый public response указанного эпизода:

```json
{
  "data": {
    "id": 101906,
    "anime_id": 12,
    "number": "664",
    "players": [
      {
        "id": 284072,
        "player": "Kodik",
        "translation_type": {"id": 1, "label": "Субтитры"},
        "team": {"id": 32417, "name": "Субтитры"},
        "src": "//kodikplayer.com/..."
      }
    ]
  }
}
```

Карточка подтверждает наличие команды `32648` (`Shachiburi`), но без Bearer эта team/player combination в response эпизода отсутствовала.

### Авторизация

| METHOD + URL | Назначение | Query/body | Заголовки/cookies | Auth | Сокращённый ответ |
|---|---|---|---|---|---|
| `GET https://auth.hentaicdn.org/auth/oauth/authorize` | Начало OAuth PKCE | `scope=`, `client_id=1`, `response_type=code`, `redirect_uri`, `state`, `code_challenge`, `code_challenge_method=S256`, `prompt=consent`, `iframe=false` | Web session cookies | Нет | 302 на `/auth/login`, если сессии нет; затем callback `?code=...&state=...` |
| `GET /auth/login` | HTML login UI | — | Устанавливает `XSRF-TOKEN`, `mangalib_session`, ddos-guard cookies | Нет | HTML form + reCAPTCHA |
| `POST /auth/login` | Вход web-сессии | Form: `_token`, `login`, `password`, `g-recaptcha-response` | CSRF + session cookies | Нет | Redirect обратно в OAuth flow |
| `POST https://hapi.hentaicdn.org/api/auth/oauth/token` | Обмен authorization code | JSON: `grant_type=authorization_code`, `client_id=1`, `redirect_uri`, `code_verifier`, `code` | Общие API headers | Нет, code одноразовый | OAuth token object: `token_type`, `expires_in`, `access_token`, `refresh_token` |
| `POST .../auth/oauth/token` | Refresh | JSON: `grant_type=refresh_token`, `client_id=1`, `refresh_token`, `scope=""` | Общие API headers | Refresh token | Новый OAuth token object |
| `GET .../auth/me` | Профиль + Premium state | — | Общие + Bearer | Да | `data.id`, `premium.enabled`, roles, preferences и т. п. |
| `DELETE .../auth/oauth/token` | Logout/revoke текущей сессии | — | Общие + Bearer | Да | Пустой/служебный success |

### Общие API headers

Текущий frontend-клиент явно добавляет:

```http
Site-Id: 5
Content-Type: application/json
Client-Time-Zone: Europe/Moscow
Authorization: Bearer <MASKED>   # если токен есть
```

Браузер также отправляет `Origin: https://v5.animelib.org` и `Referer: https://v5.animelib.org/`. В точечной curl-матрице от 15.07.2026:

- без `Referer` API edge отвечал `403`;
- с любым непустым `Referer` указанный episode endpoint отвечал `200`;
- один `Origin` без `Referer` не снимал `403`;
- `Site-Id` не был технически нужен именно этому чтению, но нужен для корректной site-specific семантики и должен всегда передаваться.

Итого для Android: отправлять **весь общий набор**, не полагаться на минимально достаточный заголовок текущей конфигурации edge.

## 2. Точная последовательность открытия серии

1. Если нужен внутренний player, обеспечить действующий Bearer. При `401` выполнить один сериализованный refresh и повторить исходный запрос один раз.
2. `GET /constants` и кешировать короткое время. Не зашивать CDN domains/paths.
3. `GET /anime?q={query}`; пользователь выбирает `slug_url`.
4. `GET /anime/{slug}?fields[]=teams`; показать команды.
5. `GET /episodes?anime_id={slug}`; пользователь выбирает episode ID.
6. `GET /episodes/{episodeId}`.
7. В `players[]` выбрать запись, где одновременно:
   - `player == "Animelib"`;
   - `team.id == selectedTeamId`;
   - `translation_type.id == selectedTranslationType`;
   - `video.quality` не пуст.
8. В `video.quality[]` выбрать первое реально существующее качество из `2160, 1440, 1080, 720, 480, 360`.
9. Получить `href` выбранного элемента. Не извлекать/не вычислять UUID, player ID или filename.
10. Выбрать base URL:
    - обычный случай: `videoServers[id == "main"].url`;
    - если сегмент между `anime/` и `/players` входит в `animeDistributionId`, использовать `animeDistributionUrl`.
11. Как frontend, выполнить строковую конкатенацию `baseUrl + href`. Не использовать `URI.resolve`, который может удалить специальную часть base path.
12. Передать итоговый URL в Media3. Для VLC сначала проверить headerless Range; если нужен Referer — открыть loopback URL прокси.

## 3. Как frontend получает качества и строит URL

В `video-player-CJukuEwU.js`:

- `episode.players` фильтруется по `team.id`, `translation_type.id`, `player`;
- для бейджа команды берётся `maxBy(player.video.quality, "quality")`;
- внутренний player получает `qualities: selectedPlayer.video.quality`;
- внешний Kodik и другие player types используют `src`/iframe и не дают тот же direct-MP4 контракт.

В `common-C6qJFgKw.js`, функция video source state:

- default settings: `quality=360`, `server="main"`;
- выбирается объект `qualities.find(x => x.quality == selectedQuality)`;
- загружаются constants `videoServers`, `animeDistributionId`, `animeDistributionUrl`;
- итог: `selectedServer.url + selectedQuality.href`, с distribution override.

Это доказывает:

- список источников приходит с API, не из DOM;
- `_360/_480/...` не генерируются клиентом;
- `2160` — обычное значение `quality`, а не отдельный endpoint;
- если API не вернул `2160`, подстановка `_2160.mp4` запрещена и технически ненадёжна.

### Результаты поиска строк в актуальных бандлах

| Строка | Результат |
|---|---|
| `cdnlibs.org` | 1 hardcoded occurrence: websocket `https://api.cdnlibs.org/api/app`; video CDN приходит динамически из `/constants` |
| `converted_videos` | отсутствует |
| `_2160.mp4` | отсутствует |
| `qualities` | присутствует в player/editor/common |
| `sources` | отсутствует как player contract |
| `player`, `episode` | много использований |
| `team_id`, `translation_type` | присутствуют в selection/router/API models |
| `Site-Id` | выставляется HTTP client |
| `Authorization` | Bearer выставляется HTTP client |
| `59520` | отсутствует |
| `e1577611-8799-4c89-bc50-82dab3088b5` | отсутствует |

UUID и `59520` являются данными, а не частью frontend-кода.

## 4. Авторизация, lifetime и 4K entitlement

### Получение и хранение токена в web frontend

Frontend генерирует PKCE `code_verifier` (128 символов), `state` (40 символов), сохраняет пару временно в localStorage `_oauth`, затем отправляет пользователя на authorize endpoint. После callback обменивает code на token.

Pinia store `auth` персистит поля `token`, `auth`, `prevUrl`, `timestamp`; persistence plugin по умолчанию использует `window.localStorage`, ключ store ID — `auth`. В token object frontend добавляет локальный `timestamp=Date.now()`.

Срок access token **не зашит константой**. Он берётся из серверного `expires_in`; frontend считает expiry как:

```text
timestamp + expires_in * 1000
```

Следовательно, корректный Android-клиент обязан хранить `expires_in`/absolute expiry, а не предполагать фиксированные 20 минут/час/год. Точное число текущего токена без раскрытия секрета можно получить как `expires_in` из token response или `exp - iat` из JWT claims. В этой проверке пользовательский JWT не читался.

### Можно ли использовать токен вне браузера

Технически да: API client передаёт обычный `Authorization: Bearer`; cookies не участвуют в последующих API XHR. Refresh также использует bearer-independent JSON grant с `client_id=1` и refresh token. Но:

- token/refresh token нельзя логировать или включать в URL;
- хранить их нужно через Android Keystore-backed encryption;
- refresh должен быть сериализован, чтобы параллельные `401` не сожгли rotating refresh token;
- это неофициальный client ID/redirect URI; сервер вправе ограничить third-party clients.

### Нужна ли авторизация для 4K

Подтверждено для указанного эпизода:

- без Bearer `GET /episodes/101906` вернул только четыре внешних Kodik player records;
- запись `player="Animelib"`, команда `32648` и `video.quality` в публичном ответе отсутствовали;
- frontend не выполняет отдельный запрос «получить 4K» и не фильтрует качества по `isPremium` на клиенте; он отображает то, что вернул сервер.

Поэтому для данного кейса авторизация нужна как минимум для получения внутреннего AnimeLib player, а значит и его 2160 source. Не доказано, фильтрует ли backend `2160` дополнительно по `premium.enabled` для разных типов аккаунтов. Это нужно проверить двумя разрешёнными response snapshots (`обычный logged-in` и `Premium`) либо одним текущим авторизованным ответом с известным статусом аккаунта. До этого формулировка «Premium обязателен для 4K» была бы предположением.

## 5. Curl-проверка указанного MP4

Проверялся только `HEAD` и `Range: bytes=0-1023`; body range уходил в `/dev/null`.

Текущий результат 15.07.2026 отличается от ранее наблюдавшегося пользователем `206`:

| Вариант | HEAD/Range результат |
|---|---|
| без заголовков | `403` ddos-guard |
| только User-Agent | `403` |
| только dummy Authorization | `403` |
| только dummy Cookie | `403` |
| с `Referer: https://v5.animelib.org/` | edge пропущен, затем `404 text/html` |
| Referer + UA + dummy auth/cookie | тот же `404` |

Это означает две вещи:

1. На текущем edge Referer практически нужен, чтобы дойти до storage lookup.
2. Конкретный сохранённый URL сейчас не воспроизводит объект: он устарел, был перемещён или его opaque href отличался. Из `404` нельзя сделать вывод о Content-Range/Content-Length реального файла.

Ранее наблюдавшийся пользователем запрос с Referer давал `206`, `video/mp4`, `Accept-Ranges: bytes`, без Cookie и Authorization. Совокупность данных указывает, что JWT/cookies относятся к получению player JSON, а не к media bytes, но это надо повторно проверять на **свежем href из текущего авторизованного response**.

Контрольная матрица для свежего URL:

```bash
curl -I '<FRESH_URL>'
curl -I -H 'User-Agent: Mozilla/5.0' '<FRESH_URL>'
curl -I -H 'Referer: https://v5.animelib.org/' '<FRESH_URL>'
curl -H 'Range: bytes=0-1023' -H 'Referer: https://v5.animelib.org/' \
  -o /dev/null -D - '<FRESH_URL>'
```

Bearer и cookies добавлять только в локальном тесте пользователя; их значения не сохранять в shell history/отчёте.

## 6. DTO, Retrofit, interceptor и quality selection

Полный reference draft находится в [AnimeLibReference.kt](./AnimeLibReference.kt). В нём есть:

- DTO для OAuth, anime, team, episode, player, video, quality, subtitles, constants;
- `AnimeLibApi`;
- interceptor со всеми требуемыми заголовками;
- selection `Animelib + team + translation_type`;
- приоритет `2160 → 1440 → 1080 → 720 → 480 → 360`;
- точная сборка URL по constants;
- Media3, VLC и loopback proxy.

Ключевой quality selector:

```kotlin
private val QUALITY_PRIORITY = listOf(2160, 1440, 1080, 720, 480, 360)

fun selectMaximumQuality(items: List<VideoQualityDto>): VideoQualityDto? {
    val byHeight = items.associateBy(VideoQualityDto::quality)
    return QUALITY_PRIORITY.firstNotNullOfOrNull(byHeight::get)
}
```

Он возвращает `null`, если ни одного поддерживаемого качества нет, и никогда не конструирует отсутствующий вариант.

## 7. Media3 / ExoPlayer

`DefaultHttpDataSource.Factory.setDefaultRequestProperties()` подходит для Referer/Origin. Media3 сам формирует Range из `DataSpec` при чтении и seek; **нельзя** добавлять статический `Range: bytes=0-` в default headers.

Минимальная схема:

```kotlin
val http = DefaultHttpDataSource.Factory()
    .setUserAgent(userAgent)
    .setDefaultRequestProperties(
        mapOf(
            "Referer" to "https://v5.animelib.org/",
            "Origin" to "https://v5.animelib.org",
            "Accept-Encoding" to "identity",
        )
    )

val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(
        DefaultMediaSourceFactory(context).setDataSourceFactory(http)
    )
    .build()

player.setMediaItem(MediaItem.fromUri(videoUrl))
player.prepare()
player.play()
```

Для 4K устройство должно поддерживать codec/profile/level конкретного файла, а HDMI/TV pipeline — нужное разрешение. Наличие 2160 URL не гарантирует аппаратное декодирование; Media3 error/fallback UX обязателен.

## 8. VLC Intent

Базовый Intent корректен:

```kotlin
Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(Uri.parse(videoUrl), "video/mp4")
    setPackage("org.videolan.vlc")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

Но обычный `ACTION_VIEW` не стандартизует произвольные HTTP headers. Проверенный VLC source при внешнем intent читает URI, mime type и extras вроде start time, from-start, subtitles, title; per-request Referer/Authorization extra не реализован. VLC может использовать глобально настроенный User-Agent, но это не решает Referer.

Следовательно:

- direct VLC допустим только после успешного headerless `Range 0-1023` теста свежего URL;
- если CDN требует Referer, direct VLC нестабилен;
- надёжный VLC path — `http://127.0.0.1:<port>/video/<random-token>` через proxy приложения.

## 9. Loopback proxy

Reference implementation в Kotlin:

- bind только `127.0.0.1`, случайный 192-bit path token;
- `GET`/`HEAD`;
- forwarding `Range` и `If-Range`;
- upstream headers `Referer`, `User-Agent`, `Accept-Encoding: identity`;
- pass-through status `200/206/416` и headers `Content-Type`, `Content-Length`, `Content-Range`, `Accept-Ranges`, `ETag`, `Last-Modified`;
- streaming через `ResponseBody.byteStream()` с небольшим buffer;
- параллельные подключения для seek;
- `close()` закрывает `ServerSocket`, отменяет active calls и останавливает workers.

Жизненный цикл:

```text
start proxy → получить loopback Uri → ACTION_VIEW VLC
→ при возврате Activity/onStop с grace period или явном Stop вызвать proxy.close()
```

Не закрывать proxy сразу в `onPause`: запуск VLC сам поставит Activity на pause. Лучше закрывать после результата/возврата пользователя, по session timeout или явной кнопке stop.

## 10. Нужен ли WebView

### Рекомендованный production-вариант

- UI каталога/серий: native Leanback/Compose for TV.
- API: Retrofit/OkHttp.
- Playback: Media3 по умолчанию; VLC как опция.
- Auth: OAuth PKCE через внешний user-agent/Custom Tab **при наличии зарегистрированного Android redirect URI**.
- WebView отсутствует.

### Реальность текущего web OAuth client

Client `1` использует `redirect_uri=https://v5.animelib.org/ru/front/auth/oauth/callback`. Стороннее приложение не владеет доменом и не может безопасно заявить verified App Link без server-side `assetlinks.json`. Поэтому нужно одно из:

1. AnimeLib регистрирует native client/redirect URI для приложения — лучший путь.
2. Пользователь проходит login в браузере, а AnimeLib предоставляет device/QR handoff — сейчас такой endpoint не найден.
3. Временный WebView перехватывает web callback и затем уничтожается — технический fallback, но нарушает native OAuth best practice, может ломаться на reCAPTCHA/social login и не должен скрыто собирать пароль.

Нельзя рекомендовать самостоятельный POST логина с сохранением пароля: это HTML form с CSRF, cookies и reCAPTCHA, а не стабильный публичный auth API.

## 11. Хрупкие места и что сломается

1. Неофициальный API: endpoint, поля и `Site-Id` могут измениться без versioning.
2. OAuth client `1` и фиксированный web redirect URI могут запретить сторонний Android flow.
3. Backend может менять auth/Premium-фильтрацию `players` или `video.quality`.
4. `/constants` может сменить CDN bases, obfuscated path или distribution routing; поэтому constants обязательны.
5. `href` opaque: нельзя нормализовать, заменять hostname/path или строить соседние качества.
6. ddos-guard может ввести новые cookies/challenge и сделать чистый OkHttp недоступным.
7. CDN может начать требовать signed URLs, короткий TTL, Referer, UA, cookies или DRM.
8. VLC Intent contract не гарантирует headers; обновление VLC может изменить extras/поведение.
9. 4K codec может быть HEVC/AV1 с неподдерживаемым TV profile, даже если MP4 URL валиден.
10. Legal/ToS changes могут запретить automated access или third-party clients.

## 12. Юридические и технические риски

- API не документирован как публичный; использование может нарушить Terms of Service.
- Прямые CDN URLs и bearer tokens нельзя публиковать, логировать в analytics/crash reports или передавать третьим лицам.
- Приложение должно показывать только контент, доступный текущему аккаунту обычным сайтом, не обходить гео/лицензионные/Premium ограничения.
- Нельзя подбирать UUID, player IDs, соседние filenames или качества.
- При появлении DRM, signed entitlement или CAPTCHA нельзя пытаться обходить их; нужна официальная интеграция.
- Публичное распространение клиента несёт больший риск блокировки, чем личный локальный клиент.

## Итог по вопросам

| Вопрос | Ответ |
|---|---|
| Можно ли отказаться от браузера? | Для всего runtime — да. Для первого login — только если AnimeLib даст native redirect/device flow; текущий web callback мешает полностью browserless auth. |
| Можно ли стабильно получать 4K? | Да, если текущий авторизованный `video.quality[]` реально содержит `2160` и URL проходит Range. Нельзя гарантировать наличие для каждого player/episode/account. |
| Нужна ли авторизация для 4K? | Для указанного внутреннего player авторизация нужна, потому что без Bearer он скрыт целиком. Отдельное Premium-требование для 2160 не доказано. |
| Можно ли VLC напрямую? | Только если свежий CDN URL работает без Referer/cookies. Текущая проверка указывает на Referer gate, а VLC Intent headers не передаёт; proxy надёжнее. |
| Лучший playback | Media3 с headers; VLC через loopback proxy как альтернативный UX. |
| Главная точка правды | `GET /episodes/{id}` + `players[].video.quality[]`, затем `/constants`; не filename guessing. |

## Первичные ссылки

- Watch page: <https://v5.animelib.org/ru/anime/12--one-piece-anime/watch?episode=101906&player=Animelib&team=32648&translation_type=2>
- Episode API: <https://hapi.hentaicdn.org/api/episodes/101906>
- Current frontend common bundle: <https://v5.animelib.org/build/assets/common-C6qJFgKw.js>
- Current player bundle: <https://v5.animelib.org/build/assets/video-player-CJukuEwU.js>
- OAuth native-app best practice: <https://datatracker.ietf.org/doc/html/rfc8252>
- Android Media3 network stacks: <https://developer.android.com/media/media3/exoplayer/network-stacks>
- Media3 default request properties: <https://developer.android.com/reference/androidx/media3/datasource/HttpDataSource.BaseFactory#setDefaultRequestProperties(java.util.Map)>
- Retrofit declarations: <https://square.github.io/retrofit/declarations/>
- OkHttp streaming recipes: <https://square.github.io/okhttp/recipes/>
- VLC external Intent entry: <https://code.videolan.org/videolan/vlc-android/-/blob/b849dd1128eb2bb804365f3001a99138a426f15e/application/vlc-android/src/org/videolan/vlc/StartActivity.kt>
- VLC external player extras: <https://code.videolan.org/videolan/vlc-android/-/blob/b849dd1128eb2bb804365f3001a99138a426f15e/application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt>
