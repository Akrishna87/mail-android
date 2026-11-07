package com.akrishna87.mail

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Selection on the whole inbox screen (header, bottom bar, pull to refresh, drawer). */
@RunWith(AndroidJUnit4::class)
class InboxSelectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longPressOnInboxShowsSelectionBar() {
        val vm = MailViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.showMessagesForTest(
            (1L..3L).map { MessageSummary(uid = it, from = "Sender $it", subject = "Subject $it", date = null, seen = false) },
        )
        compose.setContent { MaterialTheme { MailApp(vm) } }

        compose.onNodeWithText("Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("Compose").assertIsDisplayed()
        compose.onNodeWithText("Subject 2").performTouchInput { longClick() }

        compose.runOnIdle { assertEquals(setOf(2L), vm.selection) }
        compose.onNodeWithContentDescription("Mark read").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete").assertIsDisplayed()

        compose.onNodeWithText("Subject 3").performClick()
        compose.runOnIdle { assertEquals(setOf(2L, 3L), vm.selection) }
        // The bottom bar steps aside while selecting.
        compose.onNodeWithText("Search").assertDoesNotExist()
    }
}
