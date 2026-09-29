package com.formulatv.player

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID

enum class Tab { HOME, LIVE, MOVIES, SERIES, FAVORITES, SETTINGS }
data class PlayRequest(val item: Channel, val source: PlaybackSource, val sequence: Int)
data class FormulaState(
    val sources: List<SourceConfig> = emptyList(), val active: SourceConfig? = null,
    val connected: Boolean = false, val busy: Boolean = false, val loading: String = "",
    val error: String? = null, val tab: Tab = Tab.HOME, val content: PortalContent? = null,
    val category: String? = null, val query: String = "", val items: List<Channel> = emptyList(),
    val episodes: List<Channel>? = null, val seriesTitle: String = "", val selected: Channel? = null,
    val playback: PlayRequest? = null, val resolving: Boolean = false, val guide: List<GuideProgram>? = null,
    val favoriteItems: List<Channel> = emptyList(), val history: List<Channel> = emptyList(),
    val favorites: Set<String> = emptySet(), val hidden: Set<String> = emptySet(), val pinned: Set<String> = emptySet(),
    val details: Channel? = null, val detailsLoading: Boolean = false,
    val lockedItem: Channel? = null, val pinError: String? = null, val fullscreen: Boolean = false
)
class FormulaViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("formula_preferences", 0)
    private val store = SourceStore(app)
    private var backend = CatalogClient()
    private var epoch = 0
    private var itemJob: Job? = null
    private var playJob: Job? = null
    private var guideJob: Job? = null
    private var sequence = 0
    private var unlocked = false
    val state = mutableStateOf(FormulaState())
    init {
        val sources = runCatching { store.load() }.getOrElse {
            state.value = state.value.copy(error = "Saved sources could not be unlocked. Add your source again.")
            emptyList()
        }
        state.value = state.value.copy(sources = sources)
        val id = prefs.getString("active_source", "")
        sources.firstOrNull { it.id == id }?.let { connect(it) }
    }
    fun connect(source: SourceConfig) {
        itemJob?.cancel(); playJob?.cancel(); guideJob?.cancel()
        val currentEpoch = ++epoch
        val client = CatalogClient(); backend = client; unlocked = false
        prefs.edit().putString("active_source", source.id).apply()
        state.value = FormulaState(sources = state.value.sources, active = source, busy = true,
            loading = "Connecting to ${source.name}…", favorites = storedSet("favorites", source.id),
            hidden = storedSet("hidden", source.id), pinned = storedSet("pinned", source.id),
            favoriteItems = store.loadItems("favorite_items:${source.id}"), history = store.loadItems("history:${source.id}"))
        viewModelScope.launch {
            try {
                check(online()) { "No internet connection. Check your Wi-Fi or mobile data." }
                val content = client.connect(source)
                if (currentEpoch == epoch) state.value = state.value.copy(connected = true, busy = false, content = content, error = null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentEpoch == epoch) state.value = state.value.copy(busy = false, error = safeMessage(error))
            }
        }
    }
    fun saveSource(source: SourceConfig): Boolean {
        val url = source.url.trim()
        if (url.isBlank()) { state.value = state.value.copy(error = "Enter the source URL."); return false }
        if (source.type == SourceType.STALKER && !Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(source.mac.trim())) {
            state.value = state.value.copy(error = "Enter a valid MAC address, for example 00:1A:79:12:34:56."); return false
        }
        if (source.type == SourceType.XTREAM && (source.username.isBlank() || source.password.isBlank())) {
            state.value = state.value.copy(error = "Enter the Xtream username and password."); return false
        }
        val clean = source.copy(url = url, mac = source.mac.trim().uppercase(), name = source.name.trim().ifBlank { source.type.name.lowercase().replaceFirstChar { it.uppercase() } })
        val sources = state.value.sources.filterNot { it.id == clean.id } + clean
        try { store.save(sources) } catch (_: Exception) {
            state.value = state.value.copy(error = "Could not save the source securely. Please try again."); return false
        }
        state.value = state.value.copy(sources = sources); connect(clean); return true
    }
    fun deleteSource(source: SourceConfig) {
        val sources = state.value.sources.filterNot { it.id == source.id }
        try { store.save(sources) } catch (_: Exception) {
            state.value = state.value.copy(error = "Could not remove this source. Please try again."); return
        }
        if (state.value.active?.id == source.id) {
            ++epoch; itemJob?.cancel(); playJob?.cancel(); guideJob?.cancel()
            prefs.edit().remove("active_source").apply()
            state.value = FormulaState(sources = sources)
        } else state.value = state.value.copy(sources = sources)
    }
    fun select(tab: Tab) {
        itemJob?.cancel()
        state.value = state.value.copy(tab = tab, category = null, query = "", episodes = null, items = emptyList(), busy = false, error = null)
        if (tab == Tab.MOVIES || tab == Tab.SERIES) category(null)
    }
    fun kind() = when (state.value.tab) { Tab.MOVIES -> MediaKind.VOD; Tab.SERIES -> MediaKind.SERIES; else -> MediaKind.LIVE }
    fun categories(): List<Category> {
        val content = state.value.content ?: return emptyList()
        val list = when (kind()) { MediaKind.LIVE -> content.liveCategories; MediaKind.VOD -> content.vodCategories; MediaKind.SERIES -> content.seriesCategories }
        return list.filterNot { categoryKey(it) in state.value.hidden }
            .sortedByDescending { categoryKey(it) in state.value.pinned }
    }
    fun category(id: String?) {
        itemJob?.cancel()
        state.value = state.value.copy(category = id, episodes = null, query = "", error = null)
        if (kind() == MediaKind.LIVE) return
        val currentEpoch = epoch; val kind = kind()
        state.value = state.value.copy(busy = true, loading = if (kind == MediaKind.SERIES) "Loading series…" else "Loading movies…", items = emptyList())
        itemJob = viewModelScope.launch {
            try {
                val items = backend.items(kind, id)
                if (currentEpoch == epoch) state.value = state.value.copy(items = items, busy = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentEpoch == epoch) state.value = state.value.copy(busy = false, error = safeMessage(error))
            }
        }
    }
    fun visibleItems(): List<Channel> {
        val s = state.value
        val list = s.episodes ?: when (s.tab) {
            Tab.LIVE -> s.content?.liveChannels.orEmpty()
            Tab.FAVORITES -> (s.content?.liveChannels.orEmpty() + s.favoriteItems).distinctBy(::itemKey).filter { itemKey(it) in s.favorites }
            else -> s.items
        }
        return list.filter { c ->
            (s.category == null || s.episodes != null || c.categoryId == s.category) &&
                "${c.kind}:${c.categoryId}" !in s.hidden && c.name.contains(s.query, true)
        }
    }
    fun query(value: String) { state.value = state.value.copy(query = value) }
    fun open(item: Channel) {
        if ((item.locked || categoryLocked(item)) && !unlocked) {
            state.value = state.value.copy(lockedItem = item, pinError = null); return
        }
        if (item.kind == MediaKind.LIVE || (item.kind == MediaKind.SERIES && !item.isContainer)) play(item)
        else {
            state.value = state.value.copy(details = item, detailsLoading = true)
            val currentEpoch = epoch
            viewModelScope.launch {
                val detailed = runCatching { backend.details(item) }.getOrDefault(item)
                if (currentEpoch == epoch && state.value.details?.id == item.id)
                    state.value = state.value.copy(details = detailed, detailsLoading = false)
            }
        }
    }
    fun closeDetails() { state.value = state.value.copy(details = null, detailsLoading = false) }
    fun watchDetails() {
        val item = state.value.details ?: return
        closeDetails()
        if (item.isContainer) openSeries(item) else play(item)
    }
    private fun categoryLocked(item: Channel): Boolean {
        val content = state.value.content ?: return false
        return (content.liveCategories + content.vodCategories + content.seriesCategories).any { it.id == item.categoryId && it.kind == item.kind && it.locked }
    }
    private fun openSeries(item: Channel) {
        itemJob?.cancel(); val currentEpoch = epoch
        state.value = state.value.copy(busy = true, loading = "Loading episodes…", seriesTitle = item.name)
        itemJob = viewModelScope.launch {
            try {
                val episodes = backend.episodes(item)
                if (currentEpoch == epoch) state.value = state.value.copy(episodes = episodes, busy = false,
                    error = if (episodes.isEmpty()) "This source returned no episodes for this series." else null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentEpoch == epoch) state.value = state.value.copy(busy = false, error = safeMessage(error))
            }
        }
    }
    private fun play(item: Channel) {
        playJob?.cancel(); val currentEpoch = epoch; val request = ++sequence
        state.value = state.value.copy(selected = item, resolving = true, error = null, guide = null)
        playJob = viewModelScope.launch {
            try {
                val source = backend.resolve(item, if (unlocked) prefs.getString("provider_pin", null) else null)
                if (currentEpoch == epoch && request == sequence) {
                    val history = (listOf(item) + state.value.history).distinctBy(::itemKey).take(20)
                    runCatching { store.saveItems("history:${state.value.active?.id}", history) }
                    state.value = state.value.copy(history = history, playback = PlayRequest(item, source, request), resolving = false,
                        fullscreen = state.value.fullscreen || item.kind != MediaKind.LIVE || item.catchupStart > 0 || state.value.tab != Tab.LIVE)
                    if (item.kind == MediaKind.LIVE) loadGuide(item)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentEpoch == epoch && request == sequence) state.value = state.value.copy(resolving = false, error = safeMessage(error))
            }
        }
    }
    private fun loadGuide(item: Channel) {
        guideJob?.cancel(); val request = sequence
        guideJob = viewModelScope.launch {
            val programs = runCatching { backend.guide(item) }.getOrDefault(emptyList())
            if (request == sequence) state.value = state.value.copy(guide = programs)
        }
    }
    fun catchup(program: GuideProgram) {
        if (program.channel.catchupDays <= 0 || program.start > System.currentTimeMillis()/1000) return
        open(program.channel.copy(id = "${program.channel.id}:archive:${program.start}", name = program.title,
            catchupStart = program.start, catchupEnd = program.end))
    }
    fun favorite(item: Channel) {
        updateSet("favorites", itemKey(item))
        val next = (state.value.favoriteItems + item).distinctBy(::itemKey).filter { itemKey(it) in state.value.favorites }
        runCatching { store.saveItems("favorite_items:${state.value.active?.id}", next) }
        state.value = state.value.copy(favoriteItems = next)
    }
    fun zap(step: Int) {
        val list = visibleItems().filter { it.kind == MediaKind.LIVE }
        if (list.isEmpty()) return
        val current = list.indexOfFirst { it.id == state.value.playback?.item?.id }.coerceAtLeast(0)
        open(list[(current + step + list.size) % list.size])
    }
    fun savePosition(item: Channel, position: Long) {
        if (item.kind != MediaKind.LIVE) prefs.edit().putLong("position:${state.value.active?.id}:${itemKey(item)}", position.coerceAtLeast(0)).apply()
    }
    fun position(item: Channel) = prefs.getLong("position:${state.value.active?.id}:${itemKey(item)}", 0)
    fun pin(category: Category) { updateSet("pinned", categoryKey(category)) }
    fun hide(category: Category) { updateSet("hidden", categoryKey(category)); category(null) }
    fun restoreGroups() {
        prefs.edit().remove("hidden:${state.value.active?.id}").apply()
        state.value = state.value.copy(hidden = emptySet())
    }
    private fun updateSet(name: String, key: String) {
        val old = when (name) { "favorites" -> state.value.favorites; "pinned" -> state.value.pinned; else -> state.value.hidden }
        val next = old.toMutableSet().apply { if (!add(key)) remove(key) }.toSet()
        prefs.edit().putStringSet("$name:${state.value.active?.id}", next).apply()
        state.value = when (name) {
            "favorites" -> state.value.copy(favorites = next)
            "pinned" -> state.value.copy(pinned = next)
            else -> state.value.copy(hidden = next)
        }
    }
    fun unlock(pin: String) {
        val required = prefs.getString("parental_pin", "").orEmpty().ifBlank { state.value.content?.parentalPassword.orEmpty() }
        if (required.isBlank()) { state.value = state.value.copy(pinError = "Set a parental PIN in Settings first."); return }
        if (pin != required) { state.value = state.value.copy(pinError = "Incorrect PIN."); return }
        unlocked = true; prefs.edit().putString("provider_pin", pin).apply()
        val item = state.value.lockedItem; state.value = state.value.copy(lockedItem = null, pinError = null)
        item?.let(::open)
    }
    fun savePin(pin: String) { prefs.edit().putString("parental_pin", pin).apply(); unlocked = false }
    fun lockAgain() { unlocked = false; prefs.edit().remove("provider_pin").apply() }
    fun cancelPin() { state.value = state.value.copy(lockedItem = null, pinError = null) }
    fun retryPlayback() { state.value.playback?.item?.let(::play) }
    fun clearError() { state.value = state.value.copy(error = null) }
    fun fullscreen(value: Boolean) { state.value = state.value.copy(fullscreen = value) }
    fun backEpisodes() { state.value = state.value.copy(episodes = null, query = "") }
    fun clearCache() { backend.clearCache(); state.value = state.value.copy(items = emptyList(), episodes = null); if (state.value.tab == Tab.MOVIES || state.value.tab == Tab.SERIES) category(state.value.category) }
    private fun storedSet(name: String, id: String) = prefs.getStringSet("$name:$id", emptySet()).orEmpty().toSet()
    private fun online(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
    }
    private fun safeMessage(error: Exception): String {
        if (!online()) return "No internet connection. Check your Wi-Fi or mobile data."
        return error.message?.takeUnless { it.contains("http://") || it.contains("https://") } ?: "The source could not complete this request. Check your details and retry."
    }
    companion object {
        fun itemKey(item: Channel) = "${item.kind}:${item.id}"
        fun categoryKey(category: Category) = "${category.kind}:${category.id}"
        fun newSource(type: SourceType = SourceType.XTREAM) = SourceConfig(UUID.randomUUID().toString(), "", type, "", mac = randomMac())
        private fun randomMac(): String { val random = SecureRandom(); return "00:1A:79:" + (1..3).joinToString(":") { "%02X".format(random.nextInt(256)) } }
    }
}
