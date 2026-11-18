package com.music.bitchord.playback

import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.playerArtPx
import com.music.bitchord.shouldTrimUiMemory
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StartupPersistenceTest {
    private lateinit var prefs: FakeSharedPreferences
    @Before fun setUp() {
        prefs = FakeSharedPreferences()
        LastPlayed.initForTesting(prefs)
    }
    private fun song(id: String) = Song(videoId = id, title = id, artist = "artist", thumbnailUrl = null)

    @Test fun `routine queue then position writes restore in order with metadata intact`() {
        val tracks = listOf(song("one"), song("two").copy(albumName = "Album", durationText = "4:12"))
        LastPlayed.saveQueue(tracks, 1)
        LastPlayed.savePlaybackState(1, 42_000L)
        val restored = LastPlayed.load()!!
        assertEquals(tracks, restored.songs)
        assertEquals(1, restored.index)
        assertEquals(42_000L, restored.positionMs)
    }

    private fun withBlockedWriter(action: () -> Unit) {
        val writer = LastPlayed::class.java.getDeclaredField("writer").apply { isAccessible = true }
            .get(LastPlayed) as ExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        writer.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try { action() } finally { release.countDown() }
    }

    @Test fun `explicit queue replacement wins over an older blocked queue and progress write`() {
        withBlockedWriter {
            LastPlayed.saveQueue(listOf(song("old")), 0)
            LastPlayed.savePlaybackState(0, 99_000L)
            LastPlayed.saveQueueImmediately(listOf(song("new")), 0, 12_000L)
        }
        val restored = LastPlayed.load()!!
        assertEquals("new", restored.songs.single().videoId)
        assertEquals(12_000L, restored.positionMs)
    }

    @Test fun `clearing a queue prevents pending work from restoring it`() {
        withBlockedWriter { LastPlayed.saveQueue(listOf(song("old")), 0); LastPlayed.clearImmediately() }
        assertNull(LastPlayed.load())
    }

    @Test fun `slow writer does not block restoration and rapid updates coalesce`() {
        withBlockedWriter {
            repeat(200) {
                LastPlayed.saveQueue(listOf(song("track-$it")), 0)
                LastPlayed.savePlaybackState(0, it.toLong())
            }
            // This call is deliberately made while the writer is still blocked.
            val restored = LastPlayed.load()!!
            assertEquals("track-199", restored.songs.single().videoId)
            assertEquals(199L, restored.positionMs)
        }
        LastPlayed.awaitWritesForTesting()
        val encoded = prefs.getString("queue", null)!!
        assertTrue(encoded.contains("track-199"))
    }

    @Test fun `artwork follows display pixels with bounds and a safe geometry fallback`() {
        assertEquals(720, playerArtPx(360f, 2f))
        assertEquals(1080, playerArtPx(360f, 3f))
        assertEquals(1200, playerArtPx(1000f, 2f))
        assertEquals(640, playerArtPx(200f, 1f))
        assertEquals(1200, playerArtPx(Float.NaN, 1f))
        assertEquals(1200, playerArtPx(0f, 2f))
    }

    @Test fun `ordinary app switching preserves disposable artwork caches`() {
        assertFalse(shouldTrimUiMemory(20)) // UI_HIDDEN
        assertFalse(shouldTrimUiMemory(5)) // RUNNING_MODERATE
        assertTrue(shouldTrimUiMemory(10)) // RUNNING_LOW
        assertTrue(shouldTrimUiMemory(15)) // RUNNING_CRITICAL
        assertTrue(shouldTrimUiMemory(40)) // BACKGROUND
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = data[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { if (key != null) removed += key; return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) prefs.data.clear()
                removed.forEach(prefs.data::remove)
                pending.forEach { (key, value) ->
                    if (value == null) prefs.data.remove(key) else prefs.data[key] = value
                }
            }
        }
    }
}
