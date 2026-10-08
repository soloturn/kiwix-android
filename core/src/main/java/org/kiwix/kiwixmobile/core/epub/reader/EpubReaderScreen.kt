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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.ui.components.KiwixAppBar
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
  val onSettings: ((EpubReaderSettings) -> EpubReaderSettings) -> Unit = {}
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
  panels: EpubReaderPanels = remember { EpubReaderPanels() }
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
        AnimatedVisibility(
          visible = ready == null || chromeVisible || !pageReady,
          modifier = Modifier.align(Alignment.TopCenter),
          enter = fadeIn(),
          exit = fadeOut()
        ) {
          TopBar(ready?.book?.title.orEmpty(), showSettings = ready != null, actions.onBack) {
            panels.showSettings = true
          }
        }
        AnimatedVisibility(
          visible = ready != null && chromeVisible,
          modifier = Modifier.align(Alignment.BottomCenter),
          enter = fadeIn(),
          exit = fadeOut()
        ) {
          if (ready != null) {
            BottomBar(ready.book, reading, actions, onToc = { panels.showToc = true })
          }
        }
      }
    }
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
private fun TopBar(title: String, showSettings: Boolean, onBack: () -> Unit, onSettings: () -> Unit) {
  Box(
    Modifier
      .background(MaterialTheme.colorScheme.onPrimary)
      .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
  ) {
    KiwixAppBar(
      title = title,
      navigationIcon = { NavigationIcon(onClick = onBack) },
      actionMenuItems = if (showSettings) {
        listOf(
          ActionMenuItem(
            icon = IconItem.Drawable(R.drawable.ic_text_size_24dp),
            contentDescription = R.string.epub_reading_settings,
            onClick = onSettings,
            testingTag = EPUB_READER_SETTINGS_TESTING_TAG
          )
        )
      } else {
        emptyList()
      }
    )
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
      SeekRow(shown, preview, reading, actions)
      PositionLabels(shown, position, reading.positionCount)
    }
  }
}

@Composable
private fun SeekRow(
  shown: Float,
  preview: SeekPreview,
  reading: EpubReadingState,
  actions: EpubReaderActions
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(
      onClick = actions.onPreviousChapter,
      enabled = reading.hasPreviousChapter,
      modifier = Modifier.testTag(EPUB_READER_PREVIOUS_CHAPTER_TESTING_TAG)
    ) {
      Icon(
        painterResource(R.drawable.ic_skip_previous_24dp),
        stringResource(R.string.go_to_previous_chapter)
      )
    }
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
    IconButton(
      onClick = actions.onNextChapter,
      enabled = reading.hasNextChapter,
      modifier = Modifier.testTag(EPUB_READER_NEXT_CHAPTER_TESTING_TAG)
    ) {
      Icon(
        painterResource(R.drawable.ic_skip_next_24dp),
        stringResource(R.string.go_to_next_chapter)
      )
    }
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
