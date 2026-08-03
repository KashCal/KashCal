package org.onekash.kashcal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the UI layer's persistence boundary.
 *
 * The UI layer (Compose screens, ViewModels, UI models) must go through the
 * domain layer (EventCoordinator/EventReader) for all data access. It must
 * never reach directly for Room — neither DAOs nor Room's own APIs — because
 * that couples presentation to the storage schema and lets DAO access creep
 * back into ViewModels.
 *
 * This is a source-scanning invariant, not a runtime one: it reads the actual
 * `.kt` files under `ui/` and asserts none of them import a forbidden symbol.
 * It passes today (the layering is clean) and exists to fail loudly if a future
 * change reintroduces the coupling — the same "guard the boundary so it can't
 * silently regress" idea as MainActivityIntentFilterTest.
 *
 * Note the asymmetry: the domain layer (EventReader, EventWriter, …) IS allowed
 * to import Room — it is the DAO gateway and owns @Transaction boundaries. Only
 * the UI layer is fenced off here.
 */
class UiLayerPersistenceBoundaryTest {

    private companion object {
        /** Import prefixes the UI layer must never reference. */
        val FORBIDDEN_IMPORT_PREFIXES = listOf(
            "androidx.room",                             // Room runtime APIs
            "org.onekash.kashcal.data.db.dao",           // Room DAOs
        )

        /** Resolve the ui/ source root regardless of where the test runner starts. */
        fun uiSourceRoot(): File {
            val relative = "src/main/kotlin/org/onekash/kashcal/ui"
            val candidates = listOf(
                File(relative),                 // working dir = app module
                File("app/$relative"),          // working dir = repo root
            )
            return candidates.firstOrNull { it.isDirectory }
                ?: error(
                    "Could not locate the ui/ source root from working dir " +
                        "'${File(".").absolutePath}'. Tried: " +
                        candidates.joinToString { it.path }
                )
        }
    }

    @Test
    fun `ui layer does not import Room DAOs or Room runtime`() {
        val root = uiSourceRoot()
        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        // Sanity: make sure we actually scanned the layer, not an empty/wrong dir.
        assertTrue(
            "Expected to scan the ui/ source tree but found no .kt files under ${root.path}",
            ktFiles.isNotEmpty()
        )

        val violations = mutableListOf<String>()
        for (file in ktFiles) {
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    if (!trimmed.startsWith("import ")) return@forEachIndexed
                    val imported = trimmed.removePrefix("import ").trim()
                    if (FORBIDDEN_IMPORT_PREFIXES.any {
                            imported == it || imported.startsWith("$it.")
                        }
                    ) {
                        violations += "${file.path}:${index + 1}  $trimmed"
                    }
                }
            }
        }

        assertTrue(
            buildString {
                appendLine(
                    "UI layer must not touch Room directly — route data access through the " +
                        "domain layer (EventCoordinator/EventReader). Offending imports:"
                )
                violations.forEach { appendLine("  $it") }
            },
            violations.isEmpty()
        )
    }
}
