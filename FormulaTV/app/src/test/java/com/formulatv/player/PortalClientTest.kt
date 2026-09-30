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
    @Test fun largeClassicCatalogLoadsOnePageAndTraversesSeasons() = runBlocking {
        val server = MockWebServer()
        var catalogRequests = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val u = request.requestUrl!!
                val body = when (u.queryParameter("action")) {
                    "handshake" -> """{"js":{"token":"mock-token"}}"""
                    "get_profile" -> """{"js":{"blocked":"0"}}"""
                    "get_genres", "get_all_channels" -> """{"js":[]}"""
                    "get_categories" -> if (u.queryParameter("type") == "series") """{"js":false}""" else """{"js":[{"id":"*","title":"All"},{"id":"20","title":"ENGLISH | TV SHOWS"},{"id":"2","title":"Movies"}]}"""
                    "get_ordered_list" -> when {
                        u.queryParameter("episode_id") == "e1" -> """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"f1","is_file":true,"cmd":"/media/file_f1.mpg"}]}}"""
                        u.queryParameter("movie_id") == "show" && u.queryParameter("season_id") == "0" -> """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"s1","season_number":"1","is_season":true}]}}"""
                        u.queryParameter("movie_id") == "show" -> """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"e1","name":"The Beginning","series_number":"1","is_episode":true}]}}"""
                        u.queryParameter("category") == "20" -> """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"show","name":"Example Show","category_id":"20","is_series":"1","series":[]}]}}"""
                        else -> { catalogRequests++; val id = u.queryParameter("p"); """{"js":{"total_items":191575,"max_page_items":1,"data":[{"id":"$id","name":"Movie $id","category_id":"2","series":[]}]}}""" }
                    }
                    "create_link" -> {
                        assertEquals("/media/file_f1.mpg", u.queryParameter("cmd"))
                        """{"js":{"cmd":"ffmpeg ${server.url("episode.mp4")}"}}"""
                    }
                    else -> """{"js":false}"""
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        try {
            val client = PortalClient()
            val content = client.connect(PortalConfig(server.url("/server/load.php").toString(), "00:1A:79:12:34:56"))
            assertEquals("20", content.seriesCategories.single().id)
            assertEquals("1", client.loadItems(MediaKind.VOD, "2").single().id)
            assertEquals(1, catalogRequests)
            assertTrue(client.hasMore(MediaKind.VOD, "2"))
            assertEquals(listOf("1", "2"), client.loadMoreItems(MediaKind.VOD, "2").map { it.id })
            assertEquals(2, catalogRequests)
            val show = client.loadItems(MediaKind.SERIES, "20").single()
            assertTrue(show.isContainer)
            val episode = client.loadEpisodes(show).single()
            assertEquals("The Beginning", episode.name)
            assertEquals("Season 1", episode.season)
            assertEquals(1, episode.episodeNumber)
            assertEquals("e1", episode.portalEpisodeId)
            assertTrue(client.resolve(episode).url.endsWith("/episode.mp4"))
        } finally { server.shutdown() }
    }

}

