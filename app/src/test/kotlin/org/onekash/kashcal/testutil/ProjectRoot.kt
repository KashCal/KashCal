package org.onekash.kashcal.testutil

import java.io.File

/**
 * Resolves the project root for tests that read source files directly (XML
 * descriptors, baselines, fixtures). Walks up from `user.dir` to the first
 * directory holding both an `app/` directory and `settings.gradle.kts`, so the
 * test works whether Gradle launches it from the module dir (`app/`) or the repo
 * root. Falls back to the parent of `user.dir` (or `user.dir` itself) when none
 * matches.
 */
internal fun resolveProjectRoot(): File {
    val userDir = File(System.getProperty("user.dir") ?: ".")
    var candidate: File? = userDir
    while (candidate != null) {
        if (File(candidate, "app").isDirectory && File(candidate, "settings.gradle.kts").isFile) {
            return candidate
        }
        candidate = candidate.parentFile
    }
    return userDir.parentFile ?: userDir
}
