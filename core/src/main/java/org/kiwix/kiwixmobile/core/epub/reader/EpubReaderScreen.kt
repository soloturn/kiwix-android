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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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
const val EPUB_READER_SEARCH_PANEL_TESTING_TAG = "epubReaderSearchPanelTestingTag"
const val EPUB_READER_SEARCH_RESULTS_LIST_TESTING_TAG = "epubReaderSearchResultsListTestingTag"
const val EPUB_READER_PREVIOUS_PAGE_TESTING_TAG = "epubReaderPreviousPageTestingTag"
const val EPUB_READER_BOOKMARK_TESTING_TAG = "epubReaderBookmarkTestingTag"
const val EPUB_READER_ANNOTATIONS_TESTING_TAG = "epubReaderAnnotationsTestingTag"
const val EPUB_READER_ANNOTATION_DELETE_TESTING_TAG = "epubReaderAnnotationDeleteTestingTag"
const val EPUB_READER_ANNOTATION_COPY_TESTING_TAG = "epubReaderAnnotationCopyTestingTag"
const val EPUB_READER_SELECTION_DELETE_TESTING_TAG = "epubReaderSelectionDeleteTestingTag"
const val EPUB_READER_PAGE_OVERVIEW_TESTING_TAG = "epubReaderPageOverviewTestingTag"
const val EPUB_READER_PAGE_CAROUSEL_TESTING_TAG = "epubReaderPageCarouselTestingTag"
const val EPUB_READER_PAGE_OVERVIEW_BUTTON_TESTING_TAG = "epubReaderPageOverviewButtonTestingTag"
const val EPUB_READER_PAGE_THUMBNAIL_TESTING_TAG = "epubReaderPageThumbnailTestingTag"

private const val PERCENT = 100
private const val TRACK_ALPHA = 0.24f
private const val PAGE_OVERVIEW_GRID_COLUMNS = 3
private const val PAGE_OVERVIEW_LOAD_THRESHOLD = PAGE_OVERVIEW_GRID_COLUMNS * 2
private const val SEEK_SETTLE_MS = 800L
private const val ANNOTATION_CONTEXT_CHARS = 48
private val ANNOTATION_DIALOG_MAX_HEIGHT = 700.dp
private val ANNOTATION_LIST_MAX_HEIGHT = 360.dp

private class AnnotationFilterState(initialAnnotations: List<EpubAnnotation>) {
  var annotations by mutableStateOf(initialAnnotations)
    private set
  val chapters get() = annotations.map(EpubAnnotation::displayTitle).distinct().sorted()
  var kinds by mutableStateOf(EpubAnnotationKind.entries.toSet())
  var colors by mutableStateOf(EpubHighlightColor.entries.toSet())
  var selectedChapters by mutableStateOf(chapters.toSet())
  val visibleAnnotations
    get() = annotations.filter { annotation ->
      annotation.kind in kinds &&
        (annotation.color == null || annotation.color in colors) &&
        annotation.displayTitle() in selectedChapters
    }

  fun toggle(kind: EpubAnnotationKind) {
    kinds = kinds.toggle(kind)
  }

  fun toggle(color: EpubHighlightColor) {
    colors = colors.toggle(color)
  }

  fun toggle(chapter: String) {
    selectedChapters = selectedChapters.toggle(chapter)
  }

  fun updateAnnotations(updated: List<EpubAnnotation>) {
    if (updated == annotations) return
    val oldChapters = chapters.toSet()
    annotations = updated
    val newChapters = chapters.toSet()
    selectedChapters = selectedChapters.intersect(oldChapters) + (newChapters - oldChapters)
  }
}

private fun <T> Set<T>.toggle(value: T) = if (value in this) this - value else this + value

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
  val onReturnToPreviousPage: (Locator) -> Unit = {},
  val onBookmark: () -> Unit = {},
  val onShowAnnotations: () -> Unit = {},
  val onAnnotationSelected: (EpubAnnotation) -> Unit = {},
  val onDeleteAnnotation: (EpubAnnotation) -> Unit = {},
  val onUpdateAnnotationNote: (EpubAnnotation, String) -> Unit = { _, _ -> },
  val onCopyAnnotation: (EpubAnnotation) -> Unit = {},
  val onPageModeSelected: (EpubPageMode) -> Unit = {},
  val onLoadMoreOverviewPages: (Boolean) -> Unit = {},
  val onSelectOverviewPage: (Locator) -> Unit = {},
  val onSelectCarouselPage: (Locator) -> Unit = {},
  val onCarouselPositionChanged: (Float) -> Unit = {},
  val onDismissSelectionMenu: () -> Unit = {},
  val onCopySelection: (String) -> Unit = {},
  val onShareSelection: (String) -> Unit = {},
  val onSearchSelection: (String) -> Unit = {},
  val onSelectAll: () -> Unit = {},
  val onBookmarkSelection: (Locator) -> Unit = {},
  val onHighlightSelection: (Locator, EpubHighlightColor) -> Unit = { _, _ -> },
  val onNoteSelection: (Locator, String) -> Unit = { _, _ -> }
)

enum class EpubPageMode { SINGLE_PAGE, CAROUSEL, OVERVIEW }

data class EpubPagePreview(
  val locator: Locator,
  val cacheKey: String,
  val pageIndex: Int = -1,
  val resourceHref: String = locator.href.toString(),
  val totalPages: Int = -1,
  val chapterTitle: String = ""
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
  var showAnnotations by mutableStateOf(false)
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
  previousPages: List<Locator> = emptyList(),
  pageMode: EpubPageMode = EpubPageMode.SINGLE_PAGE,
  pageModesAvailable: Boolean = true,
  pagePreviews: List<EpubPagePreview> = emptyList(),
  overviewPages: List<EpubPagePreview> = emptyList(),
  overviewPagesLoading: Boolean = false,
  overviewHasPrevious: Boolean = false,
  overviewHasNext: Boolean = false,
  loadPreviewBitmap: suspend (EpubPagePreview) -> Bitmap? = { null },
  annotations: List<EpubAnnotation> = emptyList(),
  selectionLocator: Locator? = null
) {
  val ready = state as? EpubReaderUiState.Ready
  val shownPageMode = if (pageModesAvailable) pageMode else EpubPageMode.SINGLE_PAGE
  val currentPageBookmarked = isCurrentPageBookmarked(reading, annotations)
  val selectedAnnotation = selectionLocator?.let { selected ->
    annotations.firstOrNull { it.matchesSelection(selected) }
  }
  val showBookmark = shouldShowBookmarkMargin(ready, pageReady, reading, chromeVisible, shownPageMode)
  val searchResultsListState = rememberLazyListState()
  val systemNightMode = isSystemInDarkTheme()
  val overlay = settings.overlayColors(systemNightMode)
  val darkReadingTheme = settings.effectivePreset(systemNightMode) in setOf(
    EpubThemePreset.DARK,
    EpubThemePreset.BLACK
  )
  val chromeState = ReaderChromeState(
    ready,
    reading,
    chromeVisible,
    pageReady,
    previousPages,
    shownPageMode,
    pageModesAvailable,
    pagePreviews,
    overviewPages
  )
  val layoutState = EpubReaderMainLayoutState(
    uiState = state,
    ready = ready,
    reading = reading,
    pageReady = pageReady,
    pageMode = shownPageMode,
    pagePreviews = pagePreviews,
    loadPreviewBitmap = loadPreviewBitmap,
    currentPageBookmarked = currentPageBookmarked,
    showBookmark = showBookmark,
    overlay = overlay,
    chrome = chromeState,
    overviewPages = overviewPages,
    overview = PageOverviewState(overviewPagesLoading, overviewHasPrevious, overviewHasNext),
    overviewActions = PageOverviewActions(
      actions.onLoadMoreOverviewPages,
      actions.onPageModeSelected,
      actions.onSelectOverviewPage,
      loadPreviewBitmap
    )
  )
  KiwixTheme {
    EpubReaderPageLayout(layoutState, panels, search, actions, searchResultsListState)
    ReaderPanels(ready, reading, settings, panels, actions, annotations)
    selectionLocator?.let {
      SelectionActionsDialog(it, actions, darkReadingTheme, selectedAnnotation)
    }
  }
}

private fun isCurrentPageBookmarked(
  reading: EpubReadingState,
  annotations: List<EpubAnnotation>
) = reading.locator?.let { currentLocator ->
  annotations.any {
    it.kind == EpubAnnotationKind.BOOKMARK && it.locator.sameBookmarkPosition(currentLocator)
  }
} == true

private data class EpubReaderMainLayoutState(
  val uiState: EpubReaderUiState,
  val ready: EpubReaderUiState.Ready?,
  val reading: EpubReadingState,
  val pageReady: Boolean,
  val pageMode: EpubPageMode,
  val pagePreviews: List<EpubPagePreview>,
  val loadPreviewBitmap: suspend (EpubPagePreview) -> Bitmap?,
  val currentPageBookmarked: Boolean,
  val showBookmark: Boolean,
  val overlay: EpubOverlayColors,
  val chrome: ReaderChromeState,
  val overviewPages: List<EpubPagePreview>,
  val overview: PageOverviewState,
  val overviewActions: PageOverviewActions
)

@Composable
private fun EpubReaderPageLayout(
  state: EpubReaderMainLayoutState,
  panels: EpubReaderPanels,
  search: EpubSearchState,
  actions: EpubReaderActions,
  searchResultsListState: LazyListState
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    EpubReaderPageLayers(state, panels, search, actions, searchResultsListState)
  }
}

@Composable
private fun BoxWithConstraintsScope.EpubReaderPageLayers(
  state: EpubReaderMainLayoutState,
  panels: EpubReaderPanels,
  search: EpubSearchState,
  actions: EpubReaderActions,
  searchResultsListState: LazyListState
) {
  when {
    state.uiState == EpubReaderUiState.Failed -> ErrorContent(actions.onBack)
    state.uiState == EpubReaderUiState.Loading || !state.pageReady -> LoadingContent(state.overlay)
  }
  OverlayTheme(state.overlay) {
    CarouselPageLayer(state, actions)
    ReaderChrome(state.chrome, panels, search, actions, searchResultsListState)
  }
  PageBookmarkToggle(
    state.showBookmark,
    state.currentPageBookmarked,
    actions.onBookmark,
    state.pageMode
  )
  PageOverviewLayer(state)
}

@Composable
private fun CarouselPageLayer(state: EpubReaderMainLayoutState, actions: EpubReaderActions) {
  if (state.pageMode != EpubPageMode.CAROUSEL || state.ready == null || state.pagePreviews.isEmpty()) {
    return
  }
  PageCarousel(
    pages = state.pagePreviews,
    currentLocator = state.reading.locator,
    onPositionChanged = actions.onCarouselPositionChanged,
    onPageSettled = actions.onSelectCarouselPage,
  )
}

@Composable
private fun PageOverviewLayer(state: EpubReaderMainLayoutState) {
  if (state.pageMode != EpubPageMode.OVERVIEW) return
  PageOverviewGrid(state.overviewPages, state.overview, state.overviewActions)
}

private fun shouldShowBookmarkMargin(
  ready: EpubReaderUiState.Ready?,
  pageReady: Boolean,
  reading: EpubReadingState,
  chromeVisible: Boolean,
  pageMode: EpubPageMode
) = ready != null &&
  pageReady &&
  reading.locator != null &&
  (pageMode == EpubPageMode.CAROUSEL || (!chromeVisible && pageMode == EpubPageMode.SINGLE_PAGE))

@Composable
private fun BoxWithConstraintsScope.PageBookmarkToggle(
  visible: Boolean,
  bookmarked: Boolean,
  onClick: () -> Unit,
  pageMode: EpubPageMode
) {
  if (!visible) return
  val centered = pageMode == EpubPageMode.CAROUSEL
  val topOffset = if (centered) {
    maxHeight * ((1f - EpubReaderActivity.CAROUSEL_PAGE_HEIGHT) / 2f) + EIGHT_DP
  } else {
    EIGHT_DP
  }
  BookmarkMarginButton(bookmarked, onClick, centered, topOffset)
}

@Composable
private fun BoxScope.BookmarkMarginButton(
  bookmarked: Boolean,
  onClick: () -> Unit,
  centered: Boolean,
  topOffset: Dp
) {
  Surface(
    modifier = Modifier
      .align(if (centered) Alignment.TopCenter else Alignment.TopEnd)
      .offset(y = topOffset)
      .then(if (centered) Modifier else Modifier.padding(end = EIGHT_DP))
      .shadow(EIGHT_DP, CircleShape),
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surface
  ) {
    IconButton(
      onClick = onClick,
      modifier = Modifier.testTag(EPUB_READER_BOOKMARK_TESTING_TAG)
    ) {
      Icon(
        painter = painterResource(
          if (bookmarked) R.drawable.ic_bookmark_24dp else R.drawable.ic_bookmark_border_24dp
        ),
        contentDescription = stringResource(
          if (bookmarked) R.string.epub_remove_bookmark else R.string.epub_add_bookmark
        ),
        tint = MaterialTheme.colorScheme.primary
      )
    }
  }
}

@Composable
private fun SelectionActionsDialog(
  locator: Locator,
  actions: EpubReaderActions,
  darkReadingTheme: Boolean,
  annotation: EpubAnnotation?
) {
  var editingNote by remember(locator) { mutableStateOf(false) }
  var choosingHighlightColor by remember(locator) { mutableStateOf(false) }
  var noteText by remember(locator) { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = actions.onDismissSelectionMenu,
    title = {
      Text(
        stringResource(if (editingNote) R.string.epub_add_note else R.string.epub_selection_actions)
      )
    },
    text = {
      if (editingNote) {
        SelectionNoteEditor(noteText, onNoteChange = { noteText = it })
      } else if (choosingHighlightColor) {
        HighlightColorGrid(darkReadingTheme) { color ->
          actions.onHighlightSelection(locator, color)
          actions.onDismissSelectionMenu()
        }
      } else {
        SelectionActionGrid(
          locator,
          actions,
          annotation,
          onAddNote = { editingNote = true },
          onChooseHighlightColor = { choosingHighlightColor = true }
        )
      }
    },
    confirmButton = {
      if (editingNote) {
        TextButton(
          onClick = {
            if (noteText.isNotBlank()) actions.onNoteSelection(locator, noteText)
            actions.onDismissSelectionMenu()
          }
        ) {
          Text(stringResource(R.string.save))
        }
      }
    },
  )
}

@Composable
private fun SelectionNoteEditor(noteText: String, onNoteChange: (String) -> Unit) {
  androidx.compose.material3.OutlinedTextField(
    value = noteText,
    onValueChange = onNoteChange,
    label = { Text(stringResource(R.string.epub_note_hint)) },
    modifier = Modifier.fillMaxWidth()
  )
}

@Composable
private fun SelectionActionGrid(
  locator: Locator,
  actions: EpubReaderActions,
  annotation: EpubAnnotation?,
  onAddNote: () -> Unit,
  onChooseHighlightColor: () -> Unit
) {
  val selectedText = locator.text.highlight.orEmpty()
  Column {
    SelectedTextExcerpt(selectedText)
    Column(verticalArrangement = Arrangement.spacedBy(EIGHT_DP)) {
      CopyShareSelectionActions(actions, selectedText)
      SearchSelectionActions(actions, selectedText)
      AnnotationSelectionActions(locator, annotation, actions)
      HighlightNoteSelectionActions(onChooseHighlightColor, onAddNote)
    }
  }
}

@Composable
private fun SelectedTextExcerpt(selectedText: String) {
  if (selectedText.isNotBlank()) {
    Text(
      text = selectedText,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(bottom = EIGHT_DP)
    )
  }
}

@Composable
private fun CopyShareSelectionActions(actions: EpubReaderActions, selectedText: String) {
  SelectionActionRow(
    SelectionMenuItem(stringResource(android.R.string.copy), R.drawable.ic_file_copy) {
      actions.onCopySelection(selectedText)
    },
    SelectionMenuItem(stringResource(R.string.share), R.drawable.baseline_share_24) {
      actions.onShareSelection(selectedText)
    }
  )
}

@Composable
private fun SearchSelectionActions(actions: EpubReaderActions, selectedText: String) {
  SelectionActionRow(
    SelectionMenuItem(stringResource(R.string.epub_search_web), R.drawable.action_search) {
      actions.onSearchSelection(selectedText)
    },
    SelectionMenuItem(stringResource(R.string.epub_select_all), R.drawable.ic_select_all_24) {
      actions.onSelectAll()
    }
  )
}

@Composable
private fun AnnotationSelectionActions(
  locator: Locator,
  annotation: EpubAnnotation?,
  actions: EpubReaderActions
) {
  SelectionActionRow(
    SelectionMenuItem(stringResource(R.string.epub_add_bookmark), R.drawable.ic_bookmark_24dp) {
      actions.onBookmarkSelection(locator)
      actions.onDismissSelectionMenu()
    },
    annotation?.let { existingAnnotation ->
      SelectionMenuItem(
        stringResource(R.string.delete),
        R.drawable.ic_delete_white_24dp,
        testingTag = EPUB_READER_SELECTION_DELETE_TESTING_TAG,
        onClick = {
          actions.onDeleteAnnotation(existingAnnotation)
          actions.onDismissSelectionMenu()
        }
      )
    }
  )
}

@Composable
private fun HighlightNoteSelectionActions(
  onChooseHighlightColor: () -> Unit,
  onAddNote: () -> Unit
) {
  SelectionActionRow(
    SelectionMenuItem(
      stringResource(R.string.epub_highlight),
      R.drawable.ic_highlight_24,
      onClick = onChooseHighlightColor
    ),
    SelectionMenuItem(
      stringResource(R.string.epub_add_note),
      R.drawable.ic_add_note,
      onClick = onAddNote
    )
  )
}

@Composable
private fun HighlightColorGrid(darkReadingTheme: Boolean, onColor: (EpubHighlightColor) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(EIGHT_DP)) {
    EpubHighlightColor.entries.chunked(2).forEach { rowColors ->
      Row(horizontalArrangement = Arrangement.spacedBy(EIGHT_DP)) {
        rowColors.forEach { color ->
          Surface(
            modifier = Modifier.weight(1f).clickable { onColor(color) },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant
          ) {
            Row(
              Modifier.padding(EIGHT_DP),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(EIGHT_DP)
            ) {
              Surface(
                Modifier.size(28.dp),
                shape = CircleShape,
                color = Color(color.hexFor(darkReadingTheme).toColorInt())
              ) {}
              Text(stringResource(color.labelResource()))
            }
          }
        }
        if (rowColors.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

private data class SelectionMenuItem(
  val label: String,
  val icon: Int,
  val testingTag: String? = null,
  val onClick: () -> Unit
)

@Composable
private fun SelectionActionRow(first: SelectionMenuItem, second: SelectionMenuItem? = null) {
  Row(horizontalArrangement = Arrangement.spacedBy(EIGHT_DP)) {
    SelectionActionTile(
      first.label,
      first.icon,
      Modifier.weight(1f),
      first.onClick,
      first.testingTag
    )
    if (second != null) {
      SelectionActionTile(
        second.label,
        second.icon,
        Modifier.weight(1f),
        second.onClick,
        second.testingTag
      )
    } else {
      Spacer(Modifier.weight(1f))
    }
  }
}

@Composable
private fun SelectionActionTile(
  label: String,
  icon: Int,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
  testingTag: String? = null
) {
  Surface(
    modifier = modifier.height(72.dp)
      .clickable(onClick = onClick)
      .then(if (testingTag != null) Modifier.testTag(testingTag) else Modifier),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceVariant
  ) {
    Row(
      Modifier.fillMaxSize().padding(horizontal = SIXTEEN_DP),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(EIGHT_DP)
    ) {
      Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      Text(
        text = label,
        textAlign = TextAlign.Start,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge
      )
    }
  }
}

private data class ReaderChromeState(
  val ready: EpubReaderUiState.Ready?,
  val reading: EpubReadingState,
  val chromeVisible: Boolean,
  val pageReady: Boolean,
  val previousPages: List<Locator>,
  val pageMode: EpubPageMode,
  val pageModesAvailable: Boolean,
  val pagePreviews: List<EpubPagePreview>,
  val overviewPages: List<EpubPagePreview>
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BoxScope.ReaderChrome(
  state: ReaderChromeState,
  panels: EpubReaderPanels,
  search: EpubSearchState,
  actions: EpubReaderActions,
  searchResultsListState: LazyListState
) {
  val ready = state.ready
  val reading = state.reading
  val chromeVisible = state.chromeVisible
  val pageReady = state.pageReady
  val previousPages = state.previousPages
  AnimatedVisibility(
    visible = state.pageMode != EpubPageMode.OVERVIEW &&
      (ready == null || chromeVisible || !pageReady || panels.showSearch),
    modifier = Modifier.align(Alignment.TopCenter),
    enter = fadeIn(),
    exit = fadeOut()
  ) {
    Column(if (search.isOpen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
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
          actions.onSearchResult,
          Modifier.weight(1f),
          searchResultsListState
        )
      }
    }
  }
  AnimatedVisibility(
    visible = ready != null && chromeVisible && state.pageMode != EpubPageMode.OVERVIEW,
    modifier = Modifier.align(Alignment.BottomCenter),
    enter = fadeIn(),
    exit = fadeOut()
  ) {
    if (ready != null) {
      BottomBar(
        ready.book,
        reading,
        actions,
        state.pageModesAvailable,
        onToc = { panels.showToc = true }
      )
    }
  }
  val visiblePreviousPages = previousPages.takeIf { state.shouldShowPreviousPages(search.isOpen) }.orEmpty()
  if (visiblePreviousPages.isNotEmpty()) {
    PreviousPageChips(visiblePreviousPages, reading.positionCount, actions.onReturnToPreviousPage)
  }
}

private fun ReaderChromeState.shouldShowPreviousPages(searchOpen: Boolean) =
  ready != null && pageReady && chromeVisible && !searchOpen && pageMode != EpubPageMode.OVERVIEW

@Composable
private fun PageCarousel(
  pages: List<EpubPagePreview>,
  currentLocator: Locator?,
  onPositionChanged: (Float) -> Unit,
  onPageSettled: (Locator) -> Unit,
) {
  val currentPages by rememberUpdatedState(pages)
  val currentOnPageSettled by rememberUpdatedState(onPageSettled)
  val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
  val currentIndex = currentLocator?.let { current ->
    pages.indexOfFirst { it.locator.sameBookmarkPosition(current) }
      .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
  } ?: 0
  val pagerState = rememberPagerState(initialPage = currentIndex) { pages.size }
  LaunchedEffect(currentIndex) {
    if (pagerState.currentPage != currentIndex) {
      pagerState.scrollToPage(currentIndex)
    }
  }
  LaunchedEffect(pagerState) {
    var wasScrolling = false
    snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
      .collect { (scrolling, page) ->
        if (scrolling) {
          wasScrolling = true
        } else if (wasScrolling) {
          wasScrolling = false
          currentPages.getOrNull(page)?.let { currentOnPageSettled(it.locator) }
        }
      }
  }
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
      .collect { currentOnPositionChanged(it) }
  }
  HorizontalPager(
    state = pagerState,
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 48.dp),
    pageSpacing = SIXTEEN_DP,
    beyondViewportPageCount = 1,
    key = { page -> pages[page].cacheKey },
    verticalAlignment = Alignment.CenterVertically
  ) { page ->
    Box(Modifier.fillMaxSize().testTag("${EPUB_READER_PAGE_CAROUSEL_TESTING_TAG}_$page"))
  }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BoxScope.PreviousPageChips(
  pages: List<Locator>,
  positionCount: Int,
  onSelect: (Locator) -> Unit
) {
  Column(
    modifier = Modifier
      .align(Alignment.BottomEnd)
      .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
      .padding(end = SIXTEEN_DP, bottom = 88.dp),
    horizontalAlignment = Alignment.End,
    verticalArrangement = Arrangement.spacedBy(EIGHT_DP)
  ) {
    pages.forEach { page ->
      PreviousPageChip(page, positionCount) { onSelect(page) }
    }
  }
}

@Composable
private fun ReaderPanels(
  ready: EpubReaderUiState.Ready?,
  reading: EpubReadingState,
  settings: EpubReaderSettings,
  panels: EpubReaderPanels,
  actions: EpubReaderActions,
  annotations: List<EpubAnnotation>
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
  if (panels.showAnnotations) {
    val systemNightMode = isSystemInDarkTheme()
    AnnotationsDialog(
      annotations,
      AnnotationDisplayOptions(
        reading.positionCount,
        settings.effectivePreset(systemNightMode) in setOf(EpubThemePreset.DARK, EpubThemePreset.BLACK)
      ),
      AnnotationDialogActions(
        onDismiss = { panels.showAnnotations = false },
        onItem = {
          panels.showAnnotations = false
          actions.onAnnotationSelected(it)
        },
        onDelete = actions.onDeleteAnnotation,
        onUpdateNote = actions.onUpdateAnnotationNote,
        onCopy = actions.onCopyAnnotation
      )
    )
  }
}

@Composable
private fun PageOverviewGrid(
  pages: List<EpubPagePreview>,
  state: PageOverviewState,
  actions: PageOverviewActions
) {
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().padding(SIXTEEN_DP)) {
      PageOverviewHeader(actions.onModeSelected)
      if (pages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      } else {
        PageOverviewGridContent(
          pages,
          state,
          actions,
          Modifier.weight(1f)
        )
      }
    }
  }
}

@Composable
private fun PageOverviewHeader(onModeSelected: (EpubPageMode) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      stringResource(R.string.epub_page_overview),
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.titleLarge
    )
    TextButton(onClick = { onModeSelected(EpubPageMode.CAROUSEL) }) {
      Text(stringResource(R.string.epub_page_carousel))
    }
  }
}

@Composable
private fun PageOverviewGridContent(
  pages: List<EpubPagePreview>,
  state: PageOverviewState,
  actions: PageOverviewActions,
  modifier: Modifier
) {
  val gridState = rememberLazyGridState()
  PageOverviewLoadMoreEffect(gridState, pages.size, state, actions.onLoadMore)
  LazyVerticalGrid(
    columns = GridCells.Fixed(PAGE_OVERVIEW_GRID_COLUMNS),
    state = gridState,
    modifier = modifier.testTag(EPUB_READER_PAGE_OVERVIEW_TESTING_TAG),
    horizontalArrangement = Arrangement.spacedBy(EIGHT_DP),
    verticalArrangement = Arrangement.spacedBy(EIGHT_DP)
  ) {
    pages.forEachIndexed { index, preview ->
      if (isNewPreviewChapter(pages, index, preview)) {
        item(
          key = "chapter-${preview.resourceHref}-${preview.chapterTitle}",
          span = { GridItemSpan(maxLineSpan) }
        ) {
          Text(
            preview.chapterTitle,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(top = EIGHT_DP, bottom = 4.dp)
          )
        }
      }
      item(key = "page-${preview.resourceHref}-${preview.pageIndex}") {
        PagePreviewCard(
          preview,
          index,
          actions.loadPreviewBitmap,
          Modifier.fillMaxWidth().height(256.dp)
        ) {
          actions.onSelect(preview.locator)
          actions.onModeSelected(EpubPageMode.CAROUSEL)
        }
      }
    }
    if (state.loading) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        CircularProgressIndicator(Modifier.padding(EIGHT_DP).size(24.dp))
      }
    }
  }
}

private data class PageOverviewState(
  val loading: Boolean,
  val hasPrevious: Boolean,
  val hasNext: Boolean
)

private data class PageOverviewActions(
  val onLoadMore: (Boolean) -> Unit,
  val onModeSelected: (EpubPageMode) -> Unit,
  val onSelect: (Locator) -> Unit,
  val loadPreviewBitmap: suspend (EpubPagePreview) -> Bitmap?
)

private fun isNewPreviewChapter(
  pages: List<EpubPagePreview>,
  index: Int,
  preview: EpubPagePreview
) = preview.chapterTitle.isNotBlank() &&
  (index == 0 || pages[index - 1].chapterTitle != preview.chapterTitle)

@Composable
private fun PageOverviewLoadMoreEffect(
  gridState: LazyGridState,
  pageCount: Int,
  state: PageOverviewState,
  onLoadMore: (Boolean) -> Unit
) {
  var requestedLoad by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
  val currentOnLoadMore by rememberUpdatedState(onLoadMore)
  val currentLoading by rememberUpdatedState(state.loading)
  val currentHasPrevious by rememberUpdatedState(state.hasPrevious)
  val currentHasNext by rememberUpdatedState(state.hasNext)
  val currentPageCount by rememberUpdatedState(pageCount)
  LaunchedEffect(gridState) {
    snapshotFlow {
      overviewLoadRequest(
        gridState.firstVisibleItemIndex,
        gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
        currentPageCount,
        currentLoading,
        currentHasPrevious,
        currentHasNext
      )
    }.collect { request ->
      if (request != null && requestedLoad != request) {
        requestedLoad = request
        currentOnLoadMore(request.first)
      }
    }
  }
}

private fun overviewLoadRequest(
  firstVisible: Int,
  lastVisible: Int,
  pageCount: Int,
  loading: Boolean,
  hasPrevious: Boolean,
  hasNext: Boolean
): Pair<Boolean, Int>? = if (loading) {
  null
} else {
  val direction = when {
    firstVisible <= PAGE_OVERVIEW_LOAD_THRESHOLD && hasPrevious -> true
    lastVisible >= pageCount - PAGE_OVERVIEW_LOAD_THRESHOLD && hasNext -> false
    else -> null
  }
  direction?.let { it to pageCount }
}

@Composable
private fun PagePreviewCard(
  preview: EpubPagePreview,
  index: Int,
  loadPreviewBitmap: suspend (EpubPagePreview) -> Bitmap?,
  modifier: Modifier,
  onClick: () -> Unit
) {
  val bitmap by produceState<Bitmap?>(null, preview.cacheKey) {
    value = loadPreviewBitmap(preview)
  }
  Surface(
    modifier = modifier.clickable(onClick = onClick)
      .testTag("${EPUB_READER_PAGE_THUMBNAIL_TESTING_TAG}_$index"),
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = EIGHT_DP
  ) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
      if (bitmap != null) {
        Image(
          bitmap = bitmap!!.asImageBitmap(),
          contentDescription = stringResource(R.string.epub_page_overview),
          contentScale = ContentScale.Fit,
          modifier = Modifier.weight(1f).fillMaxWidth().padding(EIGHT_DP)
        )
      } else {
        Box(Modifier.weight(1f).fillMaxWidth())
      }
      Text(
        stringResource(R.string.epub_page_number, preview.pageIndex + 1),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = EIGHT_DP)
      )
    }
  }
}

@Composable
private fun PreviousPageChip(
  locator: Locator,
  positionCount: Int,
  onClick: () -> Unit
) {
  val page = readingProgress(locator, positionCount).position.takeIf { it > 0 }
    ?: locator.locations.position ?: 1
  Surface(
    modifier = Modifier
      .clickable(onClick = onClick)
      .testTag(EPUB_READER_PREVIOUS_PAGE_TESTING_TAG),
    color = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    shadowElevation = EIGHT_DP,
    shape = MaterialTheme.shapes.large
  ) {
    Text(
      text = stringResource(R.string.epub_back_to_page, page),
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
  onSelect: (Locator) -> Unit,
  modifier: Modifier,
  searchResultsListState: LazyListState
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .testTag(EPUB_READER_SEARCH_PANEL_TESTING_TAG),
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = EIGHT_DP
  ) {
    SearchResultsContent(search, toc, positionCount, onSuggestion, onSelect, searchResultsListState)
  }
}

@Composable
private fun SearchResultsContent(
  search: EpubSearchState,
  toc: List<EpubTocItem>,
  positionCount: Int,
  onSuggestion: (String) -> Unit,
  onSelect: (Locator) -> Unit,
  searchResultsListState: LazyListState
) {
  when {
    search.isSearching -> SearchLoading()
    search.failed -> SearchMessage(R.string.epub_search_error)
    search.hasSearched && search.showingSuggestions && search.suggestions.isEmpty() ->
      SearchMessage(R.string.epub_search_no_words)
    search.hasSearched && !search.showingSuggestions && search.results.isEmpty() ->
      SearchMessage(R.string.epub_search_no_results)
    search.showingSuggestions -> SearchSuggestions(search.suggestions, onSuggestion)
    search.results.isNotEmpty() ->
      SearchResultList(search, toc, positionCount, onSelect, searchResultsListState)
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
  onSelect: (Locator) -> Unit,
  listState: LazyListState
) {
  Column {
    if (search.limited) {
      Text(
        text = stringResource(R.string.epub_search_limited),
        modifier = Modifier.padding(horizontal = SIXTEEN_DP, vertical = EIGHT_DP),
        style = MaterialTheme.typography.labelMedium
      )
    }
    LazyColumn(
      modifier = Modifier.testTag(EPUB_READER_SEARCH_RESULTS_LIST_TESTING_TAG),
      state = listState
    ) {
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
  pageModesAvailable: Boolean,
  onToc: () -> Unit,
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
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextButton(
          onClick = actions.onShowAnnotations,
          modifier = Modifier.testTag(EPUB_READER_ANNOTATIONS_TESTING_TAG)
        ) {
          Text(stringResource(R.string.epub_annotations))
        }
        PageOverviewButton(pageModesAvailable, actions.onPageModeSelected)
      }
    }
  }
}

@Composable
private fun PageOverviewButton(
  pageModesAvailable: Boolean,
  onPageModeSelected: (EpubPageMode) -> Unit
) {
  if (pageModesAvailable) {
    TextButton(
      onClick = { onPageModeSelected(EpubPageMode.OVERVIEW) },
      modifier = Modifier.testTag(EPUB_READER_PAGE_OVERVIEW_BUTTON_TESTING_TAG)
    ) {
      Text(stringResource(R.string.epub_page_overview))
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

private data class AnnotationDisplayOptions(val positionCount: Int, val darkTheme: Boolean)

private data class AnnotationDialogActions(
  val onDismiss: () -> Unit,
  val onItem: (EpubAnnotation) -> Unit,
  val onDelete: (EpubAnnotation) -> Unit,
  val onUpdateNote: (EpubAnnotation, String) -> Unit,
  val onCopy: (EpubAnnotation) -> Unit
)

private data class AnnotationListActions(
  val onItem: (EpubAnnotation) -> Unit,
  val onAddNote: (EpubAnnotation) -> Unit,
  val onDelete: (EpubAnnotation) -> Unit,
  val onCopy: (EpubAnnotation) -> Unit
)

@Composable
private fun AnnotationsDialog(
  annotations: List<EpubAnnotation>,
  display: AnnotationDisplayOptions,
  actions: AnnotationDialogActions
) {
  val filters = remember { AnnotationFilterState(annotations) }
  SideEffect { filters.updateAnnotations(annotations) }
  var noteTarget by remember { mutableStateOf<EpubAnnotation?>(null) }
  var noteText by remember { mutableStateOf("") }
  Dialog(
    onDismissRequest = actions.onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(horizontal = SIXTEEN_DP),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface
    ) {
      Column(
        Modifier.fillMaxWidth().heightIn(max = ANNOTATION_DIALOG_MAX_HEIGHT).padding(SIXTEEN_DP),
        verticalArrangement = Arrangement.spacedBy(EIGHT_DP)
      ) {
        Text(stringResource(R.string.epub_annotations), style = MaterialTheme.typography.headlineSmall)
        AnnotationFilters(filters, display.darkTheme)
        AnnotationEntries(
          annotations,
          filters.visibleAnnotations,
          display,
          AnnotationListActions(
            onItem = actions.onItem,
            onAddNote = { annotation ->
              noteTarget = annotation
              noteText = annotation.note.orEmpty()
            },
            onDelete = actions.onDelete,
            onCopy = actions.onCopy
          )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.epub_close)) }
        }
      }
    }
  }
  noteTarget?.let { annotation ->
    AnnotationNoteEditor(
      annotation = annotation,
      note = noteText,
      onNoteChange = { noteText = it },
      onDismiss = { noteTarget = null },
      onSave = {
        actions.onUpdateNote(annotation, noteText)
        noteTarget = null
      }
    )
  }
}

@Composable
private fun AnnotationFilters(
  filters: AnnotationFilterState,
  darkTheme: Boolean
) {
  Column(verticalArrangement = Arrangement.spacedBy(EIGHT_DP)) {
    Text(stringResource(R.string.epub_annotation_types), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
      EpubAnnotationKind.entries.forEach { kind ->
        FilterChip(
          selected = kind in filters.kinds,
          onClick = { filters.toggle(kind) },
          label = { Text(stringResource(kind.labelResource())) }
        )
      }
    }
    Text(stringResource(R.string.epub_annotation_colors), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
      EpubHighlightColor.entries.forEach { color ->
        FilterChip(
          selected = color in filters.colors,
          onClick = { filters.toggle(color) },
          label = { Text(stringResource(color.labelResource())) },
          leadingIcon = {
            Surface(
              Modifier.size(16.dp),
              shape = CircleShape,
              color = Color(color.hexFor(darkTheme).toColorInt())
            ) {}
          }
        )
      }
    }
    Text(stringResource(R.string.epub_annotation_chapters), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
      filters.chapters.forEach { chapter ->
        FilterChip(
          selected = chapter in filters.selectedChapters,
          onClick = { filters.toggle(chapter) },
          label = { Text(chapter, maxLines = 1) }
        )
      }
    }
  }
}

@Composable
private fun AnnotationEntries(
  allAnnotations: List<EpubAnnotation>,
  visibleAnnotations: List<EpubAnnotation>,
  display: AnnotationDisplayOptions,
  actions: AnnotationListActions
) {
  if (visibleAnnotations.isEmpty()) {
    val emptyMessage = if (allAnnotations.isEmpty()) {
      R.string.epub_no_annotations
    } else {
      R.string.epub_no_matching_annotations
    }
    Text(stringResource(emptyMessage), modifier = Modifier.padding(vertical = SIXTEEN_DP))
  } else {
    val byChapter = visibleAnnotations
      .groupBy(EpubAnnotation::displayTitle)
      .map { (chapter, chapterAnnotations) ->
        chapter to chapterAnnotations.sortedBy { annotationPage(it, display.positionCount) }
      }
      .sortedBy { (_, chapterAnnotations) ->
        annotationPage(chapterAnnotations.first(), display.positionCount)
      }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = ANNOTATION_LIST_MAX_HEIGHT)) {
      byChapter.forEach { (chapter, chapterAnnotations) ->
        item(key = "chapter:$chapter") {
          Text(
            chapter,
            modifier = Modifier.fillMaxWidth().padding(top = SIXTEEN_DP, bottom = EIGHT_DP),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )
        }
        items(chapterAnnotations, key = { it.id }) { annotation ->
          AnnotationListItem(
            annotation,
            annotationPage(annotation, display.positionCount),
            display.darkTheme,
            actions
          )
        }
      }
    }
  }
}

@Composable
private fun AnnotationNoteEditor(
  annotation: EpubAnnotation,
  note: String,
  onNoteChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onSave: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      val title = if (annotation.note.isNullOrBlank()) R.string.epub_add_note else R.string.epub_edit_note
      Text(stringResource(title))
    },
    text = {
      OutlinedTextField(
        value = note,
        onValueChange = onNoteChange,
        label = { Text(stringResource(R.string.epub_note_hint)) }
      )
    },
    confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.save)) } }
  )
}

@Composable
private fun AnnotationListItem(
  annotation: EpubAnnotation,
  pageNumber: Int,
  darkTheme: Boolean,
  actions: AnnotationListActions
) {
  Column(Modifier.fillMaxWidth().padding(vertical = EIGHT_DP)) {
    Column(Modifier.fillMaxWidth().clickable { actions.onItem(annotation) }) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(annotation.kind.labelResource()),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary
        )
        Text(stringResource(R.string.epub_page_number, pageNumber))
      }
      AnnotationContext(annotation, darkTheme)
      annotation.note?.takeIf(String::isNotBlank)?.let {
        Text(it, modifier = Modifier.padding(top = EIGHT_DP), style = MaterialTheme.typography.bodyMedium)
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = { actions.onAddNote(annotation) }) {
        Text(stringResource(if (annotation.note.isNullOrBlank()) R.string.epub_add_note else R.string.epub_edit_note))
      }
      TextButton(
        onClick = { actions.onDelete(annotation) },
        modifier = Modifier.testTag(EPUB_READER_ANNOTATION_DELETE_TESTING_TAG)
      ) { Text(stringResource(R.string.epub_delete_annotation)) }
      TextButton(
        onClick = { actions.onCopy(annotation) },
        modifier = Modifier.testTag(EPUB_READER_ANNOTATION_COPY_TESTING_TAG)
      ) { Text(stringResource(R.string.epub_copy_annotation)) }
    }
    Surface(Modifier.fillMaxWidth().height(1.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
  }
}

@Composable
private fun AnnotationContext(annotation: EpubAnnotation, darkTheme: Boolean) {
  val exact = annotation.text.ifBlank { annotation.locator.text.highlight.orEmpty() }
  if (exact.isBlank()) return
  val prefix = annotation.locator.text.before.orEmpty().takeLast(ANNOTATION_CONTEXT_CHARS)
  val suffix = annotation.locator.text.after.orEmpty().take(ANNOTATION_CONTEXT_CHARS)
  Text(
    buildAnnotatedString {
      if (prefix.isNotBlank()) append("…$prefix")
      withStyle(
        SpanStyle(
          background = Color(
            (annotation.color ?: EpubHighlightColor.LIGHT_YELLOW).hexFor(darkTheme).toColorInt()
          )
        )
      ) { append(exact) }
      if (suffix.isNotBlank()) append("$suffix…")
    },
    modifier = Modifier.fillMaxWidth().padding(top = EIGHT_DP),
    maxLines = 3,
    overflow = TextOverflow.Ellipsis,
    style = MaterialTheme.typography.bodyMedium
  )
}

private fun EpubAnnotation.displayTitle() =
  title.ifBlank { locator.href.toString().substringAfterLast('/').substringBefore('?') }

private fun annotationPage(annotation: EpubAnnotation, positionCount: Int) =
  readingProgress(annotation.locator, positionCount).position.takeIf { it > 0 }
    ?: annotation.locator.locations.position
    ?: 1

@androidx.annotation.StringRes
private fun EpubAnnotationKind.labelResource() = when (this) {
  EpubAnnotationKind.BOOKMARK -> R.string.epub_bookmark
  EpubAnnotationKind.NOTE -> R.string.epub_note
  EpubAnnotationKind.HIGHLIGHT -> R.string.epub_highlight
}

@androidx.annotation.StringRes
private fun EpubHighlightColor.labelResource() = when (this) {
  EpubHighlightColor.LIGHT_YELLOW -> R.string.epub_highlight_light_yellow
  EpubHighlightColor.LIGHT_GREEN -> R.string.epub_highlight_light_green
  EpubHighlightColor.LIGHT_BLUE -> R.string.epub_highlight_light_blue
  EpubHighlightColor.LIGHT_PINK -> R.string.epub_highlight_light_pink
  EpubHighlightColor.LIGHT_PURPLE -> R.string.epub_highlight_light_purple
}
