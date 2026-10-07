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
 */

package org.kiwix.kiwixmobile.core.epub.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

@Composable
internal fun EpubReaderActivity.collectReadingState(state: EpubReaderUiState): EpubReadingState {
  val locator by viewModel.currentLocator.collectAsState()
  val positions by viewModel.positions.collectAsState()
  val readingOrder = (state as? EpubReaderUiState.Ready)?.book?.publication?.readingOrder.orEmpty()
  return EpubReadingState(
    locator = locator,
    positionCount = positions.size,
    hasPreviousChapter = locator?.let { adjacentChapter(readingOrder, it, next = false) } != null,
    hasNextChapter = locator?.let { adjacentChapter(readingOrder, it, next = true) } != null
  )
}

/** Observes the carousel's own flows so a swipe recomposes only the pager, not the whole screen. */
@Composable
internal fun EpubReaderActivity.CarouselHost() {
  val pages by pagePreviews.collectAsState()
  val locator by viewModel.currentLocator.collectAsState()
  val annotations by viewModel.annotations.collectAsState()
  val bookmarks = remember(annotations) {
    annotations.filter { it.kind == EpubAnnotationKind.BOOKMARK }.map { it.locator }
  }
  if (pages.isNotEmpty()) {
    PageCarousel(
      pages = pages,
      currentLocator = locator,
      bookmarkedLocators = bookmarks,
      onBookmarkToggle = viewModel::toggleBookmark,
      onPositionChanged = ::onCarouselPositionChanged,
      onPageSettled = ::selectCarouselPage,
      onPageTap = ::handleCarouselTap
    )
  }
}
