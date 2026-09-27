package org.onekash.kashcal.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.onekash.kashcal.di.IoDispatcher
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads ICS files from content:// or file:// URIs through the ContentResolver.
 *
 * Every method is main-safe: blocking I/O runs on the injected IO dispatcher.
 *
 * @see <a href="https://developer.android.com/training/secure-file-sharing/retrieve-info">docs</a>
 */
@Singleton
class IcsFileReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Reads the file at [uri] as text.
     *
     * Fails when the stream can't be opened, the text has no `BEGIN:VCALENDAR`, or the read
     * throws an [IOException] or [SecurityException]; other exceptions propagate.
     */
    suspend fun readIcsContent(uri: Uri): Result<String> = withContext(ioDispatcher) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: return@withContext Result.failure(IOException("Could not open file"))

            // A presence check only; anything with the marker passes.
            if (!content.contains("BEGIN:VCALENDAR")) {
                return@withContext Result.failure(IllegalArgumentException("Invalid ICS file: missing VCALENDAR"))
            }

            Result.success(content)
        } catch (e: FileNotFoundException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SecurityException) {
            Result.failure(e)
        }
    }

    /**
     * Returns the file's `OpenableColumns.DISPLAY_NAME`, or null when the provider has none or
     * the query fails.
     */
    suspend fun getFileName(uri: Uri): String? = withContext(ioDispatcher) {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
