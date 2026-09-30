package com.formulatv.player

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FormulaUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun visible(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) { rule.waitUntil(40000) { visible(text) } }
    private fun input(label: String, value: String) { rule.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performClick().performTextReplacement(value) }
    private fun shot(name: String) {
        rule.waitForIdle()
        val directory = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        check(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory, "$name.png")))
    }
    @Test fun setupBrowsePlaybackSwitchingAndLandscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val video = instrumentation.context.assets.open("test-video.mp4").use { it.readBytes() }
        val video2 = instrumentation.context.assets.open("test-video2.mp4").use { it.readBytes() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath.endsWith(".mp4")) {
                    val all = if (url.encodedPath.contains("/11.")) video2 else video
                    val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    val bytes = all.copyOfRange(start.coerceIn(0, all.size), all.size)
                    return MockResponse().setResponseCode(if (start > 0) 206 else 200)
                        .addHeader("Content-Type", "video/mp4").addHeader("Accept-Ranges", "bytes")
                        .addHeader("Content-Range", "bytes $start-${all.size-1}/${all.size}")
                        .setBody(Buffer().write(bytes))
                }
                val body = when (url.queryParameter("action")) {
                    null -> """{"user_info":{"auth":1,"status":"Active","username":"Test account"}}"""
                    "get_live_categories" -> """[{"category_id":"1","category_name":"News & Learning"}]"""
                    "get_vod_categories" -> """[{"category_id":"2","category_name":"Documentaries"}]"""
                    "get_series_categories" -> """[{"category_id":"3","category_name":"Education"}]"""
                    "get_live_streams" -> """[{"stream_id":10,"name":"World News","category_id":"1","container_extension":"mp4"},{"stream_id":11,"name":"Science Lab","category_id":"1","container_extension":"mp4"}]"""
                    "get_short_epg" -> """{"epg_listings":[]}"""
                    "get_vod_streams" -> """[{"stream_id":21,"name":"Our Blue Planet","category_id":"2","container_extension":"mp4","rating":"8.4"},{"stream_id":22,"name":"A Journey Through Space","category_id":"2","container_extension":"mp4","rating":"8.1"}]"""
                    "get_vod_info" -> """{"info":{"plot":"Explore the oceans, forests and wild places of our world. This is a local test fixture.","rating":"8.4","releasedate":"2024-01-01","duration":"01:30:00","genre":"Documentary"}}"""
                    "get_series" -> """[{"series_id":31,"name":"The Curious Mind","category_id":"3","rating":"8.7"}]"""
                    "get_series_info" -> """{"info":{"plot":"A series about science and discovery.","rating":"8.7","genre":"Education"},"episodes":{"1":[{"id":44,"title":"The Beginning","episode_num":1,"container_extension":"mp4"},{"id":45,"title":"New Discoveries","episode_num":2,"container_extension":"mp4"}]}}"""
                    else -> "[]"
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        try {
            waitFor("Add content source"); shot("01-welcome")
            rule.onNodeWithText("Add content source").performClick()
            rule.onNodeWithText("Stalker", useUnmergedTree = true).performClick()
            waitFor("MAC address"); shot("02-stalker-setup")
            rule.onNodeWithText("Xtream", useUnmergedTree = true).performClick()
            input("Source name", "My TV source")
            input("Server URL", server.url("/").toString())
            input("Username", "test"); input("Password", "test")
            UiDevice.getInstance(instrumentation).pressBack()
            rule.onNodeWithText("Save & connect").performClick()
            waitFor("Welcome to Formula TV"); shot("03-home-phone")
            rule.onAllNodesWithText("Live TV").onLast().performClick()
            waitFor("World News"); rule.onNodeWithText("World News").performClick()
            waitFor("Playing"); shot("04-live-purple")
            rule.onNodeWithText("Science Lab").performClick()
            waitFor("Playing"); rule.waitForIdle(); Thread.sleep(800); shot("05-live-switched-blue")
            rule.onAllNodesWithText("Movies").onLast().performClick()
            waitFor("Our Blue Planet"); shot("06-movies-phone")
            rule.onNodeWithText("Our Blue Planet").performClick()
            waitFor("Documentary"); shot("07-movie-details")
            rule.onNodeWithText("Close").performClick()
            rule.onAllNodesWithText("Series").onLast().performClick()
            waitFor("The Curious Mind"); rule.onNodeWithText("The Curious Mind").performClick()
            rule.onNodeWithText("View episodes").performClick()
            waitFor("The Beginning"); shot("08-series-episodes")
            rule.onAllNodesWithText("Live TV").onLast().performClick()
            rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitForIdle(); Thread.sleep(1800); shot("09-live-landscape")
            rule.onAllNodesWithText("Home").onLast().performClick()
            waitFor("Welcome to Formula TV"); shot("10-home-landscape")
        } finally { server.shutdown() }
    }
}

