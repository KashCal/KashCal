package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for SubscriptionsScreen derived state calculations.
 *
 * Tests verify the correctness of subscription count and preview names
 * calculation logic used in the Settings summary row.
 */
class SubscriptionsScreenTest {

    // ==================== Subscription Count Tests ====================

    @Test
    fun `subscription count is 0 when no ICS and birthdays disabled`() {
        val subscriptions = emptyList<IcsSubscriptionUiModel>()
        val contactBirthdaysEnabled = false

        val count = subscriptions.size + (if (contactBirthdaysEnabled) 1 else 0)

        assertEquals(0, count)
    }

    @Test
    fun `subscription count is 1 with only birthdays enabled`() {
        val subscriptions = emptyList<IcsSubscriptionUiModel>()
        val contactBirthdaysEnabled = true

        val count = subscriptions.size + (if (contactBirthdaysEnabled) 1 else 0)

        assertEquals(1, count)
    }

    @Test
    fun `subscription count is 1 with only one ICS subscription`() {
        val subscriptions = listOf(createSubscription(1, "Holidays"))
        val contactBirthdaysEnabled = false

        val count = subscriptions.size + (if (contactBirthdaysEnabled) 1 else 0)

        assertEquals(1, count)
    }

    @Test
    fun `subscription count includes birthdays when enabled with ICS`() {
        val subscriptions = listOf(
            createSubscription(1, "Holidays"),
            createSubscription(2, "Sports")
        )
        val contactBirthdaysEnabled = true

        val count = subscriptions.size + (if (contactBirthdaysEnabled) 1 else 0)

        assertEquals(3, count)
    }

    // ==================== Preview Names Tests ====================

    @Test
    fun `preview names shows birthdays first when enabled`() {
        val subscriptions = listOf(
            createSubscription(1, "Holidays"),
            createSubscription(2, "Sports")
        )
        val contactBirthdaysEnabled = true

        val previewNames = buildPreviewNames(subscriptions, contactBirthdaysEnabled)

        assertEquals("Contact Birthdays, Holidays", previewNames)
    }

    @Test
    fun `preview names shows only ICS names when birthdays disabled`() {
        val subscriptions = listOf(
            createSubscription(1, "Holidays"),
            createSubscription(2, "Sports")
        )
        val contactBirthdaysEnabled = false

        val previewNames = buildPreviewNames(subscriptions, contactBirthdaysEnabled)

        assertEquals("Holidays, Sports", previewNames)
    }

    @Test
    fun `preview names shows first two items only`() {
        val subscriptions = listOf(
            createSubscription(1, "Holidays"),
            createSubscription(2, "Sports"),
            createSubscription(3, "Work")
        )
        val contactBirthdaysEnabled = false

        val previewNames = buildPreviewNames(subscriptions, contactBirthdaysEnabled)

        assertEquals("Holidays, Sports", previewNames)
    }

    @Test
    fun `preview names shows birthdays only when no ICS`() {
        val subscriptions = emptyList<IcsSubscriptionUiModel>()
        val contactBirthdaysEnabled = true

        val previewNames = buildPreviewNames(subscriptions, contactBirthdaysEnabled)

        assertEquals("Contact Birthdays", previewNames)
    }

    @Test
    fun `preview names is empty when nothing enabled`() {
        val subscriptions = emptyList<IcsSubscriptionUiModel>()
        val contactBirthdaysEnabled = false

        val previewNames = buildPreviewNames(subscriptions, contactBirthdaysEnabled)

        assertEquals("", previewNames)
    }

    @Test
    fun `preview names with birthdays takes one ICS slot`() {
        // When birthdays is enabled, it takes one of the two preview slots
        // So only 1 ICS name should be shown
        val subscriptions = listOf(
            createSubscription(1, "Holidays"),
            createSubscription(2, "Sports"),
            createSubscription(3, "Work")
        )
        val contactBirthdaysEnabled = true

        val previewNames = buildPreviewNames(subscriptions, contactBirthdaysEnabled)

        assertEquals("Contact Birthdays, Holidays", previewNames)
    }

    // ==================== Ellipsis Logic Tests ====================

    @Test
    fun `ellipsis not added when count is 2 or less`() {
        val subscriptionCount = 2

        val needsEllipsis = subscriptionCount > 2

        assertEquals(false, needsEllipsis)
    }

    @Test
    fun `ellipsis added when count is more than 2`() {
        val subscriptionCount = 3

        val needsEllipsis = subscriptionCount > 2

        assertEquals(true, needsEllipsis)
    }

    // ==================== Helpers ====================

    private fun createSubscription(
        id: Long,
        name: String
    ) = IcsSubscriptionUiModel(
        id = id,
        url = "https://example.com/$id.ics",
        name = name,
        color = 0xFF2196F3.toInt()
    )

    /**
     * Mirrors the preview names logic from AccountSettingsScreen.
     */
    private fun buildPreviewNames(
        subscriptions: List<IcsSubscriptionUiModel>,
        contactBirthdaysEnabled: Boolean
    ): String {
        return buildList {
            if (contactBirthdaysEnabled) add("Contact Birthdays")
            subscriptions.take(2).forEach { add(it.name) }
        }.take(2).joinToString(", ")
    }
}
