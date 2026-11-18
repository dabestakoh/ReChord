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

    fun arrange(feed: List<HomeShelf>, overrides: Map<String, Boolean>): List<HomeShelf> =
        feed.filter { visible(key(it), overrides) }.sortedBy {
            when (key(it)) {
                RECENTS -> 0
                QUICK_PICKS -> 1
                else -> 2
            }
        }

    fun key(shelf: HomeShelf): String {
        val title = shelf.title.trim().lowercase()
        return when (title) {
            "recents", "recently played", "nghe gần đây", "最近再生した曲" -> RECENTS
            "quick picks", "lựa chọn nhanh", "chọn nhanh đài phát", "クイック ミックス" -> QUICK_PICKS
            "top songs", "bài hát hàng đầu", "人気の曲", "トップ ソング" -> TOP_SONGS
            "trending", "thịnh hành", "đang thịnh hành", "急上昇" -> TRENDING
            else -> "feed:" + (shelf.moreBrowseId?.let { "$it:${shelf.moreParams.orEmpty()}" } ?: title)
        }
    }
}
