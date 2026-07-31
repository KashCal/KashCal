package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Category
import org.onekash.kashcal.data.repository.CategoryRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.ui.components.category.colorForTag
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [TagsViewModel] — the state holder behind the tag-management screen.
 * Exercises the observed tag list (sorted, colors resolved) and the mutating
 * actions. Color, delete, and undo delegate to [CategoryRepository]; rename goes
 * through [EventCoordinator] so it can propagate to the server (the DAO cascade
 * itself is covered under EventWriter/CategoryDao). Backed by a real in-memory
 * Room DB for the metadata surface and a mock coordinator for rename routing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TagsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: KashCalDatabase
    private lateinit var repository: CategoryRepository
    private lateinit var coordinator: EventCoordinator

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CategoryRepository(database.categoryDao())
        coordinator = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = TagsViewModel(repository, coordinator)

    @Test
    fun `empty table yields no tags`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.tags.value.isEmpty())
    }

    @Test
    fun `tags are sorted by name and expose resolved colors`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))
        database.categoryDao().insertIgnore(Category(name = "Gym", color = null, lastUsedAt = 200L))

        val tags = viewModel().tags.first { it.isNotEmpty() }

        assertEquals(listOf("Gym", "Work"), tags.map { it.name })
        // Custom color wins for Work; Gym falls back to the hash color.
        assertEquals(0xFF4457C9.toInt(), tags.first { it.name == "Work" }.color)
        assertEquals(colorForTag("Gym"), tags.first { it.name == "Gym" }.color)
    }

    @Test
    fun `an unrecolored tag reports no custom color`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Gym", color = null, lastUsedAt = 200L))

        val tags = viewModel().tags.first { it.isNotEmpty() }

        assertFalse(tags.single().hasCustomColor)
    }

    @Test
    fun `a recolored tag reports a custom color`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))

        val tags = viewModel().tags.first { it.isNotEmpty() }

        assertTrue(tags.single().hasCustomColor)
    }

    @Test
    fun `onSetColor applies a custom color`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Work", color = null, lastUsedAt = 100L))
        val vm = viewModel()
        vm.tags.first { it.isNotEmpty() }

        vm.onSetColor("Work", 0xFFE04A8E.toInt())

        // Await the reactive list rather than reading the DAO directly: Room commits
        // the write on its own executor, so the observed re-emit is the commit signal.
        val work = vm.tags.first { list -> list.any { it.name == "Work" && it.hasCustomColor } }
            .first { it.name == "Work" }
        assertEquals(0xFFE04A8E.toInt(), work.color)
    }

    @Test
    fun `onRename routes through the coordinator so the rename reaches the server`() = runTest {
        val vm = viewModel()

        vm.onRename("Work", "Job")
        advanceUntilIdle()

        // Rename goes through the domain layer (not the data-layer repository) so
        // affected syncable events are marked dirty and re-uploaded — the whole
        // point of S5. The cascade itself is proven at the EventWriter/DAO level.
        coVerify(exactly = 1) { coordinator.renameTag("Work", "Job") }
    }

    @Test
    fun `onSetColor never touches the coordinator so a recolor triggers no sync`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Work", color = null, lastUsedAt = 100L))
        val vm = viewModel()
        vm.tags.first { it.isNotEmpty() }

        vm.onSetColor("Work", 0xFFE04A8E.toInt())
        vm.tags.first { list -> list.any { it.name == "Work" && it.hasCustomColor } }

        // A color is local-only metadata (CATEGORIES carries no color on the wire),
        // so recoloring must never enter the sync path.
        coVerify(exactly = 0) { coordinator.renameTag(any(), any()) }
    }

    @Test
    fun `onDelete removes the tag`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Temp", color = null, lastUsedAt = 100L))
        val vm = viewModel()
        vm.tags.first { it.isNotEmpty() }

        vm.onDelete("Temp")

        // Empty re-emit is the delete's commit signal — the DAO read below is now safe.
        vm.tags.first { it.isEmpty() }
        assertNull(database.categoryDao().getByName("Temp"))
    }

    @Test
    fun `onUndoDelete restores the deleted tag with its color and recency`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))
        val vm = viewModel()
        vm.tags.first { it.isNotEmpty() }

        vm.onDelete("Work")
        vm.tags.first { it.isEmpty() }

        vm.onUndoDelete()

        // The tag reappears once the restore commits; then read the row for lastUsedAt,
        // which the UI item doesn't expose.
        vm.tags.first { it.isNotEmpty() }
        val restored = database.categoryDao().getByName("Work")!!
        assertEquals(0xFF4457C9.toInt(), restored.color)
        assertEquals(100L, restored.lastUsedAt)
    }

    @Test
    fun `onUndoDelete does nothing when no delete is pending`() = runTest {
        database.categoryDao().insertIgnore(Category(name = "Work", color = null, lastUsedAt = 100L))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onUndoDelete()
        advanceUntilIdle()

        // No prior delete — the existing row is untouched and nothing is re-inserted.
        assertEquals(1, vm.tags.first { it.isNotEmpty() }.size)
    }
}
