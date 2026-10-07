/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
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

package org.kiwix.kiwixmobile.ui

import android.os.Build
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.epub.EpubOnDisk
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.SelectionMode
import org.kiwix.kiwixmobile.nav.destination.library.local.BookItemListForPreview
import org.kiwix.kiwixmobile.nav.destination.library.local.EpubItemCallbacks
import org.kiwix.kiwixmobile.ui.EpubBookItemScreen.EPUB_BADGE_TESTING_TAG
import org.kiwix.kiwixmobile.ui.EpubBookItemScreen.EPUB_ITEM_CHECKBOX_TESTING_TAG
import org.kiwix.kiwixmobile.ui.EpubBookItemScreen.EPUB_ITEM_TESTING_TAG
import org.kiwix.kiwixmobile.zimManager.fileselectView.FileSelectListState
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class EpubBookItemUITest {
  @Rule
  @JvmField
  val composeTestRule = createComposeRule()

  private fun epub(isSelected: Boolean = false) = EpubOnDisk(
    id = "e1",
    file = File("/books/e1.epub"),
    title = "Moby Dick",
    authors = "Herman Melville",
    language = "en",
    coverPath = null,
    size = 2_500_000L,
    addedAt = 1L,
    lastOpenedAt = 0L,
    isSelected = isSelected
  )

  @Test
  fun showsTitleAuthorSizeAndEpubBadge() {
    composeTestRule.setContent { EpubBookItem(0, epub()) }
    composeTestRule.onNodeWithText("Moby Dick").assertIsDisplayed()
    composeTestRule.onNodeWithText("Herman Melville").assertIsDisplayed()
    composeTestRule.onNodeWithText("2.5 MB").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EPUB_BADGE_TESTING_TAG, useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("EPUB").assertIsDisplayed()
  }

  @Test
  fun clickAndLongClickInNormalMode_reportTheEpub() {
    var clicked: EpubOnDisk? = null
    var longClicked: EpubOnDisk? = null
    composeTestRule.setContent {
      EpubBookItem(0, epub(), onClick = { clicked = it }, onLongClick = { longClicked = it })
    }
    composeTestRule.onNodeWithTag(EPUB_ITEM_TESTING_TAG).performClick()
    assertEquals("e1", clicked?.id)
    composeTestRule.onNodeWithTag(EPUB_ITEM_TESTING_TAG).performTouchInput { longClick() }
    assertEquals("e1", longClicked?.id)
  }

  @Test
  fun inMultiMode_clickTogglesSelectionAndShowsCheckbox() {
    var selected: EpubOnDisk? = null
    var clicked = false
    composeTestRule.setContent {
      EpubBookItem(
        0,
        epub(isSelected = true),
        selectionMode = SelectionMode.MULTI,
        onClick = { clicked = true },
        onMultiSelect = { selected = it }
      )
    }
    composeTestRule.onNodeWithTag("${EPUB_ITEM_CHECKBOX_TESTING_TAG}0").assertIsOn()
    composeTestRule.onNodeWithTag(EPUB_ITEM_TESTING_TAG).performClick()
    assertEquals("e1", selected?.id)
    assertEquals(false, clicked)
  }

  @Test
  fun libraryListShowsEpubRowsAlongsideTheEmptyZimList() {
    var clicked: EpubOnDisk? = null
    composeTestRule.setContent {
      BookItemListForPreview(
        state = FileSelectListState(emptyList(), epubItems = listOf(epub())),
        lazyListState = rememberLazyListState(),
        epubCallbacks = EpubItemCallbacks(onClick = { clicked = it })
      )
    }
    composeTestRule.onNodeWithText("Moby Dick").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EPUB_ITEM_TESTING_TAG).performClick()
    assertEquals("e1", clicked?.id)
  }
}
