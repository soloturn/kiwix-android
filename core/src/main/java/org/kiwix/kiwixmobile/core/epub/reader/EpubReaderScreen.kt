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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
const val EPUB_READER_FONT_SMALLER_TESTING_TAG = "epubReaderFontSmallerTestingTag"
const val EPUB_READER_FONT_LARGER_TESTING_TAG = "epubReaderFontLargerTestingTag"
const val EPUB_READER_MARGINS_NARROWER_TESTING_TAG = "epubReaderMarginsNarrowerTestingTag"
const val EPUB_READER_MARGINS_WIDER_TESTING_TAG = "epubReaderMarginsWiderTestingTag"

private const val PERCENT = 100

/** What the reader chrome can ask of its host. */
data class EpubReaderActions(
  val onBack: () -> Unit = {},
  val onTocItem: (EpubTocItem) -> Unit = {},
  val onPreviousChapter: () -> Unit = {},
  val onNextChapter: () -> Unit = {},
  val onSettings: ((EpubReaderSettings) -> EpubReaderSettings) -> Unit = {}
)

/**
 * The reader's own UI around the navigator: top bar, loading and error states, the table of
 * contents and the reading settings. Drawn over the navigator view, so it draws nothing where
 * the book should show through.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EpubReaderScreen(
  state: EpubReaderUiState,
  settings: EpubReaderSettings,
  currentLocator: Locator?,
  chromeVisible: Boolean,
  hasPreviousChapter: Boolean,
  hasNextChapter: Boolean,
  actions: EpubReaderActions
) {
  var showToc by rememberSaveable { mutableStateOf(false) }
  var showSettings by rememberSaveable { mutableStateOf(false) }
  val ready = state as? EpubReaderUiState.Ready
  KiwixTheme {
    Box(Modifier.fillMaxSize()) {
      when (state) {
        EpubReaderUiState.Loading -> LoadingContent()
        EpubReaderUiState.Failed -> ErrorContent(actions.onBack)
        is EpubReaderUiState.Ready -> Unit
      }
      AnimatedVisibility(
        visible = ready == null || chromeVisible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        // KiwixAppBar leaves the status bar to its host, so pad for it here.
        Box(
          Modifier
            .background(MaterialTheme.colorScheme.onPrimary)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
        ) {
          KiwixAppBar(
            title = ready?.book?.title.orEmpty(),
            navigationIcon = { NavigationIcon(onClick = actions.onBack) },
            actionMenuItems = if (ready == null) {
              emptyList()
            } else {
              actionItems(
                hasPreviousChapter,
                hasNextChapter,
                actions,
                onToc = { showToc = true },
                onSettings = { showSettings = true }
              )
            }
          )
        }
      }
    }
    if (showToc && ready != null) {
      TocDialog(ready.book.toc, currentLocator, onDismiss = { showToc = false }) {
        showToc = false
        actions.onTocItem(it)
      }
    }
    if (showSettings) {
      SettingsDialog(settings, actions.onSettings) { showSettings = false }
    }
  }
}

private fun actionItems(
  hasPreviousChapter: Boolean,
  hasNextChapter: Boolean,
  actions: EpubReaderActions,
  onToc: () -> Unit,
  onSettings: () -> Unit
) = listOf(
  ActionMenuItem(
    icon = IconItem.Drawable(R.drawable.ic_skip_previous_24dp),
    contentDescription = R.string.go_to_previous_chapter,
    onClick = actions.onPreviousChapter,
    isEnabled = hasPreviousChapter,
    testingTag = EPUB_READER_PREVIOUS_CHAPTER_TESTING_TAG
  ),
  ActionMenuItem(
    icon = IconItem.Drawable(R.drawable.ic_skip_next_24dp),
    contentDescription = R.string.go_to_next_chapter,
    onClick = actions.onNextChapter,
    isEnabled = hasNextChapter,
    testingTag = EPUB_READER_NEXT_CHAPTER_TESTING_TAG
  ),
  ActionMenuItem(
    icon = IconItem.Drawable(R.drawable.ic_toc_24dp),
    contentDescription = R.string.table_of_contents,
    onClick = onToc,
    testingTag = EPUB_READER_TOC_TESTING_TAG
  ),
  ActionMenuItem(
    icon = IconItem.Drawable(R.drawable.ic_settings_24px),
    contentDescription = R.string.epub_reading_settings,
    onClick = onSettings,
    testingTag = EPUB_READER_SETTINGS_TESTING_TAG
  )
)

@Composable
private fun LoadingContent() {
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(contentAlignment = Alignment.Center) {
      CircularProgressIndicator(Modifier.testTag(EPUB_READER_LOADING_TESTING_TAG))
    }
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

@Composable
private fun SettingsDialog(
  settings: EpubReaderSettings,
  onSettings: ((EpubReaderSettings) -> EpubReaderSettings) -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.epub_reading_settings)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(SIXTEEN_DP)) {
        StepperRow(
          label = stringResource(R.string.epub_text_size),
          value = "${(settings.fontScale * PERCENT).roundToInt()}%",
          decreaseDescription = stringResource(R.string.epub_decrease_text_size),
          increaseDescription = stringResource(R.string.epub_increase_text_size),
          canDecrease = settings.canDecreaseFont,
          canIncrease = settings.canIncreaseFont,
          decreaseTag = EPUB_READER_FONT_SMALLER_TESTING_TAG,
          increaseTag = EPUB_READER_FONT_LARGER_TESTING_TAG,
          onDecrease = { onSettings { it.smallerFont() } },
          onIncrease = { onSettings { it.largerFont() } }
        )
        StepperRow(
          label = stringResource(R.string.epub_page_margins),
          value = "${(settings.pageMargins * PERCENT).roundToInt()}%",
          decreaseDescription = stringResource(R.string.epub_decrease_margins),
          increaseDescription = stringResource(R.string.epub_increase_margins),
          canDecrease = settings.canNarrowMargins,
          canIncrease = settings.canWidenMargins,
          decreaseTag = EPUB_READER_MARGINS_NARROWER_TESTING_TAG,
          increaseTag = EPUB_READER_MARGINS_WIDER_TESTING_TAG,
          onDecrease = { onSettings { it.narrowerMargins() } },
          onIncrease = { onSettings { it.widerMargins() } }
        )
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.epub_close)) } }
  )
}

@Composable
@Suppress("LongParameterList")
private fun StepperRow(
  label: String,
  value: String,
  decreaseDescription: String,
  increaseDescription: String,
  canDecrease: Boolean,
  canIncrease: Boolean,
  decreaseTag: String,
  increaseTag: String,
  onDecrease: () -> Unit,
  onIncrease: () -> Unit
) {
  Column {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(SIXTEEN_DP),
      verticalAlignment = Alignment.CenterVertically
    ) {
      OutlinedButton(
        onClick = onDecrease,
        enabled = canDecrease,
        modifier = Modifier
          .testTag(decreaseTag)
          .semantics { contentDescription = decreaseDescription }
      ) { Text("−") }
      Text(value, style = MaterialTheme.typography.bodyLarge)
      OutlinedButton(
        onClick = onIncrease,
        enabled = canIncrease,
        modifier = Modifier
          .testTag(increaseTag)
          .semantics { contentDescription = increaseDescription }
      ) { Text("+") }
    }
  }
}
