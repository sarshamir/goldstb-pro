package com.formulatv.player

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class XtreamClientTest {
    @Test fun authenticatesAndLoadsSeparateCatalogsWithEncodedCredentials() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("user+name", request.requestUrl!!.queryParameter("username"))
                assertEquals("p&/word", request.requestUrl!!.queryParameter("password"))
                val body = when (request.requestUrl!!.queryParameter("action")) {
                    null -> """{"user_info":{"auth":1,"status":"Active","username":"user+name"}}"""
                    "get_live_categories" -> """[{"category_id":"1","category_name":"News"}]"""
                    "get_vod_categories" -> """[{"category_id":"2","category_name":"Movies"}]"""
                    "get_series_categories" -> """[{"category_id":"3","category_name":"Shows"}]"""
                    "get_live_streams" -> """[{"stream_id":10,"name":"News One","category_id":"1","tv_archive":1,"tv_archive_duration":3}]"""
                    "get_vod_streams" -> """[{"stream_id":21,"name":"Movie One","category_id":"2","container_extension":"mkv"}]"""
                    "get_series" -> """[{"series_id":31,"name":"Show One","category_id":"3"}]"""
                    "get_series_info" -> """{"episodes":{"2":[{"id":45,"title":"Second season","episode_num":1,"container_extension":"mp4"}],"1":[{"id":44,"title":"First season","episode_num":1,"container_extension":"mkv"}]}}"""
                    else -> "[]"
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        try {
            val client = XtreamClient()
            val content = client.connect(SourceConfig("s", "Test", SourceType.XTREAM, server.url("/").toString(), "user+name", "p&/word"))
            assertEquals("News", content.liveCategories.single().title)
            assertEquals(3, content.liveChannels.single().catchupDays)
            assertTrue(content.liveChannels.single().command.contains("user%2Bname/p%26%2Fword/10.ts"))
            val movies = client.items(MediaKind.VOD, "2")
            assertEquals(MediaKind.VOD, movies.single().kind)
            assertTrue(movies.single().command.endsWith("/21.mkv"))
            val series = client.items(MediaKind.SERIES, "3").single()
            assertTrue(series.isContainer)
            val episodes = client.episodes(series)
            assertEquals(listOf("Season 1", "Season 2"), episodes.map { it.season })
            assertTrue(episodes.first().command.endsWith("/44.mkv"))
            val replay = client.resolve(content.liveChannels.single().copy(catchupStart = 1700000000, catchupEnd = 1700003600))
            assertTrue(replay.url.contains("/timeshift/"))
            assertTrue(replay.url.endsWith("/10.ts"))
        } finally { server.shutdown() }
    }
    @Test fun rejectsUnauthorizedAccountsBeforeFetchingContent() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}"""))
        server.start()
        try {
            val result = runCatching { XtreamClient().connect(SourceConfig("s", "Test", SourceType.XTREAM, server.url("/").toString(), "u", "p")) }
            assertTrue(result.isFailure)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}

