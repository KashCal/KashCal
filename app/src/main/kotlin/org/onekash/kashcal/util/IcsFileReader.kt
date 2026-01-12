package org.onekash.kashcal.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Utility for reading ICS calendar files from content:// or file:// URIs.
 *
 * Uses ContentResolver for secure file access per Android best practices.
 * @see <a href="https://developer.android.com/training/secure-file-sharing/retrieve-info">Android Docs</a>
 */
object IcsFileReader {

    /**
     * Read ICS content from a content:// or file:// URI.
     *
     * @param context Application context for ContentResolver
     * @param uri The content:// or file:// URI to read from
     * @return Result containing ICS content string, or failure with exception
     */
    fun readIcsContent(context: Context, uri: Uri): Result<String> {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: return Result.failure(IOException("Could not open file"))

            // Basic validation - must contain VCALENDAR
            if (!content.contains("BEGIN:VCALENDAR")) {
                return Result.failure(IllegalArgumentException("Invalid ICS file: missing VCALENDAR"))
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
     * @param context Application context for ContentResolver
     * @param uri The content:// URI to query
     * @return Filename if available, null otherwise
     */
    fun getFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
