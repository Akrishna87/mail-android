package com.akrishna87.mail

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Selecting several messages, on the real list rows and selection bar. */
@RunWith(AndroidJUnit4::class)
class SelectionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: MailViewModel

    @Before
    fun showList() {
        vm = MailViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.showMessagesForTest(
            (1L..3L).map { MessageSummary(uid = it, from = "Sender $it", subject = "Subject $it", date = null, seen = false) },
        )
        compose.setContent {
            MaterialTheme {
                Column {
                    if (vm.selection.isNotEmpty()) SelectionBar(vm)
                    LazyColumn {
                        items(vm.messages, key = { it.uid }) { SwipeableRow(vm, it) }
                    }
                }
            }
        }
    }

    @Test
    fun longPressSelectsAndShowsActions() {
        compose.onNodeWithText("Subject 1").performTouchInput { longClick() }

        compose.runOnIdle { assertEquals(setOf(1L), vm.selection) }
        compose.onNodeWithContentDescription("Delete").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark read").assertIsDisplayed()
        compose.onNodeWithContentDescription("Snooze").assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Star").assertIsDisplayed()
        compose.onNodeWithText("Move to").assertIsDisplayed()
    }

    @Test
    fun snoozeOffersGmailTimes() {
        compose.onNodeWithText("Subject 1").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Snooze").performClick()

        compose.onNodeWithText("Snooze until\u2026").assertIsDisplayed()
        compose.onNodeWithText("Tomorrow").assertIsDisplayed()
        compose.onNodeWithText("Next week").assertIsDisplayed()
    }

    @Test
    fun moveToListsOtherFolders() {
        vm.showFoldersForTest(
            listOf(
                MailFolder("INBOX", "INBOX", "inbox", 0),
                MailFolder("Work", "Work", null, 0),
                MailFolder("[Gmail]/Trash", "Trash", "trash", 0),
            ),
        )
        compose.onNodeWithText("Subject 1").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Move to").performClick()

        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Trash").assertIsDisplayed()
    }

    @Test
    fun tapAddsToSelectionOnceSelecting() {
        compose.onNodeWithText("Subject 1").performTouchInput { longClick() }
        compose.onNodeWithText("Subject 2").performClick()

        compose.runOnIdle { assertEquals(setOf(1L, 2L), vm.selection) }
        compose.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun tappingAvatarSelects() {
        compose.onAllNodesWithText("S")[0].performClick()

        compose.runOnIdle { assertEquals(setOf(1L), vm.selection) }
    }

    @Test
    fun selectAllThenCancel() {
        compose.onNodeWithText("Subject 1").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Select all").performClick()
        compose.runOnIdle { assertEquals(setOf(1L, 2L, 3L), vm.selection) }

        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.runOnIdle { assertEquals(emptySet<Long>(), vm.selection) }
    }
}
