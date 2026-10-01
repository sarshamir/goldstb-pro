@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.formulatv.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Background = Color(0xFF100C19)
private val Panel = Color(0xFF1D1729)
private val PanelLight = Color(0xFF2B203C)
private val Purple = Color(0xFFAC7AF5)
private val Red = Color(0xFFFF5266)
private val Muted = Color(0xFFA99DB9)
private val Shape = RoundedCornerShape(16.dp)

class MainActivity : ComponentActivity() {
    var pip by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Background,
            surface = Panel, secondary = Red, tertiary = Red)) { FormulaApp(this) } }
    }
    fun pictureInPicture() {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature("android.software.picture_in_picture")) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())
        }
    }
    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig); pip = inPip
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun FormulaApp(activity: MainActivity, vm: FormulaViewModel = viewModel()) {
    val s = vm.state.value
    val config = LocalConfiguration.current
    val tv = config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val wide = config.screenWidthDp >= 700 || tv
    val player = remember { ExoPlayer.Builder(activity).build() }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var playbackStatus by remember { mutableStateOf("Ready") }
    var sourceDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceConfig?>(null) }
    var searchDialog by remember { mutableStateOf(false) }
    var guideDialog by remember { mutableStateOf(false) }
    var retryDialog by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackStatus = when (state) { Player.STATE_BUFFERING -> "Buffering…"; Player.STATE_READY -> if (player.playWhenReady) "Playing" else "Paused"; Player.STATE_ENDED -> "Finished"; else -> "Ready" }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) playbackStatus = "Playing" }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "This stream could not play. Retry or choose another channel."
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    DisposableEffect(lifecycle, player) {
        var resume = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !activity.isInPictureInPictureMode) {
                resume = player.playWhenReady; player.pause()
                vm.state.value.playback?.let { vm.savePosition(it.item, player.currentPosition) }
            }
            if (event == Lifecycle.Event.ON_START && resume) { player.play(); resume = false }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(s.playback?.sequence) {
        playbackError = null
        val request = s.playback
        if (request == null) { player.stop(); player.clearMediaItems() }
        else {
            player.stop(); player.clearMediaItems()
            val data = DefaultHttpDataSource.Factory().setDefaultRequestProperties(request.source.headers)
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(18000).setReadTimeoutMs(35000)
            player.setMediaSource(DefaultMediaSourceFactory(data).createMediaSource(MediaItem.fromUri(request.source.url)))
            if (request.item.kind != MediaKind.LIVE) player.seekTo(vm.position(request.item))
            player.prepare(); player.play()
        }
    }
    LaunchedEffect(s.tab, s.fullscreen, s.playback?.sequence) {
        if (s.playback != null && !s.fullscreen && s.tab != Tab.LIVE && s.tab != Tab.FAVORITES) player.pause()
        else if (s.playback != null && (s.fullscreen || s.playback.item.kind == MediaKind.LIVE)) player.play()
    }
    DisposableEffect(s.playback?.sequence) {
        if (s.playback != null) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            s.playback?.let { vm.savePosition(it.item, player.currentPosition) }
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    BackHandler(enabled = s.fullscreen || s.episodes != null || s.tab != Tab.HOME) {
        when { s.fullscreen -> vm.fullscreen(false); s.episodes != null -> vm.backEpisodes(); else -> vm.select(Tab.HOME) }
    }
    if (activity.pip) {
        VideoSurface(player, false, Modifier.fillMaxSize())
        return
    }
    Box(Modifier.fillMaxSize().background(Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (s.fullscreen && s.playback != null) {
            FullPlayer(player, s, playbackError, vm, activity)
        } else Column(Modifier.fillMaxSize()) {
            Header(s, onSource = { sourceDialog = true }, onSearch = { searchDialog = true })
            if (!s.connected) {
                Landing(s, onAdd = { editing = FormulaViewModel.newSource() }, onConnect = vm::connect,
                    onEdit = { editing = it }, onDismissError = vm::clearError, onAutoLoad = vm::autoLoad)
            } else Row(Modifier.weight(1f)) {
                if (wide) Navigation(s.tab, true, vm::select)
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (wide) 22.dp else 16.dp)) {
                    if (s.error != null) ErrorBanner(s.error, vm::clearError)
                    when (s.tab) {
                        Tab.HOME -> HomeScreen(s, vm)
                        Tab.SETTINGS -> SettingsScreen(s, vm, onSources = { sourceDialog = true }, onEdit = { editing = s.active })
                        else -> BrowseScreen(s, vm, wide, player, playbackError, playbackStatus, onGuide = { guideDialog = true })
                    }
                }
            }
            if (s.connected && !wide) Navigation(s.tab, false, vm::select)
        }
        if (s.busy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .65f)), contentAlignment = Alignment.Center) {
            Column(Modifier.clip(Shape).background(Panel).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Purple, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(18.dp)); Text(s.loading, color = Color.White)
            }
        }
    }
    if (sourceDialog) SourceListDialog(s, onClose = { sourceDialog = false }, onConnect = { sourceDialog = false; vm.connect(it) },
        onAdd = { sourceDialog = false; editing = FormulaViewModel.newSource() },
        onEdit = { sourceDialog = false; editing = it }, onDelete = vm::deleteSource)
    editing?.let { source -> SourceEditor(source, tv, onClose = { editing = null }, onSave = { if (vm.saveSource(it)) editing = null }, error = s.error) }
    if (searchDialog) SearchDialog(s.query, onClose = { searchDialog = false }, onSearch = { if (s.tab == Tab.HOME) vm.select(Tab.LIVE); vm.query(it); searchDialog = false })
    if (guideDialog) GuideDialog(s, onClose = { guideDialog = false }, onPlay = { guideDialog = false; vm.catchup(it) })
    s.details?.let { DetailDialog(it, s.detailsLoading, FormulaViewModel.itemKey(it) in s.favorites, vm::closeDetails, vm::watchDetails, { vm.favorite(it) }) }
    if (s.lockedItem != null) PinDialog(s.pinError, onClose = vm::cancelPin, onSave = vm::unlock)
}

@Composable private fun Header(s: FormulaState, onSource: () -> Unit, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Color(0xFF8450D1), Red))), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.app_logo), "Formula TV", tint = Color.Unspecified, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Formula TV", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(if (s.connected) s.active?.name.orEmpty() else "Your TV. Your way.", color = Muted, fontSize = 11.sp)
        }
        if (s.connected) IconAction(Icons.Default.Search, "Search", onSearch)
        IconAction(Icons.Default.Dns, "Sources", onSource)
    }
}
@Composable private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusTile(onClick, modifier = modifier.size(44.dp), radius = 12) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(21.dp)) }
    }
}
@Composable private fun FocusTile(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false,
    onLongClick: (() -> Unit)? = null, radius: Int = 14, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(radius.dp)
    Box(modifier.onFocusChanged { focused = it.isFocused }.clip(shape)
        .background(if (focused) Color(0xFF6334A0) else if (selected) PanelLight else Panel)
        .border(if (focused) 3.dp else 1.dp, if (focused) Color(0xFFE7D7FF) else if (selected) Purple else Color.Transparent, shape)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) { content() }
}
@Composable private fun Action(label: String, icon: ImageVector? = null, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    FocusTile(onClick, modifier.heightIn(min = 48.dp), selected) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (icon != null) Icon(icon, label, tint = if (selected) Purple else Color.White, modifier = Modifier.size(20.dp))
            Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
private fun tabIcon(tab: Tab) = when(tab) { Tab.HOME -> Icons.Default.Home; Tab.LIVE -> Icons.Default.LiveTv; Tab.MOVIES -> Icons.Default.Movie; Tab.SERIES -> Icons.Default.VideoLibrary; Tab.FAVORITES -> Icons.Default.Star; Tab.SETTINGS -> Icons.Default.Settings }
private fun tabLabel(tab: Tab) = when(tab) { Tab.HOME -> "Home"; Tab.LIVE -> "Live TV"; Tab.MOVIES -> "Movies"; Tab.SERIES -> "Series"; Tab.FAVORITES -> "Favorites"; Tab.SETTINGS -> "Settings" }
@Composable private fun Navigation(selected: Tab, wide: Boolean, onSelect: (Tab) -> Unit) {
    if (wide) Column(Modifier.width(150.dp).fillMaxHeight().padding(start = 16.dp, end = 2.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tab.entries.forEach { tab -> Action(tabLabel(tab), tabIcon(tab), { onSelect(tab) }, Modifier.fillMaxWidth(), selected == tab) }
    } else Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 5.dp, vertical = 8.dp)) {
        Tab.entries.forEach { tab -> FocusTile({ onSelect(tab) }, Modifier.weight(1f).height(54.dp), selected == tab, radius = 10) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(tabIcon(tab), tabLabel(tab), tint = if (selected == tab) Purple else Muted, modifier = Modifier.size(21.dp))
                Text(tabLabel(tab), fontSize = 9.sp, color = if (selected == tab) Color.White else Muted, maxLines = 1)
            }
        } }
    }
}
@Composable private fun ErrorBanner(message: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(Shape).background(Color(0xFF392237)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = Color(0xFFFFD8E7), modifier = Modifier.weight(1f), fontSize = 13.sp)
        IconAction(Icons.Default.Close, "Dismiss", onClose)
    }
}
@Composable private fun Landing(s: FormulaState, onAdd: () -> Unit, onConnect: (SourceConfig) -> Unit, onEdit: (SourceConfig) -> Unit, onDismissError: () -> Unit, onAutoLoad: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 450.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Auto load on launch", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = s.autoLoad, onCheckedChange = onAutoLoad)
        }
        Spacer(Modifier.height(20.dp))
        Icon(Icons.Default.LiveTv, null, tint = Purple, modifier = Modifier.size(60.dp))
        Spacer(Modifier.height(20.dp))
        Text("Everything you watch,\nin one place.", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text("Connect your Xtream Codes or Stalker source\nto enjoy Live TV, movies and series.", color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        if (s.error != null) ErrorBanner(s.error, onDismissError)
        Action("Add content source", Icons.Default.Add, onAdd, Modifier.widthIn(max = 450.dp).fillMaxWidth(), true)
        Spacer(Modifier.height(18.dp))
        s.sources.forEach { source ->
            Row(Modifier.widthIn(max = 450.dp).fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Action(source.name, Icons.Default.Dns, { onConnect(source) }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp)); IconAction(Icons.Default.Edit, "Edit source", { onEdit(source) })
            }
        }
        Spacer(Modifier.height(25.dp))
        Text("Please use authorized TV providers only.\nNo channels or subscriptions are included.", color = Muted, fontSize = 11.sp)
    }
}
@Composable private fun HomeScreen(s: FormulaState, vm: FormulaViewModel) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clip(Shape).background(Brush.horizontalGradient(listOf(Color(0xFF502580), Color(0xFF482032)))).padding(24.dp)) {
                Text("READY TO WATCH", color = Red, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(10.dp)); Text("Welcome to Formula TV", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp)); Text("${s.content?.liveChannels?.size ?: 0} channels · ${s.active?.type?.name?.lowercase()?.replaceFirstChar { it.uppercase() }} connected", color = Color(0xFFDBCEE9), fontSize = 13.sp)
                Spacer(Modifier.height(18.dp)); Action("Watch Live TV", Icons.Default.PlayArrow, { vm.select(Tab.LIVE) }, selected = true)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Tab.LIVE, Tab.MOVIES, Tab.SERIES).forEach { tab -> FocusTile({ vm.select(tab) }, Modifier.weight(1f).height(116.dp)) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Icon(tabIcon(tab), tabLabel(tab), tint = Purple, modifier = Modifier.size(27.dp))
                        Text(tabLabel(tab), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                } }
            }
        }
        if (s.history.isNotEmpty()) item {
            SectionTitle("Recently watched")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(s.history, key = FormulaViewModel::itemKey) { item -> PosterCard(item, Modifier.width(150.dp), false, onClick = { vm.open(item) }, onLongClick = { vm.favorite(item) }) }
            }
        }
        item {
            Text("Your account", fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            val account = s.content?.accountInfo
            Text(listOf(account?.username?.takeIf(String::isNotBlank), account?.status?.takeIf(String::isNotBlank), account?.expires?.takeIf(String::isNotBlank)?.let { "Expires $it" }).filterNotNull().joinToString(" · "), color = Muted, fontSize = 13.sp)
        }
    }
}
@Composable private fun SectionTitle(title: String) { Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }

@Composable private fun BrowseScreen(s: FormulaState, vm: FormulaViewModel, wide: Boolean, player: ExoPlayer, error: String?, status: String, onGuide: () -> Unit) {
    val visible = vm.visibleItems()
    var groups by remember(s.tab) { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf<Category?>(null) }
    val title = if (s.episodes != null) s.seriesTitle else tabLabel(s.tab)
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (s.episodes != null) { IconAction(Icons.Default.ArrowBack, if (s.episodePage) "Back to seasons" else "Back to series", vm::backEpisodes); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            SectionTitle(title)
            Text("${visible.size} ${if (s.episodes != null) (if (s.episodePage) "episodes" else "seasons") else "items"}" + (s.category?.let { id -> vm.categories().firstOrNull { it.id == id }?.title?.let { " · $it" } }.orEmpty()) + if (s.query.isNotBlank()) " · ${s.query}" else "", color = Muted, fontSize = 11.sp)
        }
        if (s.episodes == null && s.hasMore && (s.tab == Tab.MOVIES || s.tab == Tab.SERIES)) {
            Action(if (s.loadingMore) "Loading…" else "Load more", Icons.Default.Add, vm::loadMore)
            Spacer(Modifier.width(8.dp))
        }
        Action("Groups", Icons.Default.FilterList, { groups = true })
    }
    if (s.tab == Tab.LIVE || (s.tab == Tab.FAVORITES && visible.all { it.kind == MediaKind.LIVE })) {
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ChannelList(visible, s, vm, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1.35f).fillMaxHeight()) {
                Preview(player, s, error, status, vm, onGuide)
                Spacer(Modifier.height(18.dp))
                GuideSummary(s, onGuide)
            }
        } else Column(Modifier.fillMaxSize()) {
            Preview(player, s, error, status, vm, onGuide)
            Spacer(Modifier.height(12.dp))
            ChannelList(visible, s, vm, Modifier.weight(1f))
        }
    } else if (visible.isEmpty() && !s.busy) EmptyState("Nothing here yet", "Try another group or clear your search.")
    else LazyVerticalGrid(columns = GridCells.Adaptive(if (wide) 150.dp else 140.dp),
        modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(visible, key = FormulaViewModel::itemKey) { item -> PosterCard(item, Modifier.fillMaxWidth(), FormulaViewModel.itemKey(item) in s.favorites,
            onClick = { vm.open(item) }, onLongClick = { vm.favorite(item) }) }
    }
    if (groups) AlertDialog(onDismissRequest = { groups = false }, title = { Text("Content groups") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Action("All", onClick = { vm.category(null); groups = false }, modifier = Modifier.fillMaxWidth(), selected = s.category == null) }
            items(vm.categories(), key = { it.id }) { cat ->
                FocusTile({ vm.category(cat.id); groups = false }, Modifier.fillMaxWidth(), selected = s.category == cat.id,
                    onLongClick = { groupMenu = cat; groups = false }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(cat.title, color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        if (FormulaViewModel.categoryKey(cat) in s.pinned) Icon(Icons.Default.PushPin, "Pinned", tint = Purple, modifier = Modifier.size(16.dp))
                        if (cat.locked) Icon(Icons.Default.Lock, "Locked", tint = Muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { groups = false }) { Text("Close") } })
    groupMenu?.let { cat -> AlertDialog(onDismissRequest = { groupMenu = null }, title = { Text(cat.title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("Pin / unpin group", Icons.Default.PushPin, { vm.pin(cat); groupMenu = null }, Modifier.fillMaxWidth())
            Action("Hide group", Icons.Default.VisibilityOff, { vm.hide(cat); groupMenu = null }, Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(onClick = { groupMenu = null }) { Text("Cancel") } }) }
}
@Composable private fun ChannelList(items: List<Channel>, s: FormulaState, vm: FormulaViewModel, modifier: Modifier) {
    if (items.isEmpty()) { Box(modifier) { EmptyState("No channels", "Choose another group or clear your search.") }; return }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(items, key = FormulaViewModel::itemKey) { item ->
            FocusTile({ if (s.playback?.let { it.item.id == item.id && it.item.kind == item.kind } == true) vm.fullscreen(true) else vm.open(item) },
                Modifier.fillMaxWidth(), s.selected?.id == item.id, onLongClick = { vm.favorite(item) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(PanelLight), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.LiveTv, item.name, tint = Purple, modifier = Modifier.size(25.dp))
                        AsyncImage(model = item.poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.playback?.item?.id == item.id) Text("Now playing", color = Red, fontSize = 10.sp)
                    }
                    if (FormulaViewModel.itemKey(item) in s.favorites) Icon(Icons.Default.Star, "Favorite", tint = Purple, modifier = Modifier.size(17.dp))
                    if (item.locked) Icon(Icons.Default.Lock, "Locked", tint = Muted, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
@Composable private fun PosterCard(item: Channel, modifier: Modifier, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusTile(onClick, modifier, onLongClick = onLongClick) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(.72f).background(Brush.verticalGradient(listOf(PanelLight, Color(0xFF352148)))), contentAlignment = Alignment.Center) {
                Icon(if (item.isSeason) Icons.Default.VideoLibrary else if (item.kind == MediaKind.LIVE) Icons.Default.LiveTv else if (item.kind == MediaKind.SERIES) Icons.Default.VideoLibrary else Icons.Default.Movie, null, tint = Purple.copy(alpha = .5f), modifier = Modifier.size(44.dp))
                AsyncImage(model = item.poster, contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (favorite) Icon(Icons.Default.Star, "Favorite", tint = Purple, modifier = Modifier.align(Alignment.TopEnd).padding(9.dp).size(20.dp))
                if (item.locked) Icon(Icons.Default.Lock, "Locked", tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp).size(18.dp))
                if (item.season.isNotBlank()) Text(item.season, color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .6f)).padding(8.dp))
            }
            Text(item.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(12.dp).heightIn(min = 32.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.TvOff, null, tint = Muted, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(15.dp)); Text(title, color = Color.White, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp)); Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun VideoSurface(player: ExoPlayer, controls: Boolean, modifier: Modifier) {
    AndroidView(factory = { context -> PlayerView(context).apply {
        this.player = player; useController = controls; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    } }, modifier = modifier, update = { view -> view.player = player; view.useController = controls },
        onRelease = { view -> view.player = null })
}
@Composable private fun Preview(player: ExoPlayer, s: FormulaState, error: String?, status: String, vm: FormulaViewModel, onGuide: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Shape).background(Panel)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color.Black), contentAlignment = Alignment.Center) {
            if (s.playback != null) VideoSurface(player, false, Modifier.fillMaxSize())
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PlayCircle, null, tint = Purple, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp)); Text("Choose a channel", color = Muted, fontSize = 13.sp)
            }
            if (s.resolving) CircularProgressIndicator(color = Purple, modifier = Modifier.size(28.dp))
            if (error != null) Text(error, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .85f)).padding(18.dp), fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.playback?.item?.name ?: "Live preview", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (s.resolving) "Connecting…" else if (s.playback != null) status else "Select a channel to start", color = Muted, fontSize = 10.sp)
            }
            if (s.playback != null) {
                IconAction(Icons.Default.Fullscreen, "Full screen", { vm.fullscreen(true) })
                Spacer(Modifier.width(6.dp)); IconAction(Icons.Default.StarBorder, "Favorite", { vm.favorite(s.playback.item) })
            }
        }
    }
}
@Composable private fun GuideSummary(s: FormulaState, onGuide: () -> Unit) {
    val now = System.currentTimeMillis()/1000
    val current = s.guide?.firstOrNull { it.start <= now && it.end > now }
    val next = s.guide?.firstOrNull { it.start > now }
    Column(Modifier.fillMaxWidth().clip(Shape).background(Panel).padding(18.dp)) {
        Text("TV GUIDE", color = Purple, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp)); Text(current?.title ?: "Select a channel to see its guide", color = Color.White, fontSize = 15.sp)
        if (next != null) { Spacer(Modifier.height(8.dp)); Text("Next: ${next.title}", color = Muted, fontSize = 12.sp) }
        Spacer(Modifier.height(14.dp)); Action("Guide & catch-up", Icons.Default.Schedule, onGuide)
    }
}
@Composable private fun FullPlayer(player: ExoPlayer, s: FormulaState, error: String?, vm: FormulaViewModel, activity: MainActivity) {
    val request = s.playback ?: return
    var tools by remember { mutableStateOf(true) }
    val rootFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(tools) { if (!tools && request.item.kind == MediaKind.LIVE) rootFocus.requestFocus() else backFocus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).focusable().onPreviewKeyEvent { event ->
        if (!tools && event.type == KeyEventType.KeyUp && (event.key == Key.DirectionCenter || event.key == Key.Enter) && request.item.kind == MediaKind.LIVE && request.item.catchupStart == 0L) {
            tools = !tools; true
        } else if (!tools && request.item.kind == MediaKind.LIVE && request.item.catchupStart == 0L && event.type == KeyEventType.KeyUp && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
            vm.zap(if (event.key == Key.DirectionDown) 1 else -1); true
        } else false
    }) {
        VideoSurface(player, request.item.kind != MediaKind.LIVE || request.item.catchupStart > 0, Modifier.fillMaxSize())
        if (tools) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .8f), Color.Transparent))).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconAction(Icons.Default.ArrowBack, "Back", { vm.fullscreen(false) }, Modifier.focusRequester(backFocus))
            Text(request.item.name, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconAction(Icons.Default.StarBorder, "Favorite", { vm.favorite(request.item) })
            IconAction(Icons.Default.Refresh, "Retry stream", { vm.retryPlayback() })
            if (Build.VERSION.SDK_INT >= 26) IconAction(Icons.Default.PictureInPictureAlt, "Picture in picture", activity::pictureInPicture)
            IconAction(Icons.Default.VisibilityOff, "Hide tools", { tools = false })
        } else IconAction(Icons.Default.MoreHoriz, "Show tools", { tools = true }, Modifier.align(Alignment.TopEnd).padding(12.dp))
        if (s.resolving) CircularProgressIndicator(color = Purple, modifier = Modifier.align(Alignment.Center))
        if (error != null) Column(Modifier.align(Alignment.Center).clip(Shape).background(Panel).padding(20.dp)) {
            Text(error, color = Color.White); Spacer(Modifier.height(14.dp))
            Action("Retry", Icons.Default.Refresh, { vm.retryPlayback() })
        }
    }
}

@Composable private fun SettingsScreen(s: FormulaState, vm: FormulaViewModel, onSources: () -> Unit, onEdit: () -> Unit) {
    var setPin by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Settings")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto load on launch", color = Color.White)
                Text("Connect to your last saved source when Formula TV opens", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = s.autoLoad, onCheckedChange = vm::autoLoad)
        }
        Text("CONTENT SOURCES", color = Red, fontSize = 11.sp, letterSpacing = 2.sp)
        Action("Manage sources", Icons.Default.Dns, onSources, Modifier.fillMaxWidth())
        Action("Edit current source", Icons.Default.Edit, onEdit, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp)); Text("PLAYBACK & PRIVACY", color = Purple, fontSize = 11.sp, letterSpacing = 2.sp)
        Action("Clear content cache", Icons.Default.Refresh, vm::clearCache, Modifier.fillMaxWidth())
        Action("Restore hidden groups", Icons.Default.Visibility, vm::restoreGroups, Modifier.fillMaxWidth())
        Action("Set parental PIN", Icons.Default.Lock, { setPin = true }, Modifier.fillMaxWidth())
        Action("Lock protected content again", Icons.Default.Security, vm::lockAgain, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp)); Text("ABOUT FORMULA TV", color = Purple, fontSize = 11.sp, letterSpacing = 2.sp)
        Text("Version ${BuildConfig.VERSION_NAME} · Android TV & phone", color = Color.White, fontSize = 14.sp)
        s.active?.let { active ->
            Text("${active.name} · ${active.type.name}", color = Muted, fontSize = 12.sp)
            if (active.type == SourceType.STALKER) Text("MAC: ${active.mac}", color = Muted, fontSize = 12.sp)
        }
        Text("Long press an item to add or remove a favorite. Long press a group to pin or hide it.", color = Muted, fontSize = 12.sp)
        Text("Please use authorized TV providers only. Formula TV includes no channels or subscriptions.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(20.dp))
    }
    if (setPin) PinDialog(null, onClose = { setPin = false }, onSave = { if (it.matches(Regex("[0-9]{4,8}"))) { vm.savePin(it); setPin = false } }, setup = true)
}
@Composable private fun SourceListDialog(s: FormulaState, onClose: () -> Unit, onConnect: (SourceConfig) -> Unit,
    onAdd: () -> Unit, onEdit: (SourceConfig) -> Unit, onDelete: (SourceConfig) -> Unit) {
    var deleting by remember { mutableStateOf<SourceConfig?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Content sources") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.sources.isEmpty()) item { Text("Add an Xtream Codes or Stalker source to start.", color = Muted) }
            items(s.sources, key = { it.id }) { source -> Column {
                Action(source.name, Icons.Default.Dns, { onConnect(source) }, Modifier.fillMaxWidth(), source.id == s.active?.id)
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(source.type.name, color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    IconAction(Icons.Default.Edit, "Edit", { onEdit(source) })
                    Spacer(Modifier.width(7.dp)); IconAction(Icons.Default.DeleteOutline, "Remove source", { deleting = source })
                }
            } }
        }
    }, confirmButton = { TextButton(onClick = onAdd) { Text("Add source") } }, dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
    deleting?.let { source -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove ${source.name}?") },
        text = { Text("This removes the saved source from this device.") },
        confirmButton = { TextButton(onClick = { onDelete(source); deleting = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
@Composable private fun SourceEditor(source: SourceConfig, tv: Boolean, onClose: () -> Unit, onSave: (SourceConfig) -> Unit, error: String?) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var type by remember(source.id) { mutableStateOf(source.type) }
    var url by remember(source.id) { mutableStateOf(source.url) }
    var username by remember(source.id) { mutableStateOf(source.username) }
    var password by remember(source.id) { mutableStateOf(source.password) }
    var mac by remember(source.id) { mutableStateOf(source.mac) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (source.url.isBlank()) "Add content source" else "Edit content source") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Stalker", onClick = { type = SourceType.STALKER }, modifier = Modifier.weight(1f), selected = type == SourceType.STALKER)
                Action("Xtream", onClick = { type = SourceType.XTREAM }, modifier = Modifier.weight(1f), selected = type == SourceType.XTREAM)
            }
            Text("Please use authorized TV providers only.", color = Red, fontSize = 11.sp)
            EditField("Source name", name, { name = it }, tv)
            EditField(if (type == SourceType.STALKER) "Portal URL" else "Server URL", url, { url = it }, tv, uri = true)
            if (type == SourceType.XTREAM) {
                EditField("Username", username, { username = it }, tv)
                EditField("Password", password, { password = it }, tv, secret = true)
            } else {
                EditField("MAC address", mac, { mac = it }, tv)
                Text("Register this MAC with your provider, or enter the MAC authorized for your account.", color = Muted, fontSize = 11.sp)
            }
            if (error != null) Text(error, color = Color(0xFFFFB2D0), fontSize = 12.sp)
        } }, confirmButton = { TextButton(onClick = { onSave(source.copy(name = name, type = type, url = url, username = username, password = password, mac = mac)) }) { Text("Save & connect") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
@Composable private fun EditField(label: String, value: String, onValue: (String) -> Unit, tv: Boolean, secret: Boolean = false, uri: Boolean = false) {
    var edit by remember { mutableStateOf(false) }
    if (tv) {
        FocusTile({ edit = true }, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(label, color = Purple, fontSize = 11.sp)
                Spacer(Modifier.height(5.dp)); Text(if (secret && value.isNotBlank()) "••••••••" else value.ifBlank { "Press OK to enter" }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (edit) {
            var text by remember { mutableStateOf(value) }
            AlertDialog(onDismissRequest = { edit = false }, title = { Text(label) }, text = {
                OutlinedTextField(text, { text = it }, singleLine = true,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (uri) KeyboardType.Uri else KeyboardType.Text))
            }, confirmButton = { TextButton(onClick = { onValue(text); edit = false }) { Text("Done") } },
                dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } })
        }
    } else OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (uri) KeyboardType.Uri else KeyboardType.Text), shape = RoundedCornerShape(12.dp))
}
@Composable private fun SearchDialog(initial: String, onClose: () -> Unit, onSearch: (String) -> Unit) {
    var query by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Search") }, text = {
        OutlinedTextField(query, { query = it }, label = { Text("Channel, movie or series") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onSearch(query) }) { Text("Search") } },
        dismissButton = { TextButton(onClick = { onSearch("") }) { Text("Clear") } })
}
@Composable private fun PinDialog(error: String?, onClose: () -> Unit, onSave: (String) -> Unit, setup: Boolean = false) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (setup) "Set parental PIN" else "Protected content") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (setup) "Choose a 4–8 digit PIN." else "Enter your parental PIN or the PIN supplied by your provider.", color = Muted, fontSize = 13.sp)
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, singleLine = true, label = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            if (error != null) Text(error, color = Color(0xFFFFB2D0), fontSize = 12.sp)
        } }, confirmButton = { TextButton(onClick = { onSave(pin) }) { Text(if (setup) "Save" else "Unlock") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
@Composable private fun GuideDialog(s: FormulaState, onClose: () -> Unit, onPlay: (GuideProgram) -> Unit) {
    val now = System.currentTimeMillis()/1000
    AlertDialog(onDismissRequest = onClose, title = { Text("TV guide & catch-up") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.guide.isNullOrEmpty()) item { Text("This source has not returned guide data for the selected channel.", color = Muted) }
            items(s.guide.orEmpty(), key = { "${it.id}:${it.start}" }) { program ->
                val replay = program.channel.catchupDays > 0 && program.start < now && program.start > now - program.channel.catchupDays * 86400L
                FocusTile({ if (replay) onPlay(program) }, Modifier.fillMaxWidth(), selected = program.start <= now && program.end > now) {
                    Column(Modifier.padding(14.dp)) {
                        Text(SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(program.start * 1000)), color = Purple, fontSize = 11.sp)
                        Spacer(Modifier.height(5.dp)); Text(program.title, color = Color.White, fontSize = 14.sp)
                        if (replay) Text("Play catch-up", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable private fun DetailDialog(item: Channel, loading: Boolean, favorite: Boolean, onClose: () -> Unit, onWatch: () -> Unit, onFavorite: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(item.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(model = item.poster, contentDescription = item.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(110.dp).height(155.dp).clip(RoundedCornerShape(12.dp)).background(PanelLight))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (item.isContainer) "TV SERIES" else "MOVIE", color = Purple, fontSize = 11.sp)
                    if (item.rating.isNotBlank()) Text("★ ${item.rating}", color = Color(0xFFE3C77D), fontSize = 14.sp)
                    if (item.year.isNotBlank()) Text(item.year, color = Muted, fontSize = 12.sp)
                    if (item.duration.isNotBlank()) Text(item.duration, color = Muted, fontSize = 12.sp)
                    if (item.genre.isNotBlank()) Text(item.genre, color = Muted, fontSize = 12.sp)
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple)
            if (item.summary.isNotBlank()) Text(item.summary, color = Color.White, fontSize = 13.sp)
            Action(if (favorite) "Remove favorite" else "Add favorite", Icons.Default.Star, onFavorite, Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = onWatch) { Text(if (item.isContainer) "View seasons" else "Play movie") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
}

