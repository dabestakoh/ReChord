package com.music.bitchord.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future

/** Media3's default loader handles ordinary artwork. Only localcover uses ReChord's loader. */
@UnstableApi
internal class SessionArtworkLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val delegate: BitmapLoader = DataSourceBitmapLoader(context),
) : BitmapLoader by delegate {

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != "localcover") return delegate.loadBitmap(uri)
        return scope.future(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(uri.toString())
                .size(256)
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            (result as? SuccessResult)?.image?.toBitmap()
                ?: error("Local media cover could not be loaded")
        }
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val uri = metadata.artworkUri
        return if (metadata.artworkData == null && uri?.scheme == "localcover") {
            loadBitmap(uri)
        } else {
            delegate.loadBitmapFromMetadata(metadata)
        }
    }
}
