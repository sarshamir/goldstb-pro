package com.formulatv.player

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class SourceType { XTREAM, STALKER }
data class SourceConfig(val id: String, val name: String, val type: SourceType,
    val url: String, val username: String = "", val password: String = "", val mac: String = "")

class CatalogClient {
    private var source: SourceConfig? = null
    private var stalker = PortalClient()
    private var xtream = XtreamClient()
    suspend fun connect(config: SourceConfig): PortalContent {
        source = config
        stalker = PortalClient(); xtream = XtreamClient()
        return if (config.type == SourceType.STALKER) stalker.connect(PortalConfig(config.url, config.mac)) else xtream.connect(config)
    }
    suspend fun items(kind: MediaKind, category: String?) =
        if (source?.type == SourceType.STALKER) stalker.loadItems(kind, category) else xtream.items(kind, category)
    suspend fun episodes(item: Channel) =
        if (source?.type == SourceType.STALKER) stalker.loadEpisodes(item) else xtream.episodes(item)
    suspend fun guide(item: Channel) =
        if (source?.type == SourceType.STALKER) stalker.loadGuide(item) else xtream.guide(item)
    suspend fun resolve(item: Channel, pin: String? = null) =
        if (source?.type == SourceType.STALKER) stalker.resolve(item, pin) else xtream.resolve(item)
    suspend fun details(item: Channel): Channel = if (source?.type == SourceType.STALKER) item else xtream.details(item)
    fun clearCache() { stalker.clearCache(); xtream.clearCache() }
}

class XtreamClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(18, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build()) {
    private lateinit var config: SourceConfig
    private var base = ""
    private val cache = mutableMapOf<String, List<Channel>>()
    suspend fun connect(input: SourceConfig): PortalContent = withContext(Dispatchers.IO) {
        config = input
        val raw = input.url.trim().let { if (it.contains("://")) it else "https://$it" }
        val parsed = raw.toHttpUrlOrNull() ?: error("Enter a valid server URL.")
        base = parsed.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
            .replace(Regex("(?i)/(player_api|xmltv|panel_api|get)\\.php$"), "")
        require(input.username.isNotBlank() && input.password.isNotBlank()) { "Enter your username and password." }
        val info = apiObject("")
        val user = info.optJSONObject("user_info") ?: error("The server did not return account information.")
        require(user.optInt("auth", 0) == 1) { "The server rejected this username or password." }
        require(user.optString("status", "Active").equals("Active", true)) { "This subscription is expired or blocked." }
        coroutineScope {
            val liveCats = async { categories("get_live_categories", MediaKind.LIVE) }
            val vodCats = async { optionalCategories("get_vod_categories", MediaKind.VOD) }
            val seriesCats = async { optionalCategories("get_series_categories", MediaKind.SERIES) }
            val live = async { streams(apiArray("get_live_streams"), MediaKind.LIVE) }
            PortalContent(liveCats.await(), live.await(), vodCats.await(), seriesCats.await(),
                AccountInfo(user.optString("username"), user.optString("status"),
                    user.optString("exp_date").toLongOrNull()?.let { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it * 1000)) }.orEmpty(),
                    "Xtream Codes", parsed.host))
        }
    }
    private fun categories(action: String, kind: MediaKind): List<Category> {
        val data = apiArray(action)
        return (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.let {
            Category(it.optString("category_id"), it.optString("category_name", "Other"), kind,
                isAdult(it.optString("category_name")))
        } }
    }
    private fun optionalCategories(action: String, kind: MediaKind) = runCatching { categories(action, kind) }.getOrDefault(emptyList())
    suspend fun items(kind: MediaKind, category: String?): List<Channel> = withContext(Dispatchers.IO) {
        val key = "$kind:$category"
        cache[key] ?: streams(apiArray(if (kind == MediaKind.SERIES) "get_series" else "get_vod_streams",
            category?.let { mapOf("category_id" to it) } ?: emptyMap()), kind).also { cache[key] = it }
    }
    private fun streams(array: JSONArray, kind: MediaKind): List<Channel> = (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i)?.let { item ->
            val id = item.optString(if (kind == MediaKind.SERIES) "series_id" else "stream_id")
            if (!id.matches(Regex("[0-9]+"))) return@let null
            val ext = item.optString("container_extension").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
                ?: if (kind == MediaKind.LIVE) "ts" else "mp4"
            val name = item.optString("name", "Untitled")
            Channel(id, name, if (kind == MediaKind.SERIES) "" else streamUrl(if (kind == MediaKind.LIVE) "live" else "movie", id, ext),
                item.optString("category_id"), kind, item.optString(if (kind == MediaKind.SERIES) "cover" else "stream_icon"),
                extension = ext, isContainer = kind == MediaKind.SERIES,
                locked = item.optInt("is_adult", 0) == 1 || isAdult(name),
                catchupDays = if (item.optInt("tv_archive", 0) == 1) item.optInt("tv_archive_duration", 1) else 0,
                summary = item.optString("plot"), rating = item.optString("rating"), year = item.optString("year"))
        }
    }
    suspend fun episodes(series: Channel): List<Channel> = withContext(Dispatchers.IO) {
        val root = apiObject("get_series_info", mapOf("series_id" to series.id))
        val seasons = root.optJSONObject("episodes") ?: return@withContext emptyList()
        seasons.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.flatMap { season ->
            val array = seasons.optJSONArray(season) ?: JSONArray()
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { e ->
                val id = e.optString("id")
                if (!id.matches(Regex("[0-9]+"))) return@let null
                val ext = e.optString("container_extension", "mp4").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "mp4"
                Channel(id, e.optString("title", "Episode ${index + 1}"), streamUrl("series", id, ext), series.categoryId,
                    MediaKind.SERIES, series.poster, extension = ext, season = "Season $season",
                    episodeNumber = e.optInt("episode_num", index + 1), locked = series.locked)
            } }
        }
    }
    suspend fun guide(channel: Channel): List<GuideProgram> = withContext(Dispatchers.IO) {
        val root = apiObject(if (channel.catchupDays > 0) "get_simple_data_table" else "get_short_epg",
            mapOf("stream_id" to channel.id, "limit" to "30"))
        val array = root.optJSONArray("epg_listings") ?: JSONArray()
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { e ->
            val start = e.optString("start_timestamp").toLongOrNull() ?: parseTime(e.optString("start"))
            val end = e.optString("stop_timestamp").toLongOrNull() ?: parseTime(e.optString("end"))
            if (start <= 0 || end <= start) return@let null
            GuideProgram(e.optString("id", "$start"), decodeTitle(e.optString("title")), start, end, channel)
        } }.sortedBy { it.start }
    }
    suspend fun details(item: Channel): Channel = withContext(Dispatchers.IO) {
        if (item.kind == MediaKind.LIVE) return@withContext item
        val root = apiObject(if (item.isContainer) "get_series_info" else "get_vod_info",
            mapOf((if (item.isContainer) "series_id" else "vod_id") to item.id))
        val info = root.optJSONObject("info") ?: return@withContext item
        item.copy(summary = info.optString("plot", item.summary), rating = info.optString("rating", item.rating),
            year = info.optString("releasedate", info.optString("releaseDate", item.year)).take(10),
            duration = info.optString("duration", info.optString("episode_run_time", item.duration)),
            genre = info.optString("genre", item.genre), poster = info.optString("cover_big", info.optString("cover", item.poster)))
    }
    suspend fun resolve(item: Channel): PlaybackSource = withContext(Dispatchers.IO) {
        val url = if (item.catchupStart > 0) {
            val stamp = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(item.catchupStart * 1000))
            val length = ((item.catchupEnd - item.catchupStart) / 60).coerceAtLeast(1)
            "$base/timeshift/${encode(config.username)}/${encode(config.password)}/$length/$stamp/${item.id.substringBefore(":archive")}.ts"
        } else item.command
        require(url.startsWith("http://") || url.startsWith("https://")) { "The server did not return a playable stream." }
        PlaybackSource(url, mapOf("User-Agent" to "FormulaTV/0.2"))
    }
    private fun streamUrl(type: String, id: String, ext: String) = "$base/$type/${encode(config.username)}/${encode(config.password)}/$id.$ext"
    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun body(action: String, extra: Map<String, String>): String {
        val url = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", config.username).addQueryParameter("password", config.password)
        if (action.isNotBlank()) url.addQueryParameter("action", action)
        extra.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return http.newCall(Request.Builder().url(url.build()).header("User-Agent", "FormulaTV/0.2").build()).execute().use { response ->
            require(response.isSuccessful) { "Server returned HTTP ${response.code}." }
            response.body?.string()?.takeIf { it.isNotBlank() } ?: error("The server returned an empty response.")
        }
    }
    private fun apiArray(action: String, extra: Map<String, String> = emptyMap()) = try { JSONArray(body(action, extra)) }
        catch (e: org.json.JSONException) { throw IllegalArgumentException("The server did not return a valid content list.") }
    private fun apiObject(action: String, extra: Map<String, String> = emptyMap()) = try { JSONObject(body(action, extra)) }
        catch (e: org.json.JSONException) { throw IllegalArgumentException("The server returned an invalid account response.") }
    private fun decodeTitle(value: String) = runCatching { String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(value)
    private fun parseTime(value: String) = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(value)!!.time / 1000 }.getOrDefault(0)
    private fun isAdult(name: String) = Regex("(?i)\\b(adult|xxx|18\\+)\\b").containsMatchIn(name)
    fun clearCache() { cache.clear() }
}
