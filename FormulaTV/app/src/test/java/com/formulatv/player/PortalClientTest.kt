package com.formulatv.player

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class PortalClientTest {
    @Test fun handshakeUsesAuthorizedMacAndResolvesProviderCommands() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath != "/server/load.php") return MockResponse().setResponseCode(404)
                assertTrue(request.getHeader("Cookie").orEmpty().contains("mac=00:1A:79:12:34:56"))
                val action = url.queryParameter("action")
                if (action != "handshake") assertEquals("Bearer mock-token", request.getHeader("Authorization"))
                val body = when (action) {
                    "handshake" -> """{"js":{"token":"mock-token"}}"""
                    "get_profile" -> """{"js":{"status":"Active","name":"Test account"}}"""
                    "get_genres" -> """{"js":[{"id":"1","title":"News"}]}"""
                    "get_all_channels" -> """{"js":{"data":[{"id":"7","name":"News One","tv_genre_id":"1","cmd":"ffrt http://localhost/ch/7","tv_archive":1,"tv_archive_duration":3}]}}"""
                    "get_short_epg" -> """{"js":{"7":[{"id":"p","name":"Current programme","start_timestamp":${System.currentTimeMillis()/1000-60},"stop_timestamp":${System.currentTimeMillis()/1000+300}}]}}"""
                    "get_categories" -> """{"js":[{"id":"2","title":"Movies"}]}"""
                    "get_ordered_list" -> """{"js":{"total_items":1,"max_page_items":10,"data":[{"id":"11","name":"Test Movie","category_id":"2","cmd":"/media/11.mpg","series":[]}]}}"""
                    "create_link" -> """{"js":{"cmd":"ffmpeg ${server.url("stream/7.ts")}"}}"""
                    else -> """{"js":[]} """
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        try {
            val client = PortalClient()
            val content = client.connect(PortalConfig(server.url("/server/load.php").toString(), "00:1A:79:12:34:56"))
            assertEquals("News", content.liveCategories.single().title)
            assertEquals("Movies", content.vodCategories.single().title)
            assertEquals("2", content.seriesCategories.single().id)
            val channel = content.liveChannels.single()
            assertEquals(3, channel.catchupDays)
            assertEquals("Current programme", client.loadGuide(channel).single().title)
            val source = client.resolve(channel)
            assertTrue(source.url.endsWith("/stream/7.ts"))
            assertEquals("Bearer mock-token", source.headers["Authorization"])
            val movies = client.loadItems(MediaKind.VOD, "2")
            assertEquals("Test Movie", movies.single().name)
            assertFalse(movies.single().isContainer)
        } finally { server.shutdown() }
    }
}
