package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gives each DAO test a fresh in-memory Room database, with Robolectric supplying the context.
 * No create callback is added, so the master-uid triggers and default tags are absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
abstract class BaseDaoTest {

    protected lateinit var database: KashCalDatabase
    protected lateinit var context: Context

    @Before
    open fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            KashCalDatabase::class.java
        )
            .allowMainThreadQueries() // OK for tests
            .build()
    }

    @After
    open fun teardown() {
        database.close()
    }
}
