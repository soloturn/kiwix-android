/*
 * Kiwix Android
 * Copyright (c) 2019 Kiwix <android.kiwix.org>
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
package org.kiwix.kiwixmobile.utils

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.kiwix.kiwixmobile.core.main.CoreMainActivity
import org.kiwix.kiwixmobile.core.main.LEFT_DRAWER_SETTINGS_ITEM_TESTING_TAG
import org.kiwix.kiwixmobile.core.ui.components.OVERFLOW_MENU_BUTTON_TESTING_TAG
import org.kiwix.kiwixmobile.main.BOTTOM_NAV_LIBRARY_ITEM_TESTING_TAG
import org.kiwix.kiwixmobile.testutils.TestUtils.testFlakyView

/**
 * Created by mhutti1 on 27/04/17.
 */
object StandardActions {
  /**
   * Settings now lives in the Library app bar's overflow menu (no more drawer).
   * Callers arrive from anywhere, so go to Library first.
   */
  fun enterSettings(composeContentTest: ComposeContentTestRule) {
    testFlakyView({
      composeContentTest.apply {
        waitForIdle()
        onNodeWithTag(BOTTOM_NAV_LIBRARY_ITEM_TESTING_TAG).performClick()
        onNodeWithTag(OVERFLOW_MENU_BUTTON_TESTING_TAG).performClick()
        onNodeWithTag(LEFT_DRAWER_SETTINGS_ITEM_TESTING_TAG).performClick()
      }
    })
  }

  /**
   * No-ops: there is no drawer any more. Kept (rather than deleted) so the many call
   * sites that used these purely as a "make sure drawer isn't covering the screen"
   * precondition don't all need touching — that precondition is trivially true now.
   */
  @Suppress("UnusedParameter")
  fun openDrawer(coreMainActivity: CoreMainActivity) = Unit

  @Suppress("UnusedParameter")
  fun closeDrawer(coreMainActivity: CoreMainActivity) = Unit
}
