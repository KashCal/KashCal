package org.onekash.kashcal.ui.components.attendees

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v23.7.17: `EventCardAttendeeBadge` count must subtract every chip
 * with `isYou=true`, not just one. Real-world trigger: a user whose
 * account has multiple calendar-user-addresses (e.g. me.com + icloud.com)
 * appears twice on the attendee list, both rows synthesized as `isYou=true`
 * by `AttendeeUiModel.computeForEvent`.
 */
@RunWith(AndroidJUnit4::class)
class EventCardAttendeeBadgeComposeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun emptyList_rendersNothing() {
        rule.setContent { Themed { EventCardAttendeeBadge(models = emptyList()) } }
        // Nothing to assert beyond "no exception" — no testTag on the badge.
        rule.onNodeWithText("👥").assertDoesNotExist()
    }

    @Test
    fun multiAlias_user_subtractsBothFromCount() {
        // Two aliases of the user + two real invitees. Badge should show "2".
        rule.setContent {
            Themed {
                EventCardAttendeeBadge(
                    models = listOf(
                        model("me@me.com", sortOrder = 0, isYou = true),
                        model("me@icloud.com", sortOrder = 1, isYou = true),
                        model("alice@example.test", sortOrder = 2),
                        model("bob@example.test", sortOrder = 3)
                    )
                )
            }
        }
        rule.onNodeWithText("👥 2").assertIsDisplayed()
    }

    @Test
    fun singleUser_with3Invitees_count3() {
        // Single isYou + 3 invitees — count is 3, not 4.
        rule.setContent {
            Themed {
                EventCardAttendeeBadge(
                    models = listOf(
                        model("me@example.test", sortOrder = 0, isYou = true),
                        model("a@example.test", sortOrder = 1),
                        model("b@example.test", sortOrder = 2),
                        model("c@example.test", sortOrder = 3)
                    )
                )
            }
        }
        rule.onNodeWithText("👥 3").assertIsDisplayed()
    }

    @Test
    fun organizerSelf_only_rendersNothing() {
        // Organizer-self with no invitees: count = 0 → early-return, no badge.
        rule.setContent {
            Themed {
                EventCardAttendeeBadge(
                    models = listOf(
                        model("me@example.test", sortOrder = 0, isYou = true, isOrganizer = true)
                    )
                )
            }
        }
        rule.onNodeWithText("👑 0", substring = false).assertDoesNotExist()
        rule.onNodeWithText("👥 0", substring = false).assertDoesNotExist()
    }

    @Test
    fun organizerSelf_with2Invitees_rendersHostingLabel() {
        rule.setContent {
            Themed {
                EventCardAttendeeBadge(
                    models = listOf(
                        model("me@example.test", sortOrder = 0, isYou = true, isOrganizer = true),
                        model("a@example.test", sortOrder = 1),
                        model("b@example.test", sortOrder = 2)
                    )
                )
            }
        }
        rule.onNodeWithText("👑 2").assertIsDisplayed()
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        MaterialTheme { content() }
    }

    private fun model(
        addr: String,
        sortOrder: Int,
        isYou: Boolean = false,
        isOrganizer: Boolean = false
    ) = AttendeeUiModel(
        displayName = addr.substringBefore('@'),
        bareAddress = addr,
        status = AttendeeStatus.Accepted,
        isYou = isYou,
        isOrganizer = isOrganizer,
        sortOrder = sortOrder
    )
}
