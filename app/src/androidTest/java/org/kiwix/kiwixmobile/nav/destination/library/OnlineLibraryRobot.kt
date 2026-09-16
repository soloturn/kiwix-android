/*
 * Kiwix Android
 * Copyright (c) 2020 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.kiwix.kiwixmobile.nav.destination.library

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import applyWithViewHierarchyPrinting
import org.kiwix.kiwixmobile.BaseRobot
import org.kiwix.kiwixmobile.core.R.string
import org.kiwix.kiwixmobile.core.ui.components.TOOLBAR_TITLE_TESTING_TAG
import org.kiwix.kiwixmobile.testutils.TestUtils.TEST_PAUSE_MS_FOR_DOWNLOAD_TEST
import org.kiwix.kiwixmobile.testutils.TestUtils.testFlakyView

fun onlineLibrary(func: OnlineLibraryRobot.() -> Unit) =
  OnlineLibraryRobot().applyWithViewHierarchyPrinting(func)

class OnlineLibraryRobot : BaseRobot() {
  fun assertOnlineLibraryScreenDisplayed(composeContentTestRule: ComposeContentTestRule) {
    testFlakyView({
      composeContentTestRule.apply {
        waitForIdle()
        val expectedTitle = context.getString(string.download)
        // Poll for the title instead of a fixed sleep-then-check-once,
        // which can fire before the shortcut intent's delayed navigation.
        waitUntil(TEST_PAUSE_MS_FOR_DOWNLOAD_TEST) {
          onAllNodes(hasTestTag(TOOLBAR_TITLE_TESTING_TAG).and(hasText(expectedTitle)))
            .fetchSemanticsNodes()
            .isNotEmpty()
        }
        onNodeWithTag(TOOLBAR_TITLE_TESTING_TAG).assertTextEquals(expectedTitle)
      }
    })
  }
}
