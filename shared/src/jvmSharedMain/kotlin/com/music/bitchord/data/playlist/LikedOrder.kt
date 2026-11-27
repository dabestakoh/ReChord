package com.music.bitchord.data.playlist

import com.music.bitchord.data.model.Song

/**
 * Applies a locally saved custom order ([customOrder] of `videoId`s) to the
 * Liked Music (`VLLM`) track list while keeping YouTube Music's natural order
 * intact in the underlying cache.
 *
 * - If [customOrder] is null or empty, returns [songs] unchanged.
 * - Any newly liked song (its `videoId` is absent from [customOrder]) appears
 *   at the top in YouTube Music's order so new likes are never buried.
 * - Any unliked song (present in [customOrder] but absent from [songs]) is
 *   cleanly dropped.
 */
fun applyLikedCustomOrder(
    songs: List<Song>,
    customOrder: List<String>?,
): List<Song> {
    if (customOrder.isNullOrEmpty() || songs.isEmpty()) return songs
    val indexById = HashMap<String, Int>(customOrder.size * 2)
    customOrder.forEachIndexed { index, id ->
        if (id.isNotBlank()) indexById.putIfAbsent(id, index)
    }
    if (indexById.isEmpty()) return songs

    val newSongs = ArrayList<Song>()
    val ordered = ArrayList<Pair<Int, Song>>(songs.size)
    for (song in songs) {
        val rank = indexById[song.videoId]
        if (rank == null) {
            newSongs.add(song)
        } else {
            ordered.add(rank to song)
        }
    }
    if (ordered.isEmpty()) return songs
    ordered.sortBy { it.first }

    val result = ArrayList<Song>(newSongs.size + ordered.size)
    result.addAll(newSongs)
    for ((_, song) in ordered) result.add(song)
    return result
}

/**
 * Removes duplicate `videoId` entries while preserving the first occurrence.
 * Used before presenting Liked Music (`VLLM`) inside the reorder list where
 * `setVideoId` may be null and `LazyColumn` requires unique item keys.
 */
fun dedupeSongsByVideoId(songs: List<Song>): List<Song> {
    if (songs.size <= 1) return songs
    val seen = HashSet<String>(songs.size * 2)
    val unique = ArrayList<Song>(songs.size)
    for (song in songs) {
        if (seen.add(song.videoId)) unique.add(song)
    }
    return unique
}
