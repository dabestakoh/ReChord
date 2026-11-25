package com.music.bitchord.data

import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A single alphabet for display ordering and jump labels. */
internal class LocalAlphabet(locale: Locale, private val ignoreThe: Boolean) {
    private val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    private val labelCache = ConcurrentHashMap<Int, String>()

    fun key(name: String): String {
        val trimmed = name.trimStart()
        if (!ignoreThe || !trimmed.startsWith("The", ignoreCase = true) ||
            trimmed.getOrNull(3)?.isWhitespace() != true
        ) return trimmed
        return trimmed.drop(4).trimStart().ifEmpty { trimmed }
    }

    fun compare(left: String, right: String): Int = synchronized(collator) {
        collator.compare(key(left), key(right))
    }

    fun label(name: String): String {
        val k = key(name)
        if (k.isEmpty()) return "#"
        val c0 = k[0]
        // Fast O(1) ASCII path for 95%+ of titles (zero Normalizer, Regex, or Collator overhead).
        if (c0 in 'A'..'Z' && (k.length == 1 || Character.getType(k[1]) != Character.NON_SPACING_MARK.toInt())) {
            return ASCII_LABELS[c0 - 'A']
        }
        if (c0 in 'a'..'z' && (k.length == 1 || Character.getType(k[1]) != Character.NON_SPACING_MARK.toInt())) {
            return ASCII_LABELS[c0 - 'a']
        }
        if (c0.code < 128 && !c0.isLetter()) return "#"

        val text = Normalizer.normalize(k, Normalizer.Form.NFC)
        if (text.isEmpty()) return "#"
        val first = text.codePointAt(0)
        if (!Character.isLetter(first)) return "#"
        return labelCache.computeIfAbsent(first) { cp ->
            val original = String(Character.toChars(Character.toUpperCase(cp)))
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN -> {
                    val base = Normalizer.normalize(original, Normalizer.Form.NFD)
                        .replace(COMBINING_MARKS, "")
                    val same = synchronized(collator) { collator.compare(original, base) == 0 }
                    if (same) base else original
                }
                Character.UnicodeScript.HAN -> "漢"
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "あ"
                Character.UnicodeScript.HANGUL -> "한"
                Character.UnicodeScript.ARABIC -> "ع"
                Character.UnicodeScript.HEBREW -> "א"
                Character.UnicodeScript.DEVANAGARI -> "अ"
                Character.UnicodeScript.THAI -> "ก"
                else -> original
            }
        }
    }

    private companion object {
        val COMBINING_MARKS = Regex("\\p{M}+")
        val ASCII_LABELS = Array(26) { ('A' + it).toString() }
    }
}
