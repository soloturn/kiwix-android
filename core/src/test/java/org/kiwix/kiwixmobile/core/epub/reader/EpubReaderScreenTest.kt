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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
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

  private val locator = Locator(href = Url("c1.xhtml")!!, mediaType = MediaType.XHTML)
    .copyWithLocations(totalProgression = 0.25)

  private fun show(
    state: EpubReaderUiState = ready,
    settings: EpubReaderSettings = EpubReaderSettings(),
    chromeVisible: Boolean = true,
    reading: EpubReadingState = EpubReadingState(
      locator = locator,
      positionCount = 100,
      hasPreviousChapter = true,
      hasNextChapter = true
    ),
    actions: EpubReaderActions = EpubReaderActions()
  ) = composeTestRule.setContent {
    EpubReaderScreen(state, settings, reading, chromeVisible, actions)
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
      EpubReaderScreen(ready, EpubReaderSettings(), EpubReadingState(), chrome.value, EpubReaderActions())
    }
    composeTestRule.onNodeWithText("A Book").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EPUB_READER_PROGRESS_SLIDER_TESTING_TAG).assertIsDisplayed()

    chrome.value = false
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("A Book").assertDoesNotExist()
    composeTestRule.onNodeWithTag(EPUB_READER_PROGRESS_SLIDER_TESTING_TAG).assertDoesNotExist()
  }

  @Test
  fun `the bottom bar shows the chapter, the page and the percentage`() {
    show()
    composeTestRule.onNodeWithText("Chapter One").assertIsDisplayed()
    composeTestRule.onNodeWithText("Page 26 of 100").assertIsDisplayed()
    composeTestRule.onNodeWithText("25%").assertIsDisplayed()
  }

  @Test
  fun `the progress bar is disabled until the positions are known`() {
    show(reading = EpubReadingState(locator = locator, positionCount = 0))
    composeTestRule.onNodeWithTag(EPUB_READER_PROGRESS_SLIDER_TESTING_TAG).assertIsNotEnabled()
    composeTestRule.onNodeWithText("25%").assertIsDisplayed()
  }

  @Test
  fun `dragging previews the page and releasing seeks once`() {
    val seeks = mutableListOf<Float>()
    show(actions = EpubReaderActions(onSeek = { seeks += it }))

    composeTestRule.onNodeWithTag(EPUB_READER_PROGRESS_SLIDER_TESTING_TAG).performTouchInput {
      // The thumb sits at 25%; dragging it right moves it by the same distance.
      down(Offset(width * 0.25f, centerY))
      moveTo(Offset(width * 0.75f, centerY))
    }
    composeTestRule.onNodeWithText("Page 26 of 100").assertDoesNotExist()
    composeTestRule.onNodeWithText("25%").assertDoesNotExist()
    assertEquals(emptyList<Float>(), seeks)

    composeTestRule.onNodeWithTag(EPUB_READER_PROGRESS_SLIDER_TESTING_TAG).performTouchInput { up() }
    assertEquals(1, seeks.size)
    assertEquals(0.75f, seeks.single(), 0.1f)
  }

  @Test
  fun `chapter buttons follow whether a neighbouring chapter exists`() {
    show(reading = EpubReadingState(locator, 100, hasPreviousChapter = false, hasNextChapter = true))
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
  fun `open panels survive the screen being composed again`() {
    val panels = EpubReaderPanels()
    val generation = mutableStateOf(0)
    composeTestRule.setContent {
      key(generation.value) {
        EpubReaderScreen(
          ready,
          EpubReaderSettings(),
          EpubReadingState(locator, 100),
          true,
          EpubReaderActions(),
          panels = panels
        )
      }
    }
    composeTestRule.onNodeWithTag(EPUB_READER_TOC_TESTING_TAG).performClick()
    composeTestRule.onNodeWithText("Section 1.1").assertIsDisplayed()

    generation.value++
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Section 1.1").assertIsDisplayed()
  }

  @Test
  fun `an empty table of contents says so`() {
    show(EpubReaderUiState.Ready(OpenEpub(mockk(relaxed = true), "id", "A Book", emptyList(), null)))
    composeTestRule.onNodeWithTag(EPUB_READER_TOC_TESTING_TAG).performClick()
    composeTestRule.onNodeWithText("This book has no table of contents.").assertIsDisplayed()
  }

  @Test
  fun `the Aa sheet changes size, margins and spacing through the sliders`() {
    val applied = mutableListOf<EpubReaderSettings>()
    show(
      settings = EpubReaderSettings(publisherStyles = false),
      actions = EpubReaderActions(onSettings = { applied += it(EpubReaderSettings()) })
    )

    composeTestRule.onNodeWithTag(EPUB_READER_SETTINGS_TESTING_TAG).performClick()
    setSlider(EPUB_SETTINGS_FONT_SIZE_TESTING_TAG, 2.0f)
    setSlider(EPUB_SETTINGS_MARGINS_TESTING_TAG, 0.5f)
    setSlider(EPUB_SETTINGS_LINE_SPACING_TESTING_TAG, 1.8f)

    assertEquals(
      listOf(
        EpubReaderSettings().withFontScale(2.0),
        EpubReaderSettings().withPageMargins(0.5),
        EpubReaderSettings().withLineSpacing(1.8)
      ),
      applied
    )
  }

  @Test
  fun `the Aa sheet picks theme, font, layout and book formatting`() {
    val applied = mutableListOf<EpubReaderSettings>()
    show(actions = EpubReaderActions(onSettings = { applied += it(EpubReaderSettings()) }))

    composeTestRule.onNodeWithTag(EPUB_READER_SETTINGS_TESTING_TAG).performClick()
    composeTestRule.onNodeWithTag("${EPUB_SETTINGS_THEME_TESTING_TAG_PREFIX}BLACK").performClick()
    composeTestRule.onNodeWithTag("${EPUB_SETTINGS_THEME_TESTING_TAG_PREFIX}AUTO").performClick()
    composeTestRule.onNodeWithTag("${EPUB_SETTINGS_FONT_TESTING_TAG_PREFIX}SERIF").performClick()
    composeTestRule.onNodeWithTag(EPUB_SETTINGS_SCROLL_TESTING_TAG).performClick()
    composeTestRule.onNodeWithTag(EPUB_SETTINGS_BOOK_STYLES_TESTING_TAG).performScrollTo().performClick()

    val base = EpubReaderSettings()
    assertEquals(
      listOf(
        base.copy(themePreset = EpubThemePreset.BLACK),
        base.copy(themePreset = null),
        base.copy(fontChoice = EpubFontChoice.SERIF),
        base.copy(scroll = true),
        base.copy(publisherStyles = false)
      ),
      applied
    )
  }

  @Test
  fun `line spacing and alignment are disabled while the book formatting is on`() {
    show(settings = EpubReaderSettings(publisherStyles = true))
    composeTestRule.onNodeWithTag(EPUB_READER_SETTINGS_TESTING_TAG).performClick()
    composeTestRule.onNodeWithTag(EPUB_SETTINGS_LINE_SPACING_TESTING_TAG).assertIsNotEnabled()
    composeTestRule.onNodeWithTag("${EPUB_SETTINGS_ALIGN_TESTING_TAG_PREFIX}JUSTIFY").assertIsNotEnabled()
  }

  private fun setSlider(tag: String, value: Float) {
    composeTestRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
  }
}
