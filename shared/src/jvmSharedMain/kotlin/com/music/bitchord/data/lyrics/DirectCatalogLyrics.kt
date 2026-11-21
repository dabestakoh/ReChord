package com.music.bitchord.data.lyrics

import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.abs

/** Match a recording before asking any direct catalogue for lyrics. */
internal object DirectCatalogLyrics {
    suspend fun netEase(title: String, artist: String, durationMs: Long): List<LyricLine>? {
        val hits = get("https://music.163.com/api/search/get", "s" to "$title $artist", "type" to "1", "limit" to "10")
            ?.obj("result").list("songs").mapNotNull { song ->
                val id = song.number("id") ?: return@mapNotNull null
                Hit(id, listOfNotNull(song.text("name")) + song.strings("alias"),
                    song.list("artists").mapNotNull { it.text("name") }, song.number("duration"))
            }
        val track = best(hits, title, artist, durationMs) ?: return null
        val result = get("https://music.163.com/api/song/lyric", "id" to track.id.toString(), "lv" to "1", "yv" to "1")
        return netEaseLines(result)
    }

    internal fun netEaseLines(result: JsonObject?): List<LyricLine>? =
        result.obj("yrc").text("lyric")?.let(::parseTimedLyrics)
            ?: result.obj("lrc").text("lyric")?.let(::parseTimedLyrics)

    fun qqMusic(title: String, artist: String, durationMs: Long): List<LyricLine>? {
        val results = qq("music.search.SearchCgiService", "DoSearchForQQMusicDesktop", buildJsonObject {
            put("query", "$title $artist")
            put("num_per_page", 10)
            put("page_num", 1)
            put("search_type", 0)
        }).obj("body").obj("song").list("list")
        val hits = results.mapNotNull { song ->
            val id = song.number("id") ?: return@mapNotNull null
            Hit(id, listOfNotNull(song.text("name")), song.list("singer").mapNotNull { it.text("name") },
                song.number("interval")?.times(1000), song.text("mid"))
        }
        val track = best(hits, title, artist, durationMs) ?: return null
        val result = qq("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", buildJsonObject {
            track.mid?.let { put("songMID", it) }
            put("songID", track.id)
        })
        return qqLines(result)
    }

    internal fun qqLines(result: JsonObject?): List<LyricLine>? =
        result.text("lyric")?.let { encoded ->
            runCatching { Base64.getDecoder().decode(encoded).decodeToString() }.getOrNull()
        }?.let(::parseTimedLyrics)

    suspend fun deezer(title: String, artist: String, durationMs: Long): List<LyricLine>? {
        val hits = get("https://api.deezer.com/search", "q" to "$title $artist", "limit" to "10")
            .list("data").mapNotNull { song ->
                val id = song.number("id") ?: return@mapNotNull null
                Hit(id, listOfNotNull(song.text("title"), song.text("title_short")),
                    listOfNotNull(song.obj("artist").text("name")), song.number("duration")?.times(1000))
            }
        val track = best(hits, title, artist, durationMs) ?: return null
        val query = buildJsonObject {
            put("query", "query { track(trackId: \"${track.id}\") { lyrics { text " +
                "synchronizedLines { milliseconds line } synchronizedWordByWordLines { start words { start end word } } } } }")
        }.toString()
        val token = deezerToken() ?: return null
        val result = lyricsPost("https://pipe.deezer.com/api", query, token).jsonObjectOrNull()
        if (result == null) tokenLock.withLock { if (deezerAccess?.first == token) deezerAccess = null }
        return deezerLines(result.obj("data").obj("track").obj("lyrics"))
    }

    internal fun deezerLines(result: JsonObject?): List<LyricLine>? {
        val words = result.list("synchronizedWordByWordLines").mapNotNull { line ->
            val start = line.number("start") ?: return@mapNotNull null
            val runs = line.list("words").mapNotNull { word ->
                val from = word.number("start") ?: return@mapNotNull null
                val to = word.number("end")?.takeIf { it > from } ?: return@mapNotNull null
                val text = word.text("word")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TimedRun(from, to, text)
            }
            val timed = wordsFromRuns(runs, spacingIsExplicit = false)
            timed.takeIf { it.isNotEmpty() }?.let { LyricLine(start, it.joinToString(" ") { word -> word.text }, it) }
        }
        if (words.isNotEmpty()) return words.sortedBy { it.timeMs }.withInstrumentalGaps()
        val lines = result.list("synchronizedLines").mapNotNull { line ->
            val start = line.number("milliseconds") ?: return@mapNotNull null
            val text = line.text("line")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LyricLine(start, text)
        }
        return lines.takeIf { it.isNotEmpty() }?.sortedBy { it.timeMs }?.withInstrumentalGaps()
            ?: result.text("text")?.let(::plainLyricLines)
    }

    private val tokenLock = Mutex()
    private var deezerAccess: Pair<String, Long>? = null

    private suspend fun deezerToken(): String? = tokenLock.withLock {
        val now = System.currentTimeMillis()
        deezerAccess?.takeIf { now < it.second }?.let { return@withLock it.first }
        val token = get("https://auth.deezer.com/login/anonymous", "jo" to "p", "rto" to "c")
            .text("jwt")?.takeIf { it.isNotBlank() } ?: return@withLock null
        val expiry = runCatching {
            Base64.getUrlDecoder().decode(token.split('.')[1]).decodeToString()
                .jsonObjectOrNull().number("exp")?.times(1000)
        }.getOrNull() ?: now + 5 * 60_000L
        deezerAccess = token to (expiry - 30_000L)
        token
    }

    private data class Hit(
        val id: Long,
        val titles: List<String>,
        val artists: List<String>,
        val durationMs: Long?,
        val mid: String? = null,
    )

    private fun best(hits: List<Hit>, title: String, artist: String, durationMs: Long): Hit? {
        val name = matchKey(title)
        val credited = matchKey(artist)
        if (name.isEmpty() || credited.isEmpty()) return null
        return hits.asSequence().filter { hit ->
            hit.titles.any { matchKey(it) == name } && (
                hit.artists.any { matchKey(it) == credited } ||
                    matchKey(hit.artists.joinToString(" ")) == credited
                ) && (durationMs <= 0L || hit.durationMs?.let { abs(it - durationMs) <= 10_000L } == true)
        }.minByOrNull { if (durationMs > 0L) abs((it.durationMs ?: durationMs) - durationMs) else 0L }
    }

    private fun matchKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun get(base: String, vararg query: Pair<String, String>): JsonObject? {
        val url = base.toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return lyricsGet(url.toString()).jsonObjectOrNull()
    }

    private fun qq(module: String, method: String, params: JsonObject): JsonObject? {
        val body = buildJsonObject {
            put("req", buildJsonObject {
                put("module", module)
                put("method", method)
                put("param", params)
            })
        }
        return lyricsPost("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString())
            .jsonObjectOrNull().obj("req").obj("data")
    }

    private fun String?.jsonObjectOrNull(): JsonObject? =
        this?.let { runCatching { lyricsJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject?.obj(key: String) = this?.get(key) as? JsonObject
    private fun JsonObject?.list(key: String) = (this?.get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun JsonObject?.strings(key: String) =
        (this?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.number(key: String) = (this?.get(key) as? JsonPrimitive)?.longOrNull
}
