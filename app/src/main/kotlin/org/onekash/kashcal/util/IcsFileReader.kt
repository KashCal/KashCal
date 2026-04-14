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
 * Utility for reading ICS calendar files from content:// or file:// URIs.
 *
 * Uses ContentResolver for secure file access per Android best practices.
 * All I/O operations are main-safe via internal dispatcher switching.
 *
 * @see <a href="https://developer.android.com/training/secure-file-sharing/retrieve-info">Android Docs</a>
 */
@Singleton
class IcsFileReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Read ICS content from a content:// or file:// URI.
     *
     * Main-safe: internally switches to IO dispatcher for blocking I/O.
     *
     * @param uri The content:// or file:// URI to read from
     * @return Result containing ICS content string, or failure with exception
     */
    suspend fun readIcsContent(uri: Uri): Result<String> = withContext(ioDispatcher) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: return@withContext Result.failure(IOException("Could not open file"))

            // Basic validation - must contain VCALENDAR
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
     * Get display name of the file from its URI.
     *
     * Main-safe: internally switches to IO dispatcher for blocking I/O.
     *
     * @param uri The content:// URI to query
     * @return Filename if available, null otherwise
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
