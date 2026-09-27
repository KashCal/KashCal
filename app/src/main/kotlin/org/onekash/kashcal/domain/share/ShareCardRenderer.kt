package org.onekash.kashcal.domain.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.util.sanitizeExportBaseName
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShareCardRenderer"

private const val SHARED_DIR = "shared"
private const val AUTHORITY_SUFFIX = ".fileprovider"
private const val MAX_FILENAME_LENGTH = 50

/**
 * Size of the shared PNG in pixels, the same 4:5 portrait on every device so chat clients show
 * every share equally sharp.
 *
 * The card is captured at the device's native density, so the captured size varies with the
 * device; [ShareCardRenderer.writePng] scales it to this size before writing.
 */
const val SHARE_CARD_PNG_WIDTH = 1080
const val SHARE_CARD_PNG_HEIGHT = 1350

/**
 * Scales [source] to [SHARE_CARD_PNG_WIDTH] by [SHARE_CARD_PNG_HEIGHT].
 *
 * Returns [source] itself when it already has that size. Otherwise returns a filtered scaled
 * copy and recycles [source], so the caller must not use it afterwards. Does no I/O.
 */
fun scaleToShareCardOutput(source: Bitmap): Bitmap {
    if (source.width == SHARE_CARD_PNG_WIDTH && source.height == SHARE_CARD_PNG_HEIGHT) {
        return source
    }
    val scaled = Bitmap.createScaledBitmap(
        source,
        SHARE_CARD_PNG_WIDTH,
        SHARE_CARD_PNG_HEIGHT,
        /* filter = */ true,
    )
    if (scaled !== source) {
        source.recycle()
    }
    return scaled
}

/**
 * Writes a [GraphicsLayer]'s captured card to a PNG in the cache and returns its FileProvider
 * [Uri] for sharing.
 *
 * The host composable ([org.onekash.kashcal.ui.components.share.ShareCardSheet]) records the layer
 * inside `Modifier.drawWithContent` (`graphicsLayer.record { drawContent() }`, then
 * `drawLayer(graphicsLayer)`); this class does only the I/O. The capture, at the device's native
 * density, is scaled by [scaleToShareCardOutput] so every device writes the same size.
 *
 * Bitmaps are recycled after the write. When compression fails or throws, the cache file is
 * deleted.
 */
@Singleton
class ShareCardRenderer @Inject constructor() {

    /**
     * Converts [layer] to a PNG and returns its FileProvider URI, or a failure for any exception.
     *
     * @param fileNameHint file name without extension; sanitized, with "share-card" as fallback.
     * @param layer a layer an on-screen composable has recorded into. Its draw must have run
     *   first, or `toImageBitmap` returns transparent pixels.
     */
    suspend fun writePng(
        context: Context,
        fileNameHint: String,
        layer: GraphicsLayer,
    ): Result<Uri> = runCatching {
        Log.d(TAG, "Writing share card PNG: $fileNameHint")

        // toImageBitmap() must run on the main thread (Compose readback); compression and
        // file I/O run on Dispatchers.IO to avoid jank.
        val imageBitmap = layer.toImageBitmap()
        val captured: Bitmap = imageBitmap.asAndroidBitmap()

        withContext(Dispatchers.IO) {
            val output: Bitmap = scaleToShareCardOutput(captured)
            val file = openOutputFile(context, fileNameHint)
            try {
                FileOutputStream(file).use { out ->
                    if (!output.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        if (file.exists()) file.delete()
                        error("Bitmap.compress(PNG) returned false")
                    }
                    out.flush()
                }
            } catch (t: Throwable) {
                if (file.exists()) file.delete()
                throw t
            } finally {
                // Frees native memory. When no scaling was needed, output is captured;
                // otherwise scaleToShareCardOutput already recycled captured.
                if (!output.isRecycled) output.recycle()
            }

            val authority = "${context.packageName}$AUTHORITY_SUFFIX"
            val uri = FileProvider.getUriForFile(context, authority, file)
            Log.i(TAG, "Wrote ${file.length()} bytes to $uri")
            uri
        }
    }

    private fun openOutputFile(context: Context, fileNameHint: String): File {
        val cacheDir = File(context.cacheDir, SHARED_DIR)
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, sanitize(fileNameHint))
    }

    private fun sanitize(name: String): String {
        val cleaned = sanitizeExportBaseName(name, fallback = "share-card", maxLength = MAX_FILENAME_LENGTH)
        return "${cleaned}.png"
    }
}
