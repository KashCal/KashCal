package org.onekash.kashcal.testutil

import java.io.File

/**
 * Resolves the project root for tests that read source files directly (XML
 * descriptors, baselines, fixtures). Walks up from `user.dir` until it finds
 * a directory containing both `app/` and `docs/` siblings, so the test
 * survives whether Gradle launches it from the module dir (`app/`) or the
 * repo root.
 */
internal fun resolveProjectRoot(): File {
    val userDir = File(System.getProperty("user.dir") ?: ".")
    var candidate: File? = userDir
    while (candidate != null) {
        if (File(candidate, "app").isDirectory && File(candidate, "docs").isDirectory) {
            return candidate
        }
        candidate = candidate.parentFile
    }
    return userDir.parentFile ?: userDir
}
