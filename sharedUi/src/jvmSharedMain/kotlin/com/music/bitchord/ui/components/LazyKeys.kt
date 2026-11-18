package com.music.bitchord.ui.components

/** Adapted from Auris (GPL-3.0): occurrence keys preserve duplicate feed entries. */
fun <T> uniqueKeys(items: List<T>, base: (T) -> String): List<String> {
    val seen = HashMap<String, Int>()
    return items.map {
        val id = base(it)
        val occurrence = seen.getOrDefault(id, 0)
        seen[id] = occurrence + 1
        "${id.length}:$id#$occurrence"
    }
}
