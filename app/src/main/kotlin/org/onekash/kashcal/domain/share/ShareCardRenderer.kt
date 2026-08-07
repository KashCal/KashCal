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
 * Target PNG dimensions. Independent of the device's native density —
 * every device produces the same 4:5 portrait PNG so chat clients render
 * every share with the same crispness, and so widgets that previously
 * sized themselves around 1080×1350 keep working.
 *
 * The capture composable lays out at 360×450 dp at the device's native
 * density (e.g. 1080×1350 on density 3.0, 720×900 on density 2.0). The
 * renderer scales the captured bitmap to these constants before writing
 * the PNG, so the output is always exactly 1080×1350 regardless of where
 * the user runs the app.
 */
const val SHARE_CARD_PNG_WIDTH = 1080
const val SHARE_CARD_PNG_HEIGHT = 1350

/**
 * Scale the captured bitmap to the share-card target dimensions.
 *
 * If the source already matches the target, the same instance is
 * returned (no copy, no recycle). Otherwise a scaled copy is created
 * with bilinear filtering and the source is recycled to free its
 * native memory immediately.
 *
 * Pure: takes a Bitmap, returns a Bitmap. No I/O. Extracted from
 * [ShareCardRenderer.writePng] so it can be unit-tested without the
 * full rendering pipeline.
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
 * Writes a [GraphicsLayer]'s captured composable contents to a PNG and
 * returns its FileProvider [Uri] for sharing.
 *
 * The host composable (typically [org.onekash.kashcal.ui.components.share.ShareCardSheet])
 * is responsible for capturing into the [GraphicsLayer] via the documented
 * `Modifier.drawWithContent { graphicsLayer.record { drawContent() }; drawLayer(graphicsLayer) }`
 * pattern (see the Compose graphics-modifiers docs). This class only handles
 * the I/O once the layer has been populated.
 *
 * The captured bitmap is at the device's native density. We scale it to a
 * fixed 1080×1350 px output ([SHARE_CARD_PNG_WIDTH] × [SHARE_CARD_PNG_HEIGHT])
 * via [scaleToShareCardOutput] so every device produces an identically-
 * sized PNG. This is the canonical Android pattern (AOSP screenshot
 * service, social apps): render at native density, scale to a known canvas.
 *
 * Bitmaps are recycled and the OutputStream is closed via `.use { }`.
 * On compress() failure the cache file is deleted to avoid orphans.
 */
@Singleton
class ShareCardRenderer @Inject constructor() {

    /**
     * Convert the populated [layer] to a PNG and return the resulting
     * FileProvider URI.
     *
     * @param context Used for cacheDir + FileProvider authority lookup.
     * @param fileNameHint Filename without extension (sanitized internally).
     * @param layer A [GraphicsLayer] whose `record { }` has been called by
     *              an on-screen composable. Caller must ensure draw has
     *              actually run before invoking — otherwise [toImageBitmap]
     *              returns transparent pixels.
     */
    suspend fun writePng(
        context: Context,
        fileNameHint: String,
        layer: GraphicsLayer,
    ): Result<Uri> = runCatching {
        Log.d(TAG, "Writing share card PNG: $fileNameHint")

        // toImageBitmap() must run on the main thread (Compose readback);
        // PNG compression + file I/O move to Dispatchers.IO to avoid jank.
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
                // Free native memory promptly. If output === captured (no
                // scaling was needed) this also handles the captured
                // bitmap; otherwise scaleToShareCardOutput already recycled
                // captured.
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
