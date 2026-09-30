package com.formulatv.player

data class PortalConfig(
    val baseUrl: String,
    val mac: String = ""
)
enum class MediaKind { LIVE, VOD, SERIES }
data class Category(val id: String, val title: String, val kind: MediaKind = MediaKind.LIVE, val locked: Boolean = false)
data class Channel(
    val id: String,
    val name: String,
    val command: String,
    val categoryId: String,
    val kind: MediaKind = MediaKind.LIVE,
    val poster: String = "",
    val series: String = "0",
    val extension: String = "",
    val episodeRefs: List<String> = emptyList(),
    val seasonEpisodeRefs: Map<String, List<String>> = emptyMap(),
    val isContainer: Boolean = false,
    val locked: Boolean = false,
    val season: String = "",
    val episodeNumber: Int = 0,
    val catchupDays: Int = 0,
    val catchupStart: Long = 0L,
    val catchupEnd: Long = 0L,
    val portalMovieId: String = "", val portalSeasonId: String = "", val portalEpisodeId: String = "",
    val summary: String = "", val rating: String = "", val year: String = "", val duration: String = "", val genre: String = ""
)
data class GuideProgram(val id: String, val title: String, val start: Long, val end: Long, val channel: Channel)
data class PortalContent(
    val liveCategories: List<Category>,
    val liveChannels: List<Channel>,
    val vodCategories: List<Category>,
    val seriesCategories: List<Category> = emptyList(),
    val accountInfo: AccountInfo = AccountInfo(),
    val parentalPassword: String = ""
)
data class AccountInfo(
    val username: String = "",
    val status: String = "",
    val expires: String = "",
    val plan: String = "",
    val server: String = ""
)
data class PlaybackSource(val url: String, val headers: Map<String, String>)

enum class ConnectionProblem { NO_INTERNET, NOT_AUTHORIZED, PORTAL_NOT_FOUND, EXPIRED_OR_BLOCKED, INVALID_RESPONSE, NETWORK }

sealed interface Screen {
    data object Setup : Screen
    data object Library : Screen
    data object Content : Screen
    data object Seasons : Screen
    data object Episodes : Screen
    data object Guide : Screen
    data class ConnectionError(val type: ConnectionProblem, val message: String) : Screen
    data class Player(val channel: Channel, val source: PlaybackSource) : Screen
}

data class AppState(
    val screen: Screen = Screen.Setup,
    val busy: Boolean = false,
    val loadingProgress: Float = 0f,
    val error: String? = null,
    val config: PortalConfig? = null,
    val liveCategories: List<Category> = emptyList(),
    val vodCategories: List<Category> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val liveChannels: List<Channel> = emptyList(),
    val vodItems: List<Channel> = emptyList(),
    val seriesItems: List<Channel> = emptyList(),
    val episodeItems: List<Channel> = emptyList(),
    val visibleEpisodeItems: List<Channel> = emptyList(),
    val seasonNames: List<String> = emptyList(),
    val selectedSeason: String = "",
    val selectedSeriesTitle: String = "",
    val guideItems: List<GuideProgram> = emptyList(),
    val guideChannelName: String = "",
    val selectedKind: MediaKind = MediaKind.LIVE,
    val selectedCategory: String? = null,
    val selectedCategoryTitle: String = "All",
    val favorites: Set<String> = emptySet(),
    val recentChannelIds: List<String> = emptyList(),
    val query: String = "",
    val resolvingChannel: Channel? = null,
    val connectionLabel: String? = null,
    val accountInfo: AccountInfo = AccountInfo(),
    val settingsMessage: String? = null,
    val pendingLockedItem: Channel? = null,
    val parentalError: String? = null
)

