package com.formulatv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PortalClient {
    private val cookieJar = object : CookieJar {
        private val cookies = ConcurrentHashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
            cookies[url.host] = (newCookies + cookies[url.host].orEmpty()).distinctBy { "${it.name}|${it.path}" }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val saved = cookies.values.flatten().filter { it.matches(url) }
            if (!::config.isInitialized) return saved
            val device = listOf(
                Cookie.Builder().name("mac").value(config.mac).hostOnlyDomain(url.host).path("/").build(),
                Cookie.Builder().name("stb_lang").value("en").hostOnlyDomain(url.host).path("/").build(),
                Cookie.Builder().name("timezone").value("UTC").hostOnlyDomain(url.host).path("/").build()
            )
            return (device + saved).distinctBy { "${it.name}|${it.path}" }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(18, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).cookieJar(cookieJar).build()
    private val discoveryHttp = http.newBuilder().callTimeout(50, TimeUnit.SECONDS).build()
    private var token = ""
    private lateinit var config: PortalConfig
    private var endpointUrl = ""
    private val itemCache = ConcurrentHashMap<String, List<Channel>>()
    private val episodeCache = ConcurrentHashMap<String, List<Channel>>()
    private val lockedCategoryIds = ConcurrentHashMap.newKeySet<String>()

    suspend fun connect(input: PortalConfig, progress: (Float, String) -> Unit = { _, _ -> }): PortalContent = withContext(Dispatchers.IO) {
        config = input.copy(baseUrl = normalizeBase(input.baseUrl), mac = normalizeMac(input.mac))
        token = ""; endpointUrl = ""; clearCache()
        connectStalker(progress)
    }

    fun clearCache() { itemCache.clear(); episodeCache.clear(); lockedCategoryIds.clear() }

    suspend fun loadItems(kind: MediaKind, categoryId: String?): List<Channel> = withContext(Dispatchers.IO) {
        val key = "$kind:${categoryId ?: "*"}"
        itemCache[key]?.let { return@withContext it }
        val items = loadStalkerItems(kind, categoryId)
        itemCache[key] = items
        items
    }

    suspend fun loadEpisodes(series: Channel): List<Channel> = withContext(Dispatchers.IO) {
        episodeCache[series.id]?.let { return@withContext it }
        val episodes = loadStalkerEpisodes(series)
        episodeCache[series.id] = episodes
        episodes
    }

    suspend fun loadGuide(channel: Channel): List<GuideProgram> = withContext(Dispatchers.IO) {
        val queries = listOf(
            "type=itv&action=get_short_epg&ch_id=${encode(channel.id)}&size=50&JsHttpRequest=1-xml",
            "type=itv&action=get_epg_info&period=${channel.catchupDays.coerceAtLeast(1) * 24}&ch_id=${encode(channel.id)}&JsHttpRequest=1-xml"
        )
        val now = System.currentTimeMillis() / 1000
        val earliest = now - channel.catchupDays.coerceAtLeast(1) * 86_400L
        for (query in queries) {
            val json = runCatching { call(query) }.getOrNull() ?: continue
            val array = contentArray(json) ?: payloadObject(json)?.optJSONArray("data") ?: continue
            val programs = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val start = firstLong(item, "start_timestamp", "start", "time", "begin")
                    val end = firstLong(item, "stop_timestamp", "stop", "time_to", "end")
                    if (start >= earliest && end > start) add(GuideProgram(
                        firstString(item, "id", "programme_id").ifBlank { "${channel.id}:$start" },
                        firstString(item, "name", "title", "descr").ifBlank { "Recorded programme" }, start, end, channel
                    ))
                }
            }.distinctBy { it.start }.sortedByDescending { it.start }
            if (programs.isNotEmpty()) return@withContext programs
        }
        emptyList()
    }

    suspend fun resolve(channel: Channel, parentalCode: String? = null): PlaybackSource = withContext(Dispatchers.IO) {
        if (channel.command.startsWith("http://") || channel.command.startsWith("https://")) {
            return@withContext PlaybackSource(channel.command, playbackHeaders())
        }
        val type = if (channel.kind == MediaKind.LIVE) "itv" else "vod"
        val candidates = if (channel.kind == MediaKind.LIVE) listOf("0") else listOf(channel.series, "1", "0").filter(String::isNotBlank).distinct()
        var lastError: Throwable? = null
        for (series in candidates) {
            val result = runCatching {
                val protection = parentalCode?.takeIf(String::isNotBlank)?.let { "&protect_code=${encode(it)}&pin=${encode(it)}&password=${encode(it)}" }.orEmpty()
                val archive = if (channel.catchupStart > 0L) "&utc=${channel.catchupStart}&lutc=${channel.catchupStart}" else ""
                val json = call("type=$type&action=create_link&cmd=${encode(channel.command)}&series=${encode(series)}$archive$protection&forced_storage=undefined&disable_ad=0&download=0&JsHttpRequest=1-xml")
                playbackUrl(json)
                    ?: error("Portal did not return a supported stream URL.")
            }
            if (result.isSuccess) return@withContext PlaybackSource(result.getOrThrow(), playbackHeaders())
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("Portal did not return a supported stream URL.")
    }

    private suspend fun connectStalker(progress: (Float, String) -> Unit): PortalContent {
        progress(.1f, "Finding Stalker portal…")
        token = payloadObject(discoverEndpointAndHandshake())?.optString("token").orEmpty()
        require(token.isNotBlank()) { "Portal did not return an authorization token." }
        progress(.3f, "Authorizing MAC…")
        val profile = runCatching { call("type=stb&action=get_profile&JsHttpRequest=1-xml") }.getOrNull()
        val profileJs = profile?.let(::payloadObject)
        val blocked = profileJs?.optString("blocked").orEmpty()
        val status = profileJs?.optString("status").orEmpty()
        require(!(blocked == "1" || status.equals("blocked", true) || status.equals("expired", true))) { "This subscription is expired or blocked by the provider." }
        progress(.52f, "Loading categories…")
        val results = coroutineScope {
            listOf(
                async { call("type=itv&action=get_genres&JsHttpRequest=1-xml") },
                async { call("type=itv&action=get_all_channels&JsHttpRequest=1-xml") },
                async { runCatching { call("type=vod&action=get_categories&JsHttpRequest=1-xml") }.getOrElse { JSONObject() } },
                async { runCatching { call("type=series&action=get_categories&JsHttpRequest=1-xml") }.getOrElse { JSONObject() } }
            ).awaitAll()
        }
        val liveCategories = parseStalkerCategories(results[0], MediaKind.LIVE)
        val vodCategories = parseStalkerCategories(results[2], MediaKind.VOD)
        val directSeriesCategories = parseStalkerCategories(results[3], MediaKind.SERIES)
        val seriesCategories = directSeriesCategories.ifEmpty {
            vodCategories.filter { category -> SERIES_CATEGORY_WORDS.any { category.title.contains(it, true) } }
                .map { it.copy(kind = MediaKind.SERIES) }
        }
        val movieCategories = if (seriesCategories.isEmpty()) vodCategories else vodCategories.filterNot { vod -> seriesCategories.any { it.id == vod.id } }
        progress(1f, "Ready")
        return PortalContent(
            liveCategories,
            parseStalkerLive(results[1]),
            movieCategories,
            seriesCategories,
            accountInfo(profileJs),
            profileJs?.let(::parentalPassword).orEmpty()
        )
    }

    private fun parseStalkerCategories(root: JSONObject, kind: MediaKind): List<Category> {
        val array = payloadArray(root) ?: return emptyList()
        return buildList { for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            item.optString("id").takeIf(String::isNotBlank)?.let { id ->
                val locked = isLocked(item)
                if (locked) lockedCategoryIds.add("$kind:$id")
                add(Category(id, item.optString("title", "Other"), kind, locked))
            }
        } }
    }

    private fun parseStalkerLive(root: JSONObject): List<Channel> {
        val array = payloadObject(root)?.optJSONArray("data") ?: payloadArray(root) ?: return emptyList()
        return buildList { for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id"); val cmd = item.optString("cmd")
            val categoryId = item.optString("tv_genre_id")
            if (id.isNotBlank() && cmd.isNotBlank()) {
                val archiveEnabled = listOf("tv_archive", "enable_tv_archive", "allow_archive", "allow_timeshift")
                    .any { key -> item.optInt(key, 0) == 1 || item.optBoolean(key, false) }
                val days = firstString(item, "tv_archive_duration", "archive_days", "catchup_days").toIntOrNull() ?: if (archiveEnabled) 7 else 0
                add(Channel(id, item.optString("name", "Channel"), cmd, categoryId, MediaKind.LIVE, item.optString("logo"),
                    locked = isLocked(item) || "${MediaKind.LIVE}:$categoryId" in lockedCategoryIds, catchupDays = days.coerceAtMost(14)))
            }
        } }
    }

    private fun loadStalkerItems(kind: MediaKind, categoryId: String?): List<Channel> {
        val requestedCategory = categoryId?.takeIf(String::isNotBlank) ?: "*"
        val collected = LinkedHashMap<String, Channel>()
        val pageSignatures = mutableSetOf<String>()
        var page = 1
        var rawLoaded = 0
        var total = Int.MAX_VALUE
        while (page <= MAX_STALKER_PAGES && rawLoaded < total) {
            val json = orderedListPage(kind, requestedCategory, page) ?: if (page == 1) {
                orderedListPage(kind, requestedCategory, 0) ?: break
            } else {
                break
            }
            val payload = payloadObject(json)
            val array = contentArray(json) ?: break
            total = payload?.optInt("total_items", Int.MAX_VALUE)?.takeIf { it > 0 } ?: total
            if (array.length() == 0) break
            val signature = buildString { for (i in 0 until array.length()) append(array.optJSONObject(i)?.let(::contentId)).append('|') }
            if (!pageSignatures.add(signature)) break
            rawLoaded += array.length()
            for (i in 0 until array.length()) {
                val channel = stalkerVodItem(array.optJSONObject(i) ?: continue, categoryId.orEmpty(), kind == MediaKind.SERIES) ?: continue
                val wanted = if (kind == MediaKind.SERIES) channel.isContainer else !channel.isContainer
                if (wanted) collected.putIfAbsent(channel.id, channel)
            }
            val pageSize = payload?.optInt("max_page_items", 0)?.takeIf { it > 0 } ?: array.length()
            if (array.length() < pageSize) break
            page++
        }
        return collected.values.toList()
    }

    private fun orderedListPage(kind: MediaKind, category: String, page: Int): JSONObject? {
        val encodedCategory = encode(category)
        val types = if (kind == MediaKind.SERIES) listOf("vod", "series") else listOf("vod")
        val categoryKeys = listOf("category", "category_id", "genre")
        for (type in types) for (key in categoryKeys) {
            val query = "type=$type&action=get_ordered_list&$key=$encodedCategory&p=$page&sortby=added&not_ended=0&fav=0&JsHttpRequest=1-xml"
            val result = runCatching { call(query) }.getOrNull() ?: continue
            if ((contentArray(result)?.length() ?: 0) > 0) return result
        }
        return null
    }

    private fun stalkerVodItem(item: JSONObject, categoryId: String, forceSeries: Boolean = false): Channel? {
        val id = contentId(item)
        if (id.isBlank()) return null
        // Classic Stalker/Ministra generates this command server-side even when
        // a compatible portal omits cmd from its ordered-list response.
        val cmd = firstString(item, "cmd", "command", "stream_url", "url").ifBlank { "/media/$id.mpg" }
        val rawSeries = listOf("series", "episodes", "episode_list").firstNotNullOfOrNull { key -> item.opt(key)?.takeUnless { it == JSONObject.NULL } }
        val seasonRefs = seasonEpisodeReferences(rawSeries)
        val refs = seasonRefs.values.flatten().distinct().ifEmpty { episodeReferences(rawSeries) }
        // Classic portals return an empty series array for ordinary movies, so
        // the array type alone must not classify every movie as a TV series.
        val isSeries = forceSeries || item.optInt("is_series", 0) == 1 || item.optBoolean("is_series", false) ||
            firstString(item, "type", "content_type", "media_type").contains("series", true) || refs.isNotEmpty()
        val serverCategory = firstString(item, "category_id", "movie_category_id", "genre_id").ifBlank { categoryId }
        val name = firstString(item, "name", "title", "movie_name").ifBlank { if (isSeries) "Series" else "Movie" }
        val poster = firstString(item, "screenshot_uri", "poster", "cover", "logo")
        val extension = firstString(item, "container_format", "extension", "format")
        return Channel(id, name, cmd, serverCategory, if (isSeries) MediaKind.SERIES else MediaKind.VOD, poster,
            refs.firstOrNull() ?: if (isSeries) "1" else "0", extension, refs, seasonRefs, isSeries,
            isLocked(item) || "${MediaKind.VOD}:$serverCategory" in lockedCategoryIds,
            summary = firstString(item, "description", "plot", "story"), rating = firstString(item, "rating_imdb", "rating"),
            year = firstString(item, "year", "released"), duration = firstString(item, "time", "duration"), genre = firstString(item, "genre"))
    }

    private fun loadStalkerEpisodes(series: Channel): List<Channel> {
        val collected = LinkedHashMap<String, Channel>()
        // The series map returned with the selected show is the safest source:
        // it is already scoped to that exact show and avoids portals which
        // silently ignore an id on a detail endpoint and return unrelated data.
        if (series.seasonEpisodeRefs.values.sumOf { it.size } > 1) {
            series.seasonEpisodeRefs.entries.forEach { (season, refs) ->
                refs.forEachIndexed { index, ref ->
                    val episode = episodeChannel(series, ref, index, season)
                    collected[episode.id] = episode
                }
            }
            return sortedEpisodes(collected.values)
        }
        val detailQueries = listOf(
                "type=vod&action=get_episodes&id=${encode(series.id)}&JsHttpRequest=1-xml",
                "type=series&action=get_episodes&id=${encode(series.id)}&JsHttpRequest=1-xml",
                "type=vod&action=get_episodes&movie_id=${encode(series.id)}&JsHttpRequest=1-xml",
                "type=vod&action=get_episodes&series_id=${encode(series.id)}&JsHttpRequest=1-xml",
                "type=vod&action=get_series&id=${encode(series.id)}&JsHttpRequest=1-xml",
                "type=series&action=get_series&id=${encode(series.id)}&JsHttpRequest=1-xml",
                "type=vod&action=get_series_info&id=${encode(series.id)}&JsHttpRequest=1-xml"
            )
        for (baseQuery in detailQueries) {
            val signatures = mutableSetOf<String>()
            var page = 1
            var total = Int.MAX_VALUE
            var loaded = 0
            while (page <= MAX_STALKER_PAGES && loaded < total) {
                val query = baseQuery.replace("&JsHttpRequest", "&p=$page&JsHttpRequest")
                val json = runCatching { call(query) }.getOrNull() ?: break
                val pageEpisodes = episodeChannels(json.opt("js"), series)
                if (pageEpisodes.isEmpty()) break
                val signature = pageEpisodes.joinToString("|") { "${it.season}:${it.series}:${it.command}" }
                if (!signatures.add(signature)) break
                pageEpisodes.filter { episodeBelongsTo(it, series) }.forEach { collected.putIfAbsent(it.id, it) }
                loaded += pageEpisodes.size
                val payload = payloadObject(json)
                total = payload?.optInt("total_items", Int.MAX_VALUE)?.takeIf { it > 0 } ?: total
                val pageSize = payload?.optInt("max_page_items", 0)?.takeIf { it > 0 } ?: pageEpisodes.size
                if (pageEpisodes.size < pageSize) break
                page++
            }
        }
        // Some portals return only the first episode from their detail action but
        // include the complete season map in the ordered-list item. Merge both.
        if (series.seasonEpisodeRefs.isNotEmpty()) {
            series.seasonEpisodeRefs.entries.forEach { (season, refs) ->
                refs.forEachIndexed { index, ref ->
                    val episode = episodeChannel(series, ref, index, season)
                    collected.putIfAbsent(episode.id, episode)
                }
            }
        } else if (collected.isEmpty()) {
            series.episodeRefs.forEachIndexed { index, ref ->
                val episode = episodeChannel(series, ref, index, if (series.episodeRefs.size > 1) "Season 1" else "")
                collected.putIfAbsent(episode.id, episode)
            }
        }
        if (collected.isNotEmpty()) return sortedEpisodes(collected.values)
        val fallbackRefs = series.episodeRefs.ifEmpty {
            listOfNotNull(series.series.takeIf { it.isNotBlank() && it != "0" })
        }
        return fallbackRefs
            .mapIndexed { index, ref -> episodeChannel(series, ref, index, if (fallbackRefs.size > 1) "Season 1" else "") }
    }

    private fun sortedEpisodes(episodes: Collection<Channel>): List<Channel> = episodes
        .distinctBy { "${it.season}|${it.series}|${it.command}" }
        .sortedWith(compareBy<Channel> { seasonSortNumber(it.season) }
            .thenBy { it.episodeNumber.takeIf { number -> number > 0 } ?: Int.MAX_VALUE }
            .thenBy { it.name })

    private fun episodeBelongsTo(episode: Channel, parent: Channel): Boolean {
        if (episode.command == parent.command) return true
        val parentId = parent.id.trim()
        if (parentId.isBlank()) return false
        return episode.id.startsWith("$parentId:") || episode.command.contains(parentId)
    }

    private fun episodeChannels(value: Any?, parent: Channel, seasonHint: String = ""): List<Channel> = when (value) {
        is JSONArray -> buildList {
            for (index in 0 until value.length()) addAll(episodeChannels(value.opt(index), parent, seasonHint))
        }
        is JSONObject -> {
            val explicitSeason = firstString(value, "season_name", "season_title", "season", "season_num", "season_number")
                .let { normalizeSeasonLabel(it.ifBlank { seasonHint }) }
            val numericSeasonKeys = value.keys().asSequence().filter { it.toIntOrNull() != null }.toList()
            val looksLikeEpisode = listOf("episode_num", "episode_number", "episode", "cmd", "command").any { value.has(it) }
            if (numericSeasonKeys.isNotEmpty() && !looksLikeEpisode) {
                numericSeasonKeys.sortedBy { it.toInt() }.flatMap { key ->
                    episodeChannels(value.opt(key), parent, "Season $key")
                }
            } else {
            val nested = listOf("episodes", "episodes_list", "episode_list", "series", "season_list", "data", "items", "seasons")
                .firstNotNullOfOrNull { key ->
                    value.opt(key)?.takeIf { candidate ->
                        candidate is JSONArray || candidate is JSONObject ||
                            candidate is String && candidate.isNotBlank() && candidate != "0"
                    }
                }
            if (nested != null && nested !== value) {
                episodeChannels(nested, parent, explicitSeason)
            } else {
                val ref = firstString(value, "episode_num", "episode_number", "episode", "number", "series", "id")
                if (ref.isBlank()) emptyList() else {
                    val episodeNumber = ref.toIntOrNull()
                        ?: Regex("\\d+").find(firstString(value, "name", "title", "episode_name"))?.value?.toIntOrNull()
                        ?: 0
                    val index = episodeNumber.minus(1).coerceAtLeast(0)
                    val command = firstString(value, "cmd", "command", "stream_url", "url").ifBlank { parent.command }
                    val name = firstString(value, "name", "title", "episode_name").ifBlank {
                        if (episodeNumber > 0) "Episode $episodeNumber" else "Episode ${index + 1}"
                    }
                    listOf(Channel("${parent.id}:${explicitSeason}:$ref", name, command, parent.categoryId, MediaKind.SERIES,
                        firstString(value, "screenshot_uri", "poster", "cover").ifBlank { parent.poster }, ref,
                        firstString(value, "container_format", "extension", "format").ifBlank { parent.extension }, locked = parent.locked || isLocked(value),
                        season = explicitSeason, episodeNumber = episodeNumber))
                }
            }
            }
        }
        is String -> {
            val clean = value.trim()
            when {
                clean.startsWith("[") -> runCatching { episodeChannels(JSONArray(clean), parent, seasonHint) }.getOrDefault(emptyList())
                clean.startsWith("{") -> runCatching { episodeChannels(JSONObject(clean), parent, seasonHint) }.getOrDefault(emptyList())
                clean.contains(',') || clean.contains(';') || clean.matches(Regex("\\d+\\s*-\\s*\\d+")) ->
                    parseEpisodeReferenceString(clean).mapIndexed { index, ref -> episodeChannel(parent, ref, index, seasonHint) }
                clean.isNotBlank() && clean != "0" -> listOf(episodeChannel(parent, clean, 0, seasonHint))
                else -> emptyList()
            }
        }
        is Number -> listOf(episodeChannel(parent, value.toString(), 0, seasonHint))
        else -> emptyList()
    }

    private fun episodeChannel(parent: Channel, ref: String, index: Int, season: String = ""): Channel = Channel(
        "${parent.id}:$season:$ref", if (ref.toIntOrNull() != null) "Episode $ref" else "Episode ${index + 1}",
        parent.command, parent.categoryId, MediaKind.SERIES, parent.poster, ref, parent.extension, locked = parent.locked,
        season = normalizeSeasonLabel(season), episodeNumber = ref.toIntOrNull() ?: index + 1
    )

    private fun parseEpisodeReferenceString(value: String): List<String> = buildList {
        value.split(',', ';').map(String::trim).filter(String::isNotBlank).forEach { token ->
            val range = Regex("^(\\d+)\\s*-\\s*(\\d+)$").matchEntire(token)
            if (range != null) {
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toInt()
                if (end >= start && end - start <= 2_000) (start..end).forEach { add(it.toString()) } else add(token)
            } else add(token)
        }
    }.distinct()

    private fun seasonEpisodeReferences(value: Any?, seasonHint: String = "Season 1"): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        fun add(season: String, refs: List<String>) {
            val clean = normalizeSeasonLabel(season.ifBlank { seasonHint })
            val target = result.getOrPut(clean) { mutableListOf() }
            refs.filter { it.isNotBlank() && it != "0" }.forEach { if (it !in target) target += it }
        }
        fun visit(node: Any?, hint: String) {
            when (node) {
                is JSONObject -> {
                    val explicit = firstString(node, "season_name", "season_title", "season", "season_num", "season_number")
                        .let { normalizeSeasonLabel(it.ifBlank { hint }) }
                    val numeric = node.keys().asSequence().filter { it.toIntOrNull() != null }.toList()
                    if (numeric.isNotEmpty()) {
                        numeric.sortedBy { it.toInt() }.forEach { key -> visit(node.opt(key), "Season $key") }
                    } else {
                        val nestedKeys = listOf("seasons", "season_list", "episodes", "episodes_list", "episode_list", "series", "data", "items")
                        val nested = nestedKeys.firstNotNullOfOrNull { key -> node.opt(key)?.takeUnless { it == null || it == JSONObject.NULL } }
                        if (nested != null) visit(nested, explicit)
                        else add(explicit, episodeReferences(node))
                    }
                }
                is JSONArray -> {
                    val objects = (0 until node.length()).map { node.opt(it) }
                    if (objects.any { it is JSONObject }) objects.forEach { visit(it, hint) }
                    else add(hint, episodeReferences(node))
                }
                else -> add(hint, episodeReferences(node))
            }
        }
        visit(value, seasonHint)
        return result.mapValues { it.value.toList() }.filterValues { it.isNotEmpty() }
    }

    private fun normalizeSeasonLabel(value: String): String {
        val clean = value.trim()
        if (clean.isBlank()) return ""
        return if (clean.startsWith("season", true)) clean.replaceFirstChar { it.uppercase() } else "Season $clean"
    }

    private fun seasonSortNumber(value: String): Int = Regex("\\d+").find(value)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    private fun episodeReferences(value: Any?): List<String> = when (value) {
        is JSONArray -> buildList { for (i in 0 until value.length()) when (val entry = value.opt(i)) {
            is JSONObject -> listOf("episode_num", "episode", "number", "series", "id").firstNotNullOfOrNull { key -> entry.opt(key)?.toString()?.takeIf(String::isNotBlank) }?.let(::add)
            is JSONArray -> addAll(episodeReferences(entry))
            null, JSONObject.NULL -> Unit
            else -> entry.toString().takeIf(String::isNotBlank)?.let(::add)
        } }
        is JSONObject -> value.keys().asSequence().flatMap { episodeReferences(value.opt(it)).asSequence() }.toList()
        is String -> when {
            value.isBlank() || value == "0" -> emptyList()
            value.trim().startsWith("[") -> runCatching { episodeReferences(JSONArray(value)) }.getOrDefault(emptyList())
            value.trim().startsWith("{") -> runCatching { episodeReferences(JSONObject(value)) }.getOrDefault(emptyList())
            value.contains(',') || value.contains(';') || value.trim().matches(Regex("\\d+\\s*-\\s*\\d+")) -> parseEpisodeReferenceString(value)
            else -> listOf(value.trim())
        }
        is Number -> listOf(value.toString())
        else -> emptyList()
    }.distinct()

    private fun episodeReferencesFromResponse(root: JSONObject, seriesId: String): List<String> {
        val payload = root.opt("js")
        val direct = when (payload) {
            is JSONObject -> episodeReferences(payload.opt("series")) + episodeReferences(payload.opt("episodes"))
            else -> emptyList()
        }
        if (direct.isNotEmpty()) return direct.distinct()
        val array = payloadArray(root) ?: payloadObject(root)?.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("id") == seriesId || array.length() == 1) {
                    addAll(episodeReferences(item.opt("series")))
                    addAll(episodeReferences(item.opt("episodes")))
                }
            }
        }.distinct()
    }

    private fun contentId(item: JSONObject): String = firstString(item, "id", "video_id", "movie_id", "series_id", "stream_id")

    private fun firstString(item: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        item.opt(key)?.takeUnless { it == JSONObject.NULL || it is JSONArray || it is JSONObject }?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
    }.orEmpty()

    private fun firstLong(item: JSONObject, vararg keys: String): Long = keys.firstNotNullOfOrNull { key ->
        item.opt(key)?.toString()?.toLongOrNull()?.let { if (it > 10_000_000_000L) it / 1000 else it }
    } ?: 0L

    private fun contentArray(root: JSONObject): JSONArray? = jsonArray(root.opt("js"))

    private fun jsonArray(value: Any?): JSONArray? = when (value) {
        is JSONArray -> value
        is JSONObject -> {
            listOf("data", "items", "movies", "videos", "episodes", "series", "results")
                .firstNotNullOfOrNull { key -> jsonArray(value.opt(key)) }
                ?: value.takeIf { it.length() > 0 && value.keys().asSequence().all { it.toIntOrNull() != null } }?.let { obj ->
                    JSONArray().also { array -> obj.keys().asSequence().forEach { array.put(obj.opt(it)) } }
                }
        }
        is String -> value.trim().let { text ->
            when {
                text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()
                text.startsWith("{") -> runCatching { jsonArray(JSONObject(text)) }.getOrNull()
                else -> null
            }
        }
        else -> null
    }

    private fun playbackUrl(root: JSONObject): String? = findPlaybackValue(root.opt("js"))

    private fun findPlaybackValue(value: Any?): String? = when (value) {
        is JSONObject -> listOf("cmd", "url", "stream_url", "link", "source")
            .firstNotNullOfOrNull { key -> findPlaybackValue(value.opt(key)) }
            ?: value.keys().asSequence().mapNotNull { findPlaybackValue(value.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findPlaybackValue(value.opt(it)) }.firstOrNull()
        is String -> Regex("(?i)(?:https?|rtsp)://[^\\s\\\"']+").find(value)?.value?.trimEnd('\\', ';')
        else -> null
    }

    private fun playbackHeaders(): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        put("X-User-Agent", "Model: MAG250; Link: WiFi"); put("Cookie", "mac=${config.mac}; stb_lang=en; timezone=UTC")
        put("Referer", "${config.baseUrl}/"); if (token.isNotBlank()) put("Authorization", "Bearer $token")
    }

    private fun call(query: String): JSONObject { check(endpointUrl.isNotBlank()) { "Portal endpoint has not been initialized." }; return request(endpointUrl, query) }

    private suspend fun discoverEndpointAndHandshake(): JSONObject = coroutineScope {
        val entered = config.baseUrl.trimEnd('/')
        val withoutPortalPath = entered.replace(Regex("(?i)/(c|server/load\\.php|stalker_portal/server/load\\.php|stalker_portal/portal\\.php|portal\\.php)$"), "")
        val initialRoots = buildList {
            add(entered); add(withoutPortalPath)
            listOf(entered, withoutPortalPath).forEach { value ->
                if (value.startsWith("https://")) add("http://${value.removePrefix("https://")}")
                if (value.startsWith("http://")) add("https://${value.removePrefix("http://")}")
            }
        }.distinct()
        val forwardedRoots = initialRoots.map { root -> async { runCatching { followForwardingPage(root) }.getOrDefault(root) } }.awaitAll()
        val roots = (forwardedRoots + initialRoots).flatMap(::portalRootVariants).distinct()
        val candidates = buildList { roots.forEach { root -> if (root.endsWith(".php", true)) add(root) else {
            add("$root/server/load.php"); add("$root/portal.php"); add("$root/stalker_portal/server/load.php"); add("$root/stalker_portal/portal.php"); add("$root/c/portal.php")
        } } }.distinct()
        val winner = CompletableDeferred<Pair<String, JSONObject>>(); val noToken = AtomicBoolean(false); val lastError = AtomicReference<Throwable?>(null)
        val jobs = candidates.map { candidate -> launch {
            val result = runCatching { requestResult(candidate, "type=stb&action=handshake&token=&JsHttpRequest=1-xml", discoveryHttp) }
            val response = result.getOrNull(); val json = response?.first
            if (json != null && payloadObject(json)?.optString("token").orEmpty().isNotBlank()) winner.complete(response.second to json) else if (json != null) noToken.set(true)
            result.exceptionOrNull()?.let(lastError::set)
        } }
        val match = withTimeoutOrNull(60_000) { winner.await() }; jobs.forEach { it.cancel() }
        if (match != null) { endpointUrl = match.first; config = config.copy(baseUrl = portalBase(match.first)); return@coroutineScope match.second }
        if (noToken.get()) throw IllegalArgumentException("Portal found, but this MAC address is not authorized by the provider.")
        throw IllegalArgumentException("Unable to find a compatible authorized portal endpoint.", lastError.get())
    }

    private fun portalBase(endpoint: String): String = endpoint.replace(Regex("(?i)/(c/portal\\.php|server/load\\.php|portal\\.php|stalker_portal/server/load\\.php|stalker_portal/portal\\.php)$"), "").trimEnd('/')
    private fun request(endpoint: String, query: String, client: OkHttpClient = http): JSONObject = requestResult(endpoint, query, client).first

    private fun requestResult(endpoint: String, query: String, client: OkHttpClient = http): Pair<JSONObject, String> {
        val url = "$endpoint?$query"
        val request = stalkerRequest(url)
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty(); require(response.isSuccessful) { "Portal returned HTTP ${response.code}." }
            parseObject(body) to response.request.url.newBuilder().query(null).build().toString().trimEnd('/')
        }
    }

    private fun stalkerRequest(url: String): Request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        .header("X-User-Agent", "Model: MAG250; Link: WiFi").header("Cookie", "mac=${config.mac}; stb_lang=en; timezone=UTC")
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.build()

    private fun followForwardingPage(start: String): String {
        var current = start
        repeat(8) {
            val request = Request.Builder().url(current).header("User-Agent", USER_AGENT).header("X-User-Agent", "Model: MAG250; Link: WiFi").build()
            discoveryHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return current
                val finalUrl = response.request.url; val body = response.body?.string().orEmpty()
                val target = forwardingTarget(body) ?: forwardingQueryTarget(finalUrl) ?: return finalUrl.toString().trimEnd('/')
                val next = (finalUrl.resolve(target) ?: return finalUrl.toString().trimEnd('/')).toString().trimEnd('/')
                if (next == current.trimEnd('/')) return next else current = next
            }
        }
        return current.trimEnd('/')
    }

    private fun forwardingTarget(body: String): String? {
        if (body.isBlank() || body.length > 1_000_000) return null
        val patterns = listOf(
            Regex("(?is)<meta[^>]+content\\s*=\\s*[\\\"'][^\\\"']*url\\s*=\\s*([^\\\"'>; ]+)"),
            Regex("(?is)(?:window\\.)?location(?:\\.href)?\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"),
            Regex("(?is)location\\.(?:replace|assign)\\(\\s*[\\\"']([^\\\"']+)[\\\"']"),
            Regex("(?is)<iframe[^>]+src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"),
            Regex("(?is)(?:redirect|portal|target|destination|url)\\s*[:=]\\s*[\\\"']((?:https?:)?\\/\\/[^\\\"']+)[\\\"']")
        )
        return patterns.firstNotNullOfOrNull { it.find(body)?.groupValues?.getOrNull(1) }?.replace("&amp;", "&")?.replace("\\/", "/")?.trim()
    }

    private fun forwardingQueryTarget(url: HttpUrl): String? {
        return listOf("url", "target", "redirect", "redirect_url", "destination", "portal").firstNotNullOfOrNull { key ->
            url.queryParameter(key)?.takeIf(String::isNotBlank)?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrDefault(it) }
        }?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun portalRootVariants(value: String): List<String> {
        val clean = value.substringBefore('#').substringBefore('?').trimEnd('/'); val parsed = clean.toHttpUrlOrNull() ?: return listOf(clean)
        val origin = parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        val withoutPage = clean.replace(Regex("(?i)/(?:index|home|redirect|forward)?\\.(?:php|html?)$"), "")
        val withoutEntry = withoutPage.replace(Regex("(?i)/(?:c|stalker_portal/c)$"), "")
        return listOf(clean, withoutPage, withoutEntry, origin).filter(String::isNotBlank).distinct()
    }

    private fun accountInfo(profile: JSONObject?): AccountInfo {
        if (profile == null) return AccountInfo()
        fun first(vararg keys: String): String = keys.firstNotNullOfOrNull { key -> profile.opt(key)?.toString()?.trim()?.takeUnless { it.isBlank() || it.equals("null", true) } }.orEmpty()
        return AccountInfo(first("name", "username", "login", "account_name", "fname"), first("status", "account_status", "blocked"), first("expire_billing_date", "expires", "expiration", "end_date"), first("tariff_plan", "tariff_plan_name", "plan", "package"), first("server", "server_name", "portal"))
    }

    private fun parentalPassword(profile: JSONObject): String =
        listOf("parent_password", "parental_password", "parent_control_password", "parent_pin")
            .firstNotNullOfOrNull { key -> profile.optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", true) } }
            .orEmpty()

    private fun parseObject(body: String): JSONObject {
        val cleaned = body.trim().removePrefix("\uFEFF"); require(cleaned.isNotBlank()) { "Portal returned an empty response." }
        val start = cleaned.indexOf('{'); val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { if (cleaned.startsWith("<")) "Portal returned a web page instead of portal data." else "Portal returned an unsupported response." }
        return try { JSONObject(cleaned.substring(start, end + 1)) } catch (_: JSONException) { throw IllegalArgumentException("Portal returned invalid JSON data.") }
    }
    private fun payloadObject(root: JSONObject): JSONObject? = when (val value = root.opt("js")) { is JSONObject -> value; is String -> runCatching { JSONObject(value) }.getOrNull(); else -> null }
    private fun payloadArray(root: JSONObject): JSONArray? = when (val value = root.opt("js")) { is JSONArray -> value; is JSONObject -> value.optJSONArray("data"); is String -> runCatching { JSONArray(value) }.getOrNull() ?: runCatching { JSONObject(value).optJSONArray("data") }.getOrNull(); else -> null }
    private fun normalizeBase(value: String): String { val raw = value.trim().trimEnd('/'); return if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw" }
    private fun normalizeMac(value: String): String { val mac = value.trim().replace(';', ':').uppercase(); require(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac)) { "Enter a valid MAC address." }; return mac }
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun isLocked(item: JSONObject): Boolean =
        listOf("lock", "censored", "locked", "is_locked", "protected", "is_protected", "adult")
            .any { key -> item.opt(key)?.toString()?.trim()?.let { it == "1" || it.equals("true", true) || it.equals("yes", true) } == true }

    private companion object {
        const val MAX_STALKER_PAGES = 250
        const val USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG200 stbapp ver: 4 rev: 1812 Mobile Safari/533.3"
        val SERIES_CATEGORY_WORDS = listOf("series", "tv show", "shows", "season", "drama", "episodes")
    }
}
