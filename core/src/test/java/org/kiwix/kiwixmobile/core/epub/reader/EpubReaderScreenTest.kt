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

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.ui.components.TOOLBAR_TITLE_TESTING_TAG
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
    search: EpubSearchState = EpubSearchState(),
    actions: EpubReaderActions = EpubReaderActions(),
    pageReady: Boolean = true,
    previousPages: List<Locator> = emptyList(),
    pageMode: EpubPageMode = EpubPageMode.SINGLE_PAGE,
    pageModesAvailable: Boolean = true,
    overviewPages: List<EpubPagePreview> = emptyList(),
    overviewHasPrevious: Boolean = false,
    overviewHasNext: Boolean = false,
    panels: EpubReaderPanels = EpubReaderPanels(),
    annotations: List<EpubAnnotation> = emptyList()
  ) = composeTestRule.setContent {
    EpubReaderScreen(
      state,
      settings,
      reading,
      chromeVisible,
      actions,
      pageReady,
      search = search,
      previousPages = previousPages,
      pageMode = pageMode,
      pageModesAvailable = pageModesAvailable,
      overviewPages = overviewPages,
      overviewHasPrevious = overviewHasPrevious,
      overviewHasNext = overviewHasNext,
      panels = panels,
      annotations = annotations
    )
  }

  @Test
  fun `the top bar opens the book search field`() {
    var opened = false
    show(actions = EpubReaderActions(onOpenSearch = { opened = true }))

    composeTestRule.onNodeWithTag(EPUB_READER_SEARCH_BUTTON_TESTING_TAG).performClick()

    assertTrue(opened)
  }

  @Test
  fun `reader controls do not offer a carousel single page switch`() {
    show()

    composeTestRule.onAllNodesWithText("Page carousel").assertCountEquals(0)
    composeTestRule.onAllNodesWithText("Single page").assertCountEquals(0)
    composeTestRule.onAllNodesWithText("Page view").assertCountEquals(0)
    composeTestRule.onAllNodesWithText("Add bookmark").assertCountEquals(0)
  }

  @Test
  fun `overview button opens overview from the reader controls`() {
    var selected: EpubPageMode? = null
    show(actions = EpubReaderActions(onPageModeSelected = { selected = it }))

    composeTestRule.onNodeWithTag(EPUB_READER_PAGE_OVERVIEW_BUTTON_TESTING_TAG).performClick()

    assertEquals(EpubPageMode.OVERVIEW, selected)
  }

  @Test
  fun `carousel and overview controls are hidden without Vulkan support`() {
    show(pageMode = EpubPageMode.OVERVIEW, pageModesAvailable = false)

    composeTestRule.onAllNodesWithTag(EPUB_READER_PAGE_OVERVIEW_BUTTON_TESTING_TAG)
      .assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(EPUB_READER_PAGE_OVERVIEW_TESTING_TAG).assertCountEquals(0)
  }

  @Test
  fun `page overview scrolls through the book and selection opens carousel`() {
    var selected: Locator? = null
    var mode: EpubPageMode? = null
    var loadedLaterPages = false
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    val pages = (1..30).map { index ->
      EpubPagePreview(
        locator.copyWithLocations(totalProgression = index / 31.0),
        bitmap,
        pageIndex = index - 1,
        chapterTitle = if (index < 11) "Chapter 1" else "Chapter 2"
      )
    }
    show(
      pageMode = EpubPageMode.OVERVIEW,
      overviewPages = pages,
      overviewHasNext = true,
      actions = EpubReaderActions(
        onSelectOverviewPage = { selected = it },
        onPageModeSelected = { mode = it },
        onLoadMoreOverviewPages = { before -> loadedLaterPages = !before }
      )
    )

    composeTestRule.onNodeWithTag(EPUB_READER_PAGE_OVERVIEW_TESTING_TAG).performScrollToIndex(11)
    composeTestRule.onNodeWithText("Chapter 2").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EPUB_READER_PAGE_OVERVIEW_TESTING_TAG).performScrollToIndex(30)
    composeTestRule.onNodeWithText("Page 30").assertIsDisplayed()
    composeTestRule.onAllNodesWithText("Earlier pages").assertCountEquals(0)
    composeTestRule.onAllNodesWithText("Later pages").assertCountEquals(0)
    composeTestRule.onNodeWithTag("${EPUB_READER_PAGE_THUMBNAIL_TESTING_TAG}_29").performClick()

    assertEquals(pages[29].locator, selected)
    assertEquals(EpubPageMode.CAROUSEL, mode)
    assertTrue(loadedLaterPages)
  }

  @Test
  fun `overview loads the next batch after page count and loading state change`() {
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    val pages = (0..29).map { index ->
      EpubPagePreview(locator, bitmap, pageIndex = index)
    }
    val displayedPages = mutableStateOf(pages.take(3))
    val loading = mutableStateOf(true)
    val requests = mutableListOf<Boolean>()
    composeTestRule.setContent {
      EpubReaderScreen(
        ready,
        EpubReaderSettings(),
        EpubReadingState(locator = locator),
        true,
        EpubReaderActions(onLoadMoreOverviewPages = { requests += it }),
        pageMode = EpubPageMode.OVERVIEW,
        overviewPages = displayedPages.value,
        overviewPagesLoading = loading.value,
        overviewHasPrevious = false,
        overviewHasNext = true
      )
    }
    composeTestRule.runOnIdle { assertTrue(requests.isEmpty()) }
    composeTestRule.runOnIdle { loading.value = false }
    composeTestRule.runOnIdle { assertEquals(listOf(false), requests) }
    composeTestRule.runOnIdle {
      loading.value = true
      displayedPages.value = pages
    }
    composeTestRule.runOnIdle { loading.value = false }
    composeTestRule.onNodeWithTag(EPUB_READER_PAGE_OVERVIEW_TESTING_TAG).performScrollToIndex(29)
    composeTestRule.runOnIdle { assertEquals(listOf(false, false), requests) }
  }

  @Test
  fun `bookmark margin button shows saved state and toggles the current page`() {
    var toggled = false
    show(
      chromeVisible = false,
      actions = EpubReaderActions(onBookmark = { toggled = true }),
      annotations = listOf(
        EpubAnnotation("bookmark", EpubAnnotationKind.BOOKMARK, locator, "")
      )
    )

    val bookmark = composeTestRule.onNodeWithContentDescription("Remove bookmark")
      .assertIsDisplayed()
    val page = composeTestRule.onRoot().getUnclippedBoundsInRoot()
    val bookmarkBounds = bookmark.getUnclippedBoundsInRoot()
    assertTrue(bookmarkBounds.top < page.top + (page.bottom - page.top) / 3)
    assertTrue(bookmarkBounds.left > page.left + (page.right - page.left) / 2)
    bookmark
      .assertIsDisplayed()
      .performClick()

    assertTrue(toggled)
  }

  @Test
  fun `search results show their text and navigate to the selected locator`() {
    var selected: Locator? = null
    show(
      search = EpubSearchState(
        isOpen = true,
        query = "kiwi",
        results = listOf(EpubSearchResult(locator, "Some text around kiwi."))
      ),
      actions = EpubReaderActions(onSearchResult = { selected = it })
    )

    composeTestRule.onNodeWithTag(EPUB_READER_SEARCH_FIELD_TESTING_TAG).assertIsDisplayed()
    assertEquals(
      composeTestRule.onRoot().getUnclippedBoundsInRoot().bottom,
      composeTestRule.onNodeWithTag(EPUB_READER_SEARCH_PANEL_TESTING_TAG)
        .getUnclippedBoundsInRoot().bottom
    )
    assertEquals(
      2,
      composeTestRule.onAllNodesWithText("Chapter One").fetchSemanticsNodes().size
    )
    assertEquals(
      2,
      composeTestRule.onAllNodesWithText("Position 26 of 100").fetchSemanticsNodes().size
    )
    composeTestRule.onNodeWithText("Some text around kiwi.").performClick()

    assertEquals(locator, selected)
  }

  @Test
  fun `reopening search restores the previous result scroll position`() {
    val search = mutableStateOf(
      EpubSearchState(
        isOpen = true,
        query = "kiwi",
        results = (0..40).map { index ->
          EpubSearchResult(locator, "Search result $index")
        }
      )
    )
    composeTestRule.setContent {
      EpubReaderScreen(
        ready,
        EpubReaderSettings(),
        EpubReadingState(locator, 100),
        chromeVisible = true,
        actions = EpubReaderActions(
          onOpenSearch = { search.value = search.value.copy(isOpen = true) },
          onCloseSearch = { search.value = search.value.copy(isOpen = false) }
        ),
        search = search.value
      )
    }

    composeTestRule.onNodeWithTag(EPUB_READER_SEARCH_RESULTS_LIST_TESTING_TAG)
      .performScrollToIndex(25)
    composeTestRule.onNodeWithTag("epubReaderCloseSearchTestingTag").performClick()
    composeTestRule.onNodeWithTag(EPUB_READER_SEARCH_BUTTON_TESTING_TAG).performClick()

    composeTestRule.onNodeWithText("Search result 25").assertIsDisplayed()
  }

  @Test
  fun `search suggestions show found words and submit the selected word`() {
    var selected: String? = null
    show(
      search = EpubSearchState(
        isOpen = true,
        query = "kiw",
        suggestions = listOf("kiwi", "kiwifruit"),
        showingSuggestions = true,
        hasSearched = true
      ),
      actions = EpubReaderActions(onSearchSuggestion = { selected = it })
    )

    composeTestRule.onNodeWithText("Words found").assertIsDisplayed()
    composeTestRule.onNodeWithText("kiwi").performClick()

    assertEquals("kiwi", selected)
  }

  @Test
  fun `search result highlights the matched word`() {
    show(
      search = EpubSearchState(
        isOpen = true,
        query = "kiwi",
        results = listOf(EpubSearchResult(locator, "Some text around kiwi."))
      )
    )
    val layouts = mutableListOf<TextLayoutResult>()

    composeTestRule.onNodeWithText("Some text around kiwi.", useUnmergedTree = true)
      .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }

    assertTrue(layouts.single().layoutInput.text.spanStyles.isNotEmpty())
  }

  @Test
  fun `the minimized previous page follows menu visibility`() {
    var returned: Locator? = null
    val chrome = mutableStateOf(false)
    composeTestRule.setContent {
      EpubReaderScreen(
        ready,
        EpubReaderSettings(),
        EpubReadingState(locator, 100),
        chrome.value,
        EpubReaderActions(onReturnToPreviousPage = { returned = it }),
        previousPages = listOf(locator)
      )
    }

    assertTrue(
      composeTestRule.onAllNodesWithTag(EPUB_READER_PREVIOUS_PAGE_TESTING_TAG)
        .fetchSemanticsNodes().isEmpty()
    )
    chrome.value = true
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Back to page 26").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EPUB_READER_PREVIOUS_PAGE_TESTING_TAG).performClick()
    assertEquals(locator, returned)

    chrome.value = false
    composeTestRule.waitForIdle()
    assertTrue(
      composeTestRule.onAllNodesWithTag(EPUB_READER_PREVIOUS_PAGE_TESTING_TAG)
        .fetchSemanticsNodes().isEmpty()
    )
  }

  @Test
  fun `all previous page chips remain available for back and forward navigation`() {
    var selected: Locator? = null
    val pages = (1..4).map { page ->
      locator.copyWithLocations(position = page * 10, totalProgression = page / 10.0)
    }
    show(
      actions = EpubReaderActions(onReturnToPreviousPage = { selected = it }),
      previousPages = pages
    )

    assertEquals(
      4,
      composeTestRule.onAllNodesWithTag(EPUB_READER_PREVIOUS_PAGE_TESTING_TAG)
        .fetchSemanticsNodes().size
    )
    composeTestRule.onNodeWithText("Back to page 20").performClick()
    assertEquals(pages[1], selected)
    composeTestRule.onNodeWithText("Back to page 40").assertIsDisplayed()
  }

  @Test
  fun `annotation list can delete and copy an item`() {
    val annotation = EpubAnnotation(
      id = "note-id",
      kind = EpubAnnotationKind.NOTE,
      locator = locator.copy(text = Locator.Text("before context", "selected text", "after context")),
      text = "selected text",
      note = "My note",
      title = "Chapter One"
    )
    val panels = EpubReaderPanels().apply { showAnnotations = true }
    show(
      panels = panels,
      annotations = listOf(annotation)
    )

    composeTestRule.onNodeWithText("Types").assertIsDisplayed()
    composeTestRule.onNodeWithText("Colors").assertIsDisplayed()
    composeTestRule.onNodeWithText("Chapters").assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithText("Chapter One").fetchSemanticsNodes().isNotEmpty())
    composeTestRule.onNodeWithText("My note").assertIsDisplayed()
    composeTestRule.onNodeWithTag(EPUB_READER_ANNOTATION_DELETE_TESTING_TAG)
      .performScrollTo().assertIsDisplayed().assertIsEnabled()
    composeTestRule.onNodeWithTag(EPUB_READER_ANNOTATION_COPY_TESTING_TAG)
      .performScrollTo().assertIsDisplayed().assertIsEnabled()
  }

  @Test
  fun `loading shows a progress indicator`() {
    show(EpubReaderUiState.Loading)
    composeTestRule.onNodeWithTag(EPUB_READER_LOADING_TESTING_TAG).assertIsDisplayed()
  }

  @Test
  fun `while the first page paints the bar stays reachable and touches pass through`() {
    var taps = 0
    composeTestRule.setContent {
      Box(Modifier.fillMaxSize()) {
        Box(
          Modifier
            .fillMaxSize()
            .clickable { taps++ }
            .testTag("underneath")
        )
        EpubReaderScreen(
          ready,
          EpubReaderSettings(),
          EpubReadingState(),
          false,
          EpubReaderActions(),
          pageReady = false
        )
      }
    }
    composeTestRule.onNodeWithTag(EPUB_READER_LOADING_TESTING_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("A Book").assertIsDisplayed()

    composeTestRule.onNodeWithTag("underneath").performTouchInput { click(Offset(width / 2f, height / 2f)) }

    assertEquals(1, taps)
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
    composeTestRule.onNodeWithText("Position 26 of 100").assertIsDisplayed()
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
    composeTestRule.onNodeWithText("Position 26 of 100").assertDoesNotExist()
    composeTestRule.onNodeWithText("25%").assertDoesNotExist()
    assertEquals(emptyList<Float>(), seeks)

    composeTestRule.onNodeWithTag(EPUB_READER_PROGRESS_SLIDER_TESTING_TAG).performTouchInput { up() }
    assertEquals(1, seeks.size)
    assertEquals(0.75f, seeks.single(), 0.1f)
  }

  @Test
  fun `the label shows the position the navigator reports, not one derived from the percentage`() {
    show(
      reading = EpubReadingState(
        locator.copyWithLocations(position = 7, totalProgression = 0.5),
        positionCount = 100
      )
    )
    composeTestRule.onNodeWithText("Position 7 of 100").assertIsDisplayed()
    composeTestRule.onNodeWithText("50%").assertIsDisplayed()
  }

  @Test
  fun `the preview is dropped after a seek that reports no new locator`() {
    show()
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.onNodeWithTag(EPUB_READER_PROGRESS_SLIDER_TESTING_TAG).performTouchInput {
      down(Offset(width * 0.25f, centerY))
      moveTo(Offset(width * 0.75f, centerY))
      up()
    }
    composeTestRule.mainClock.advanceTimeBy(100)
    composeTestRule.onNodeWithText("Position 26 of 100").assertDoesNotExist()

    composeTestRule.mainClock.advanceTimeBy(1_000)

    composeTestRule.onNodeWithText("Position 26 of 100").assertIsDisplayed()
    composeTestRule.onNodeWithText("25%").assertIsDisplayed()
  }

  @Test
  fun `the chapter buttons and scrubber follow the book's reading direction`() {
    fun left(tag: String) = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
    val rtlBook = EpubReaderUiState.Ready(OpenEpub(mockk(relaxed = true), "id", "A Book", toc, null, rtl = true))

    show(rtlBook)
    assertTrue(left(EPUB_READER_PREVIOUS_CHAPTER_TESTING_TAG) > left(EPUB_READER_NEXT_CHAPTER_TESTING_TAG))
  }

  @Test
  fun `a left to right book keeps previous on the left`() {
    show()
    val previous = composeTestRule.onNodeWithTag(EPUB_READER_PREVIOUS_CHAPTER_TESTING_TAG)
    val next = composeTestRule.onNodeWithTag(EPUB_READER_NEXT_CHAPTER_TESTING_TAG)
    assertTrue(previous.fetchSemanticsNode().boundsInRoot.left < next.fetchSemanticsNode().boundsInRoot.left)
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

  @Test
  fun `the top bar follows each reading theme instead of the app theme`() {
    val preset = mutableStateOf<EpubThemePreset?>(null)
    composeTestRule.setContent {
      EpubReaderScreen(
        ready,
        EpubReaderSettings(themePreset = preset.value),
        EpubReadingState(),
        true,
        EpubReaderActions()
      )
    }
    listOf(null, *EpubThemePreset.entries.toTypedArray()).forEach { choice ->
      preset.value = choice
      composeTestRule.waitForIdle()
      val colors = EpubReaderSettings(themePreset = choice).overlayColors(false)
      val results = mutableListOf<TextLayoutResult>()
      composeTestRule.onNodeWithTag(TOOLBAR_TITLE_TESTING_TAG)
        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
      assertEquals("$choice title", colors.content, results.single().layoutInput.style.color.toArgb())
    }
  }

  private fun setSlider(tag: String, value: Float) {
    composeTestRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
  }
}
