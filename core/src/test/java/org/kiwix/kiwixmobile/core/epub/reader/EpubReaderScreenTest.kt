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

package org.kiwix.kiwixmobile.core.epub.reader

import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubReaderScreenTest {
  @get:Rule
  val composeTestRule = createComposeRule()

  private val toc = listOf(
    EpubTocItem("Chapter One", Link(href = Url("c1.xhtml")!!), 0),
    EpubTocItem("Section 1.1", Link(href = Url("c1.xhtml#s1")!!), 1)
  )
  private val ready = EpubReaderUiState.Ready(
    OpenEpub(mockk(relaxed = true), "id", "A Book", toc, null)
  )

  private fun show(
    state: EpubReaderUiState = ready,
    settings: EpubReaderSettings = EpubReaderSettings(),
    chromeVisible: Boolean = true,
    hasPreviousChapter: Boolean = true,
    hasNextChapter: Boolean = true,
    actions: EpubReaderActions = EpubReaderActions()
  ) = composeTestRule.setContent {
    EpubReaderScreen(
      state = state,
      settings = settings,
      currentLocator = null,
      chromeVisible = chromeVisible,
      hasPreviousChapter = hasPreviousChapter,
      hasNextChapter = hasNextChapter,
      actions = actions
    )
  }

  @Test
  fun `loading shows a progress indicator`() {
    show(EpubReaderUiState.Loading)
    composeTestRule.onNodeWithTag(EPUB_READER_LOADING_TESTING_TAG).assertIsDisplayed()
  }

  @Test
  fun `failure shows the message and a close button that goes back`() {
    var backs = 0
    show(EpubReaderUiState.Failed, actions = EpubReaderActions(onBack = { backs++ }))

    composeTestRule.onNodeWithTag(EPUB_READER_ERROR_TESTING_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("Close").performClick()
    assertEquals(1, backs)
  }

  @Test
  fun `a ready book shows its title in the top bar, which can be hidden`() {
    val chrome = mutableStateOf(true)
    composeTestRule.setContent {
      EpubReaderScreen(ready, EpubReaderSettings(), null, chrome.value, true, true, EpubReaderActions())
    }
    composeTestRule.onNodeWithText("A Book").assertIsDisplayed()

    chrome.value = false
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("A Book").assertDoesNotExist()
  }

  @Test
  fun `chapter buttons follow whether a neighbouring chapter exists`() {
    show(hasPreviousChapter = false, hasNextChapter = true)
    composeTestRule.onNodeWithTag(EPUB_READER_PREVIOUS_CHAPTER_TESTING_TAG).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(EPUB_READER_NEXT_CHAPTER_TESTING_TAG).assertIsEnabled()
  }

  @Test
  fun `chapter buttons call back`() {
    var previous = 0
    var next = 0
    show(actions = EpubReaderActions(onPreviousChapter = { previous++ }, onNextChapter = { next++ }))

    composeTestRule.onNodeWithTag(EPUB_READER_PREVIOUS_CHAPTER_TESTING_TAG).performClick()
    composeTestRule.onNodeWithTag(EPUB_READER_NEXT_CHAPTER_TESTING_TAG).performClick()

    assertEquals(1, previous)
    assertEquals(1, next)
  }

  @Test
  fun `table of contents lists the entries and navigates on tap`() {
    var opened: EpubTocItem? = null
    show(actions = EpubReaderActions(onTocItem = { opened = it }))

    composeTestRule.onNodeWithTag(EPUB_READER_TOC_TESTING_TAG).performClick()
    assertEquals(2, composeTestRule.onAllNodesWithTag(EPUB_READER_TOC_ITEM_TESTING_TAG).fetchSemanticsNodes().size)
    composeTestRule.onNodeWithText("Section 1.1").performClick()

    assertEquals(toc[1], opened)
    composeTestRule.onNodeWithText("Section 1.1").assertDoesNotExist()
  }

  @Test
  fun `an empty table of contents says so`() {
    show(EpubReaderUiState.Ready(OpenEpub(mockk(relaxed = true), "id", "A Book", emptyList(), null)))
    composeTestRule.onNodeWithTag(EPUB_READER_TOC_TESTING_TAG).performClick()
    composeTestRule.onNodeWithText("This book has no table of contents.").assertIsDisplayed()
  }

  @Test
  fun `settings change text size and margins and stop at the limits`() {
    val applied = mutableListOf<EpubReaderSettings>()
    show(
      settings = EpubReaderSettings(fontScale = EpubReaderSettings.MAX_FONT_SCALE),
      actions = EpubReaderActions(onSettings = { applied += it(EpubReaderSettings()) })
    )

    composeTestRule.onNodeWithTag(EPUB_READER_SETTINGS_TESTING_TAG).performClick()
    composeTestRule.onNodeWithTag(EPUB_READER_FONT_LARGER_TESTING_TAG).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(EPUB_READER_FONT_SMALLER_TESTING_TAG).performClick()
    composeTestRule.onNodeWithTag(EPUB_READER_MARGINS_WIDER_TESTING_TAG).performClick()

    assertEquals(
      listOf(EpubReaderSettings().smallerFont(), EpubReaderSettings().widerMargins()),
      applied
    )
  }
}
