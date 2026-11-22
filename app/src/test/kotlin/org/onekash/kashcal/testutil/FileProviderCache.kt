package org.onekash.kashcal.testutil

import androidx.core.content.FileProvider

/**
 * Clears the roots [FileProvider] has cached, so the next [FileProvider.getUriForFile] builds them
 * from the calling test's own cache directory.
 *
 * FileProvider keeps its roots in a static map (`sCache`, keyed by authority) that outlives each
 * test, built from the cache directory of the first caller. Robolectric gives every test a new
 * cache directory and loads FileProvider once per sandbox (one per SDK level in a test JVM), so
 * after an earlier test on the same SDK has asked for a URI, a later test's file lies outside the
 * cached root and the lookup throws `IllegalArgumentException: Failed to find configured root`.
 * Whether that happens depends on which test classes share a JVM and their order. On a device the
 * cache directory is fixed for the process, so the app is not affected.
 *
 * Call it first in a test that needs the URI lookup to succeed. Tests that only read the written
 * file need not call it. Fails with an AssertionError naming the fallback if androidx drops or
 * retypes the field ([FileProviderCacheTest] covers the clearing).
 */
fun resetFileProviderCache() {
    val recheck = "re-check FileProvider.getPathStrategy in the current androidx core, " +
        "or pass the URI in as DebugReportExporter's contentUri parameter does"
    val field = try {
        FileProvider::class.java.getDeclaredField("sCache")
    } catch (e: NoSuchFieldException) {
        throw AssertionError("FileProvider.sCache not found; $recheck", e)
    }
    field.isAccessible = true
    val cache = field.get(null) as? MutableMap<*, *>
        ?: throw AssertionError("FileProvider.sCache is not a map; $recheck")
    // getPathStrategy reads and fills the map while holding its lock.
    synchronized(cache) { cache.clear() }
}
