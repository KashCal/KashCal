package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup

class ExportCalendarsModelTest {

    private val accounts = listOf(
        Account(id = 1, provider = AccountProvider.ICLOUD, email = "me@icloud.example.test", displayName = "iCloud"),
        Account(id = 2, provider = AccountProvider.CALDAV, email = "user@example.test", displayName = "Nextcloud"),
        Account(id = 3, provider = AccountProvider.CALDAV, email = "work@example.test", displayName = "Nextcloud"),
        Account(id = 4, provider = AccountProvider.ICS, email = "ics"),
        Account(id = 5, provider = AccountProvider.CONTACTS, email = "contacts"),
        Account(id = 6, provider = AccountProvider.LOCAL, email = "local"),
    )

    private fun cal(id: Long, accountId: Long, name: String, visible: Boolean = true) = Calendar(
        id = id, accountId = accountId, caldavUrl = "https://dav.example.test/$id/", displayName = name,
        color = id.toInt(), isVisible = visible,
    )

    private val calendars = listOf(
        cal(10, 1, "Home"), cal(11, 1, "Work", visible = false),
        cal(20, 2, "Personal"), cal(30, 3, "Team"),
        cal(40, 4, "US Holidays"),
        cal(50, 5, "Birthdays"), cal(51, 5, "Anniversaries"),
        cal(60, 6, "Local"), cal(61, 6, "Trips"),
    )

    private val groups = CalendarGroup.fromCalendarsAndAccounts(
        calendars, accounts, localLabel = "Offline", icsLabel = "Calendar Feeds"
    )
    private val logins = accounts.associate { it.id to it.email }

    private fun build(counts: Map<Long, Int>? = mapOf(10L to 312, 50L to 57)) =
        buildExportCalendarSections(groups, counts, logins, contactsHeader = "Contacts")

    @Test
    fun `sections follow the drawer, accounts then contacts then offline, with no device section`() {
        assertEquals(
            listOf("Calendar Feeds", "iCloud", "Nextcloud", "Nextcloud", "Contacts", "Offline"),
            build().map { it.header },
        )
    }

    @Test
    fun `every calendar is listed, hidden ones included, sorted as the drawer sorts them`() {
        val sections = build()
        assertEquals(listOf("Home", "Work"), sections.single { it.header == "iCloud" }.rows.map { it.name })
        assertEquals(listOf("Anniversaries", "Birthdays"), sections.single { it.header == "Contacts" }.rows.map { it.name })
        assertEquals(listOf("Local", "Trips"), sections.single { it.header == "Offline" }.rows.map { it.name })
    }

    @Test
    fun `a header two accounts share names each account's login under it`() {
        val sections = build()
        assertEquals(
            listOf("user@example.test", "work@example.test"),
            sections.filter { it.header == "Nextcloud" }.map { it.subheader },
        )
        assertNull(sections.single { it.header == "iCloud" }.subheader)
        assertNull(sections.single { it.header == "Offline" }.subheader)
    }

    @Test
    fun `counts come from the map, missing ones are zero, and an unloaded map leaves them unknown`() {
        val loaded = build().flatMap { it.rows }.associate { it.id to it.eventCount }
        assertEquals(312, loaded[10])
        assertEquals(0, loaded[11])
        assertEquals(57, loaded[50])

        assertEquals(setOf<Int?>(null), build(counts = null).flatMap { it.rows }.map { it.eventCount }.toSet())
    }

    @Test
    fun `rows carry each calendar's color and section keys are unique`() {
        val sections = build()
        assertEquals(20, sections.flatMap { it.rows }.single { it.id == 20L }.color)
        assertEquals(sections.size, sections.map { it.key }.toSet().size)
    }
}
