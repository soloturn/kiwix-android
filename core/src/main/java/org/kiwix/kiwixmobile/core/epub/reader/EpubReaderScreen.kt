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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.ui.components.KiwixAppBar
import org.kiwix.kiwixmobile.core.ui.components.KiwixSearchView
import org.kiwix.kiwixmobile.core.ui.components.NavigationIcon
import org.kiwix.kiwixmobile.core.ui.models.ActionMenuItem
import org.kiwix.kiwixmobile.core.ui.models.IconItem
import org.kiwix.kiwixmobile.core.ui.theme.KiwixTheme
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.EIGHT_DP
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.SIXTEEN_DP
import org.readium.r2.shared.publication.Locator
import kotlin.math.roundToInt

const val EPUB_READER_LOADING_TESTING_TAG = "epubReaderLoadingTestingTag"
const val EPUB_READER_ERROR_TESTING_TAG = "epubReaderErrorTestingTag"
const val EPUB_READER_PREVIOUS_CHAPTER_TESTING_TAG = "epubReaderPreviousChapterTestingTag"
const val EPUB_READER_NEXT_CHAPTER_TESTING_TAG = "epubReaderNextChapterTestingTag"
const val EPUB_READER_TOC_TESTING_TAG = "epubReaderTocTestingTag"
const val EPUB_READER_SETTINGS_TESTING_TAG = "epubReaderSettingsTestingTag"
const val EPUB_READER_TOC_ITEM_TESTING_TAG = "epubReaderTocItemTestingTag"
const val EPUB_READER_PROGRESS_SLIDER_TESTING_TAG = "epubReaderProgressSliderTestingTag"
const val EPUB_READER_PAGE_LABEL_TESTING_TAG = "epubReaderPageLabelTestingTag"
const val EPUB_READER_SEARCH_BUTTON_TESTING_TAG = "epubReaderSearchButtonTestingTag"
const val EPUB_READER_SEARCH_FIELD_TESTING_TAG = "epubReaderSearchFieldTestingTag"
const val EPUB_READER_SEARCH_RESULT_TESTING_TAG = "epubReaderSearchResultTestingTag"
const val EPUB_READER_SEARCH_SUGGESTION_TESTING_TAG = "epubReaderSearchSuggestionTestingTag"
const val EPUB_READER_SEARCH_CHAPTER_TESTING_TAG = "epubReaderSearchChapterTestingTag"
const val EPUB_READER_SEARCH_POSITION_TESTING_TAG = "epubReaderSearchPositionTestingTag"
const val EPUB_READER_PREVIOUS_PAGE_TESTING_TAG = "epubReaderPreviousPageTestingTag"

private const val PERCENT = 100
private const val TRACK_ALPHA = 0.24f
private const val SEEK_SETTLE_MS = 800L

/** What the reader chrome can ask of its host. */
data class EpubReaderActions(
  val onBack: () -> Unit = {},
  val onTocItem: (EpubTocItem) -> Unit = {},
  val onPreviousChapter: () -> Unit = {},
  val onNextChapter: () -> Unit = {},
  val onSeek: (Float) -> Unit = {},
  val onSettings: ((EpubReaderSettings) -> EpubReaderSettings) -> Unit = {},
  val onOpenSearch: () -> Unit = {},
  val onSearchQueryChange: (String) -> Unit = {},
  val onSearch: (String) -> Unit = {},
  val onSearchSuggestion: (String) -> Unit = {},
  val onCloseSearch: () -> Unit = {},
  val onSearchResult: (Locator) -> Unit = {},
  val onReturnToPreviousPage: () -> Unit = {}
)

/** Where the reader is, for the progress bar and the chapter buttons. */
data class EpubReadingState(
  val locator: Locator? = null,
  val positionCount: Int = 0,
  val hasPreviousChapter: Boolean = false,
  val hasNextChapter: Boolean = false
)

/** Which panels are open. Held by the view model: the activity is recreated without saved state. */
class EpubReaderPanels {
  var showToc by mutableStateOf(false)
  var showSettings by mutableStateOf(false)
  var showSearch by mutableStateOf(false)
}

/**
 * The reader's own UI around the navigator: the overlay (top bar, bottom progress bar), loading
 * and error states, the table of contents and the reading settings. Drawn over the navigator
 * view, so it draws nothing where the book should show through.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EpubReaderScreen(
  state: EpubReaderUiState,
  settings: EpubReaderSettings,
  reading: EpubReadingState,
  chromeVisible: Boolean,
  actions: EpubReaderActions,
  pageReady: Boolean = true,
  panels: EpubReaderPanels = remember { EpubReaderPanels() },
  search: EpubSearchState = EpubSearchState(),
  previousPage: Locator? = null
) {
  val ready = state as? EpubReaderUiState.Ready
  val overlay = settings.overlayColors(isSystemInDarkTheme())
  KiwixTheme {
    Box(Modifier.fillMaxSize()) {
      when {
        state == EpubReaderUiState.Failed -> ErrorContent(actions.onBack)
        // The book stays hidden until its first page has painted; show the reading colours.
        state == EpubReaderUiState.Loading || !pageReady -> LoadingContent(overlay)
      }
      OverlayTheme(overlay) {
        ReaderChrome(
          ReaderChromeState(ready, reading, chromeVisible, pageReady, previousPage),
          panels,
          search,
          actions
        )
      }
    }
    ReaderPanels(ready, reading, settings, panels, actions)
  }
}

private data class ReaderChromeState(
  val ready: EpubReaderUiState.Ready?,
  val reading: EpubReadingState,
  val chromeVisible: Boolean,
  val pageReady: Boolean,
  val previousPage: Locator?
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BoxScope.ReaderChrome(
  state: ReaderChromeState,
  panels: EpubReaderPanels,
  search: EpubSearchState,
  actions: EpubReaderActions
) {
  val ready = state.ready
  val reading = state.reading
  val chromeVisible = state.chromeVisible
  val pageReady = state.pageReady
  val previousPage = state.previousPage
  AnimatedVisibility(
    visible = ready == null || chromeVisible || !pageReady || panels.showSearch,
    modifier = Modifier.align(Alignment.TopCenter),
    enter = fadeIn(),
    exit = fadeOut()
  ) {
    Column(Modifier.fillMaxWidth()) {
      TopBar(
        title = ready?.book?.title.orEmpty(),
        showSettings = ready != null,
        search = search,
        actions = actions,
        onSettings = {
          panels.showSearch = false
          panels.showSettings = true
        }
      )
      if (ready != null && search.isOpen) {
        SearchResults(
          search,
          ready.book.toc,
          reading.positionCount,
          actions.onSearchSuggestion,
          actions.onSearchResult
        )
      }
    }
  }
  AnimatedVisibility(
    visible = ready != null && chromeVisible,
    modifier = Modifier.align(Alignment.BottomCenter),
    enter = fadeIn(),
    exit = fadeOut()
  ) {
    if (ready != null) BottomBar(ready.book, reading, actions) { panels.showToc = true }
  }
  val returnPage = previousPage.takeIf { ready != null && pageReady && !search.isOpen }
  returnPage?.let {
    PreviousPageChip(it, chromeVisible, actions.onReturnToPreviousPage)
  }
}

@Composable
private fun ReaderPanels(
  ready: EpubReaderUiState.Ready?,
  reading: EpubReadingState,
  settings: EpubReaderSettings,
  panels: EpubReaderPanels,
  actions: EpubReaderActions
) {
  if (panels.showToc && ready != null) {
    TocDialog(ready.book.toc, reading.locator, onDismiss = { panels.showToc = false }) {
      panels.showToc = false
      actions.onTocItem(it)
    }
  }
  if (panels.showSettings) {
    EpubSettingsSheet(settings, actions.onSettings) { panels.showSettings = false }
  }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BoxScope.PreviousPageChip(locator: Locator, chromeVisible: Boolean, onClick: () -> Unit) {
  Surface(
    modifier = Modifier
      .align(Alignment.BottomEnd)
      .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
      .padding(end = SIXTEEN_DP, bottom = if (chromeVisible) 88.dp else SIXTEEN_DP)
      .clickable(onClick = onClick)
      .testTag(EPUB_READER_PREVIOUS_PAGE_TESTING_TAG),
    color = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    shadowElevation = EIGHT_DP,
    shape = MaterialTheme.shapes.large
  ) {
    Text(
      text = locator.title?.takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.epub_previous_page_in, it)
      } ?: stringResource(R.string.epub_previous_page),
      modifier = Modifier.padding(horizontal = SIXTEEN_DP, vertical = EIGHT_DP),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.labelLarge
    )
  }
}

/** Re-colours the Material scheme the overlay reads, so it follows the reading theme. */
@Composable
private fun OverlayTheme(colors: EpubOverlayColors, content: @Composable () -> Unit) {
  val background = Color(colors.background)
  val foreground = Color(colors.content)
  MaterialTheme(
    colorScheme = MaterialTheme.colorScheme.copy(
      onPrimary = background,
      surface = background,
      background = background,
      onSurface = foreground,
      onBackground = foreground,
      primary = foreground,
      secondaryContainer = foreground.copy(alpha = TRACK_ALPHA)
    ),
    typography = MaterialTheme.typography,
    shapes = MaterialTheme.shapes,
    content = content
  )
}

/** Back, title and the "Aa" button; KiwixAppBar leaves the status bar to its host. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TopBar(
  title: String,
  showSettings: Boolean,
  search: EpubSearchState,
  actions: EpubReaderActions,
  onSettings: () -> Unit
) {
  Box(
    Modifier
      .background(MaterialTheme.colorScheme.onPrimary)
      .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
  ) {
    KiwixAppBar(
      useAppTheme = false,
      title = title,
      navigationIcon = {
        NavigationIcon(onClick = if (search.isOpen) actions.onCloseSearch else actions.onBack)
      },
      searchBar = searchBar(search, actions),
      actionMenuItems = topBarActions(search, showSettings, actions, onSettings)
    )
  }
}

@Composable
private fun searchBar(search: EpubSearchState, actions: EpubReaderActions): (@Composable () -> Unit)? =
  if (search.isOpen) {
    {
      KiwixSearchView(
        modifier = Modifier.fillMaxWidth(),
        value = search.query,
        searchViewTextFiledTestTag = EPUB_READER_SEARCH_FIELD_TESTING_TAG,
        imeAction = ImeAction.Search,
        onValueChange = actions.onSearchQueryChange,
        onClearClick = { actions.onSearchQueryChange("") },
        onKeyboardSubmitButtonClick = actions.onSearch
      )
    }
  } else {
    null
  }

private fun topBarActions(
  search: EpubSearchState,
  showSettings: Boolean,
  actions: EpubReaderActions,
  onSettings: () -> Unit
): List<ActionMenuItem> = when {
  search.isOpen -> listOf(
    ActionMenuItem(
      icon = IconItem.Drawable(R.drawable.action_search),
      contentDescription = R.string.search_label,
      onClick = { actions.onSearch(search.query) },
      testingTag = EPUB_READER_SEARCH_BUTTON_TESTING_TAG
    ),
    ActionMenuItem(
      icon = IconItem.Drawable(R.drawable.ic_close_white_24dp),
      contentDescription = R.string.cancel,
      onClick = actions.onCloseSearch,
      testingTag = "epubReaderCloseSearchTestingTag"
    )
  )
  showSettings -> listOf(
    ActionMenuItem(
      icon = IconItem.Drawable(R.drawable.action_search),
      contentDescription = R.string.search_label,
      onClick = actions.onOpenSearch,
      testingTag = EPUB_READER_SEARCH_BUTTON_TESTING_TAG
    ),
    ActionMenuItem(
      icon = IconItem.Drawable(R.drawable.ic_text_size_24dp),
      contentDescription = R.string.epub_reading_settings,
      onClick = onSettings,
      testingTag = EPUB_READER_SETTINGS_TESTING_TAG
    )
  )
  else -> emptyList()
}

@Composable
private fun SearchResults(
  search: EpubSearchState,
  toc: List<EpubTocItem>,
  positionCount: Int,
  onSuggestion: (String) -> Unit,
  onSelect: (Locator) -> Unit
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = EIGHT_DP)
      .heightIn(max = 360.dp),
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = EIGHT_DP
  ) {
    SearchResultsContent(search, toc, positionCount, onSuggestion, onSelect)
  }
}

@Composable
private fun SearchResultsContent(
  search: EpubSearchState,
  toc: List<EpubTocItem>,
  positionCount: Int,
  onSuggestion: (String) -> Unit,
  onSelect: (Locator) -> Unit
) {
  when {
    search.isSearching -> SearchLoading()
    search.failed -> SearchMessage(R.string.epub_search_error)
    search.hasSearched && search.showingSuggestions && search.suggestions.isEmpty() ->
      SearchMessage(R.string.epub_search_no_words)
    search.hasSearched && !search.showingSuggestions && search.results.isEmpty() ->
      SearchMessage(R.string.epub_search_no_results)
    search.showingSuggestions -> SearchSuggestions(search.suggestions, onSuggestion)
    search.results.isNotEmpty() -> SearchResultList(search, toc, positionCount, onSelect)
    else -> Unit
  }
}

@Composable
private fun SearchLoading() {
  Box(Modifier.padding(SIXTEEN_DP), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}

@Composable
private fun SearchMessage(message: Int) {
  Text(
    text = stringResource(message),
    modifier = Modifier.padding(SIXTEEN_DP),
    color = MaterialTheme.colorScheme.onSurface
  )
}

@Composable
private fun SearchSuggestions(suggestions: List<String>, onSuggestion: (String) -> Unit) {
  Column {
    Text(
      text = stringResource(R.string.epub_search_suggestions),
      modifier = Modifier.padding(horizontal = SIXTEEN_DP, vertical = EIGHT_DP),
      style = MaterialTheme.typography.labelMedium
    )
    LazyColumn {
      items(suggestions, key = { it }) { suggestion ->
        Text(
          text = suggestion,
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onSuggestion(suggestion) }
            .padding(horizontal = SIXTEEN_DP, vertical = EIGHT_DP)
            .testTag(EPUB_READER_SEARCH_SUGGESTION_TESTING_TAG),
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.bodyLarge
        )
      }
    }
  }
}

@Composable
private fun SearchResultList(
  search: EpubSearchState,
  toc: List<EpubTocItem>,
  positionCount: Int,
  onSelect: (Locator) -> Unit
) {
  Column {
    if (search.limited) {
      Text(
        text = stringResource(R.string.epub_search_limited),
        modifier = Modifier.padding(horizontal = SIXTEEN_DP, vertical = EIGHT_DP),
        style = MaterialTheme.typography.labelMedium
      )
    }
    LazyColumn {
      itemsIndexed(search.results, key = { index, result -> "${result.locator.href}:$index" }) { _, result ->
        SearchResultRow(result, search.query, toc, positionCount, onSelect)
      }
    }
  }
}

@Composable
private fun SearchResultRow(
  result: EpubSearchResult,
  query: String,
  toc: List<EpubTocItem>,
  positionCount: Int,
  onSelect: (Locator) -> Unit
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clickable { onSelect(result.locator) }
      .padding(horizontal = SIXTEEN_DP, vertical = EIGHT_DP)
      .testTag(EPUB_READER_SEARCH_RESULT_TESTING_TAG)
  ) {
    chapterTitle(toc, result.locator)?.let { chapter ->
      Text(
        chapter,
        modifier = Modifier.testTag(EPUB_READER_SEARCH_CHAPTER_TESTING_TAG),
        style = MaterialTheme.typography.labelMedium
      )
    }
    val progress = readingProgress(result.locator, positionCount)
    if (progress.position > 0) {
      Text(
        text = stringResource(R.string.epub_position_of, progress.position, progress.total),
        modifier = Modifier.testTag(EPUB_READER_SEARCH_POSITION_TESTING_TAG),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
      )
    }
    Text(
      text = highlightedSearchText(
        result.snippet,
        result.locator.text.highlight?.takeIf { it.isNotBlank() } ?: query
      ),
      color = MaterialTheme.colorScheme.onSurface
    )
  }
}

@Composable
private fun highlightedSearchText(text: String, query: String) = buildAnnotatedString {
  val start = query.takeIf { it.isNotBlank() }?.let { text.indexOf(it, ignoreCase = true) } ?: -1
  if (start < 0) {
    append(text)
  } else {
    append(text.substring(0, start))
    withStyle(
      SpanStyle(
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        fontWeight = FontWeight.Bold
      )
    ) {
      append(text.substring(start, start + query.length))
    }
    append(text.substring(start + query.length))
  }
}

/**
 * The scrubber preview. It survives release until the navigator reports the new locator, or a
 * short time passes: a seek onto the current locator reports nothing new.
 */
private class SeekPreview {
  var drag by mutableStateOf<Float?>(null)
  var released by mutableStateOf(false)
}

@Composable
private fun rememberSeekPreview(locator: Locator?): SeekPreview {
  val preview = remember(locator) { SeekPreview() }
  LaunchedEffect(preview.released) {
    if (preview.released) {
      delay(SEEK_SETTLE_MS)
      preview.drag = null
      preview.released = false
    }
  }
  return preview
}

/** Footer: chapter and table of contents, a seekable progress bar, position label. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomBar(
  book: OpenEpub,
  reading: EpubReadingState,
  actions: EpubReaderActions,
  onToc: () -> Unit
) {
  val progress = readingProgress(reading.locator, reading.positionCount)
  val preview = rememberSeekPreview(reading.locator)
  val shown = preview.drag ?: progress.fraction
  val position =
    preview.drag?.let { positionForFraction(it, reading.positionCount) } ?: progress.position
  Surface(
    color = MaterialTheme.colorScheme.onPrimary,
    contentColor = MaterialTheme.colorScheme.onSurface
  ) {
    Column(
      Modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
        .padding(horizontal = SIXTEEN_DP, vertical = EIGHT_DP)
    ) {
      ChapterRow(chapterTitle(book.toc, reading.locator).orEmpty(), onToc)
      SeekRow(shown, preview, reading, actions, book.rtl)
      PositionLabels(shown, position, reading.positionCount)
    }
  }
}

@Composable
private fun SeekRow(
  shown: Float,
  preview: SeekPreview,
  reading: EpubReadingState,
  actions: EpubReaderActions,
  rtl: Boolean
) {
  // The scrubber runs the way the book reads, whatever the app's own layout direction.
  val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
  CompositionLocalProvider(LocalLayoutDirection provides direction) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      ChapterButton(actions.onPreviousChapter, reading.hasPreviousChapter, rtl, previous = true)
      Slider(
        value = shown,
        onValueChange = {
          preview.drag = it
          preview.released = false
        },
        onValueChangeFinished = {
          preview.drag?.let(actions.onSeek)
          preview.released = true
        },
        enabled = reading.positionCount > 0,
        modifier = Modifier
          .weight(1f)
          .testTag(EPUB_READER_PROGRESS_SLIDER_TESTING_TAG)
      )
      ChapterButton(actions.onNextChapter, reading.hasNextChapter, rtl, previous = false)
    }
  }
}

/** The icons point left and right, so they mirror when the book reads right to left. */
@Composable
private fun ChapterButton(onClick: () -> Unit, enabled: Boolean, rtl: Boolean, previous: Boolean) {
  val tag = if (previous) {
    EPUB_READER_PREVIOUS_CHAPTER_TESTING_TAG
  } else {
    EPUB_READER_NEXT_CHAPTER_TESTING_TAG
  }
  IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
    Icon(
      painterResource(if (previous) R.drawable.ic_skip_previous_24dp else R.drawable.ic_skip_next_24dp),
      stringResource(if (previous) R.string.go_to_previous_chapter else R.string.go_to_next_chapter),
      Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f }
    )
  }
}

@Composable
private fun PositionLabels(fraction: Float, position: Int, positionCount: Int) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(
      text = if (position > 0) stringResource(R.string.epub_position_of, position, positionCount) else "",
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.testTag(EPUB_READER_PAGE_LABEL_TESTING_TAG)
    )
    Text(
      text = stringResource(R.string.epub_percent_read, (fraction * PERCENT).roundToInt()),
      style = MaterialTheme.typography.labelMedium
    )
  }
}

@Composable
private fun ChapterRow(title: String, onToc: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
    )
    IconButton(onClick = onToc, modifier = Modifier.testTag(EPUB_READER_TOC_TESTING_TAG)) {
      Icon(painterResource(R.drawable.ic_toc_24dp), stringResource(R.string.table_of_contents))
    }
  }
}

@Composable
private fun LoadingContent(colors: EpubOverlayColors) {
  // Not a Surface: that swallows touches, and the bars drawn over this must stay reachable.
  Box(
    Modifier
      .fillMaxSize()
      .background(Color(colors.background)),
    contentAlignment = Alignment.Center
  ) {
    CircularProgressIndicator(
      Modifier.testTag(EPUB_READER_LOADING_TESTING_TAG),
      color = Color(colors.content)
    )
  }
}

@Composable
private fun ErrorContent(onClose: () -> Unit) {
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
      Modifier
        .fillMaxSize()
        .padding(SIXTEEN_DP),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = stringResource(R.string.epub_open_failed),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag(EPUB_READER_ERROR_TESTING_TAG)
      )
      Button(onClick = onClose, modifier = Modifier.padding(top = SIXTEEN_DP)) {
        Text(stringResource(R.string.epub_close))
      }
    }
  }
}

@Composable
private fun TocDialog(
  toc: List<EpubTocItem>,
  currentLocator: Locator?,
  onDismiss: () -> Unit,
  onItem: (EpubTocItem) -> Unit
) {
  val currentHref = currentLocator?.href?.removeFragment()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.table_of_contents)) },
    text = {
      if (toc.isEmpty()) {
        Text(stringResource(R.string.epub_no_table_of_contents))
      } else {
        LazyColumn {
          items(toc) { item ->
            val isCurrent = currentHref != null && item.link.url().removeFragment() == currentHref
            Text(
              text = item.title,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onItem(item) }
                .padding(start = (item.depth * SIXTEEN_DP.value).dp, top = EIGHT_DP, bottom = EIGHT_DP)
                .testTag(EPUB_READER_TOC_ITEM_TESTING_TAG)
            )
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.epub_close)) } }
  )
}
