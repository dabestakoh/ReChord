package com.music.bitchord.data.model

object HomeSections {
    const val CAROUSEL = "carousel"
    const val CONTINUE = "continue"
    const val RELEASE = "release"
    const val SONG = "song"
    const val ARTIST = "artist"
    const val RECENTS = "recents"
    const val QUICK_PICKS = "quick-picks"
    const val MOODS = "moods"
    const val TOP_SONGS = "top-songs"
    const val TRENDING = "trending"

    val cards = listOf(CONTINUE, RELEASE, SONG, ARTIST)
    val shelves = listOf(RECENTS, QUICK_PICKS, MOODS, TOP_SONGS, TRENDING)

    fun visible(key: String, overrides: Map<String, Boolean>): Boolean =
        overrides[key] ?: (key != TOP_SONGS && key != TRENDING)

    private val editorialOrder = listOf(
        RECENTS, QUICK_PICKS, "feed:mixed for you", "feed:your daily discover",
        "feed:fresh finds, old favorites", "feed:listen again", "feed:from your library",
        "feed:from the community",
    )

    private val removedTitles = setOf(
        "music videos", "new music videos", "albums & singles", "new albums & singles",
        "albums and singles", "new albums and singles", "new releases", "bản phát hành mới", "最新リリース",
        "video âm nhạc", "video âm nhạc mới", "ミュージック ビデオ", "新しいミュージック ビデオ",
    )

    fun removed(title: String): Boolean {
        val normalized = title.trim().lowercase()
        return normalized in removedTitles || " - " in normalized || " – " in normalized || " — " in normalized
    }

    private val generalShelfTitles = setOf(
        "recents", "recently played", "quick picks", "mixed for you", "your daily discover",
        "daily discover", "fresh finds, old favorites", "listen again", "from your library",
        "from the community", "forgotten favorites", "featured playlist", "featured playlists",
        "find your mood", "top songs", "trending",
    )

    fun retainLegacyLabel(key: String, title: String): Boolean =
        key in shelves || key in editorialOrder || title.trim().lowercase() in generalShelfTitles

    private fun isArtistOrAlbumShelf(shelf: HomeShelf): Boolean {
        val title = shelf.title.trim()
        if (retainLegacyLabel(key(shelf), title)) return false
        fun isSubject(id: String?): Boolean =
            id?.let { it.startsWith("UC") || it.startsWith("MPRE") || it.startsWith("OLAK5uy") } == true
        if (isSubject(shelf.subjectBrowseId) || isSubject(shelf.moreBrowseId)) return true

        fun sameTitle(value: String?): Boolean = value?.trim()?.equals(title, ignoreCase = true) == true
        if (shelf.items.any { sameTitle(it.title) && isSubject(it.browseId) }) return true

        val tracks = shelf.items.filter { it.videoId != null }
        return tracks.isNotEmpty() && tracks.size == shelf.items.size && (
            tracks.all { item -> item.subtitle.split('•', '·').any(::sameTitle) } ||
                tracks.all { sameTitle(it.albumTitle) }
        )
    }

    fun isRecurring(shelf: HomeShelf): Boolean {
        if (removed(shelf.title) || isArtistOrAlbumShelf(shelf)) return false
        return retainLegacyLabel(key(shelf), shelf.title) || shelf.items.size > 1
    }

    fun arrange(feed: List<HomeShelf>, overrides: Map<String, Boolean>): List<HomeShelf> =
        feed.filter { isRecurring(it) && visible(key(it), overrides) }.sortedBy {
            editorialOrder.indexOf(key(it)).takeIf { rank -> rank >= 0 } ?: editorialOrder.size
        }

    fun key(shelf: HomeShelf): String {
        val title = shelf.title.trim().lowercase()
        return when (title) {
            "recents", "recently played", "gần đây", "nghe gần đây", "最近", "最近再生した曲" -> RECENTS
            "quick picks", "lựa chọn nhanh", "chọn nhanh đài phát", "クイック ミックス" -> QUICK_PICKS
            "top songs", "bài hát hàng đầu", "人気の曲", "トップ ソング" -> TOP_SONGS
            "trending", "thịnh hành", "đang thịnh hành", "急上昇" -> TRENDING
            "mixed for you", "dành riêng cho bạn", "ミックス" -> "feed:mixed for you"
            "listen again", "nghe lại", "もう一度聴く" -> "feed:listen again"
            "from your library", "từ thư viện của bạn", "ライブラリから" -> "feed:from your library"
            else -> "feed:" + (shelf.moreBrowseId?.let { "$it:${shelf.moreParams.orEmpty()}" } ?: title)
        }
    }
}
