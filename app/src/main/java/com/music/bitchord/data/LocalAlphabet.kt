package com.music.bitchord.data

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** A single alphabet for display ordering and jump labels. */
internal class LocalAlphabet(locale: Locale, private val ignoreThe: Boolean) {
    private val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }

    fun key(name: String): String {
        val trimmed = name.trimStart()
        if (!ignoreThe || !trimmed.startsWith("The", ignoreCase = true) ||
            trimmed.getOrNull(3)?.isWhitespace() != true
        ) return trimmed
        return trimmed.drop(4).trimStart().ifEmpty { trimmed }
    }

    fun compare(left: String, right: String): Int = collator.compare(key(left), key(right))

    fun label(name: String): String {
        val text = Normalizer.normalize(key(name), Normalizer.Form.NFC)
        if (text.isEmpty()) return "#"
        val first = text.codePointAt(0)
        if (!Character.isLetter(first)) return "#"
        val original = String(Character.toChars(Character.toUpperCase(first)))
        return when (Character.UnicodeScript.of(first)) {
            Character.UnicodeScript.LATIN -> {
                val base = Normalizer.normalize(original, Normalizer.Form.NFD)
                    .replace(COMBINING_MARKS, "")
                if (collator.compare(original, base) == 0) base else original
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

    private companion object {
        val COMBINING_MARKS = Regex("\\p{M}+")
    }
}
