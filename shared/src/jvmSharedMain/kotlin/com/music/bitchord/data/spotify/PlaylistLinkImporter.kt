package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

object PlaylistLinkImporter {
    data class Preview(
        val title: String,
        val songs: List<Song>,
        val missed: List<SpotifyImportTrack> = emptyList(),
        val partial: Boolean = false,
    )

    suspend fun load(link: String, language: String?, progress: (Int, Int) -> Unit): Preview {
        val url = link.trim().toHttpUrlOrNull()
        val spotify = SpotifyImporter.extractPlaylistId(link)
        if (spotify != null) {
            val (title, tracks) = SpotifyImporter.fetchPlaylistTracks(spotify)
            val (songs, missed) = SpotifyImporter.resolveToSongs(tracks, language, allowLooseMatches = false, onProgress = progress)
            return Preview(title, songs, missed, partial = tracks.size >= 100)
        }
        require(url != null && url.isHttps) { "Enter a supported HTTPS playlist link." }
        if (url.host in setOf("music.youtube.com", "www.youtube.com", "youtube.com")) {
            val id = url.queryParameter("list")?.takeIf {
                it.matches(Regex("[A-Za-z0-9_-]{2,100}")) && !it.startsWith("RD")
            } ?: error("Enter a YouTube playlist link.")
            val browseId = "VL$id"
            val first = YtMusicRepository.browseSongs(browseId).getOrThrow()
            val songs = first.songs.toMutableList()
            val seen = mutableSetOf<String>()
            var next = first.continuation
            while (next != null) {
                if (!seen.add(next) || seen.size > 200) return Preview(first.header?.title ?: "YouTube Music", songs, partial = true)
                val page = YtMusicRepository.moreSongs(next).getOrThrow()
                songs.addAll(page.songs)
                next = page.continuation
            }
            return Preview(first.header?.title ?: "YouTube Music", songs)
        }
        require(url.host == "music.apple.com" && url.pathSegments.getOrNull(1) == "playlist" &&
            url.pathSegments.last().matches(Regex("pl\\.[A-Za-z0-9-]+"))) {
            "Enter an Apple Music, Spotify, or YouTube Music playlist link."
        }
        val html = withContext(Dispatchers.IO) {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use {
                check(it.isSuccessful) { "Apple Music playlist unavailable (HTTP ${it.code})." }
                it.body?.string().orEmpty()
            }
        }
        val (title, tracks, partial) = parseApplePage(html)
        val (songs, missed) = SpotifyImporter.resolveToSongs(tracks, language, allowLooseMatches = false, onProgress = progress)
        return Preview(title, songs, missed, partial)
    }

    // Public-page parsing adapted from Mochy-Music, GPL-3.0.
    // https://github.com/realidkroo/Mochy-Music/blob/fb929ecf5b7f358efc2c82eeb0e8bd364e1a574c/app/src/main/kotlin/com/mochyy/mochymusic/importer/LinkImporter.kt
    internal fun parseApplePage(html: String): Triple<String, List<SpotifyImportTrack>, Boolean> {
        val script = Regex("""<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("Apple Music did not expose playlist data.")
        val root = Json.parseToJsonElement(script)
        val data = (root as? JsonArray) ?: ((root as? JsonObject)?.get("data") as? JsonArray)
        val page = (data?.firstOrNull() as? JsonObject)?.get("data") as? JsonObject
        val sections = (page?.get("sections") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val header = (sections.firstOrNull { it.text("itemKind") == "containerDetailHeaderLockup" }
            ?.get("items") as? JsonArray)?.firstOrNull() as? JsonObject
        val tracks = sections.filter { it.text("itemKind") == "trackLockup" }
            .flatMap { (it["items"] as? JsonArray).orEmpty() }
            .filterIsInstance<JsonObject>()
            .mapNotNull { item ->
                val title = item.text("title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val artists = (item["subtitleLinks"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    .mapNotNull { it.text("title") }
                SpotifyImportTrack(title, item.text("artistName") ?: artists.joinToString(", "), item.text("duration")?.toLongOrNull())
            }
        check(tracks.isNotEmpty()) { "No readable tracks in this Apple Music playlist." }
        return Triple(header?.text("title") ?: "Apple Music", tracks,
            (header?.text("trackCount")?.toIntOrNull() ?: tracks.size) > tracks.size)
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
