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

package org.kiwix.kiwixmobile.core.page.saved

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.main.LEFT_DRAWER_BOOKMARK_ITEM_TESTING_TAG
import org.kiwix.kiwixmobile.core.main.LEFT_DRAWER_HISTORY_ITEM_TESTING_TAG
import org.kiwix.kiwixmobile.core.main.LEFT_DRAWER_NOTES_ITEM_TESTING_TAG
import org.kiwix.kiwixmobile.core.page.bookmark.BookmarkScreenRoute
import org.kiwix.kiwixmobile.core.page.bookmark.viewmodel.BookmarkViewModel
import org.kiwix.kiwixmobile.core.page.history.HistoryScreenRoute
import org.kiwix.kiwixmobile.core.page.history.viewmodel.HistoryViewModel
import org.kiwix.kiwixmobile.core.page.notes.NotesScreenRoute
import org.kiwix.kiwixmobile.core.page.notes.viewmodel.NotesViewModel
import org.kiwix.kiwixmobile.core.ui.theme.KiwixTheme
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.SIXTEEN_DP

const val SAVED_SEGMENTED_ROW_TESTING_TAG = "savedSegmentedRowTestingTag"
private const val SAVED_PAGE_COUNT = 3
private const val BOOKMARKS_PAGE = 0
private const val HISTORY_PAGE = 1
private const val NOTES_PAGE = 2

/**
 * Replaces the drawer's three separate Bookmarks/History/Notes rows with one screen:
 * a segmented tab row over a swipeable pager, each page hosting the existing,
 * unmodified [BookmarkScreenRoute] / [HistoryScreenRoute] / [NotesScreenRoute].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreenRoute(navigateBack: () -> Unit) {
  val pagerState = rememberPagerState(pageCount = { SAVED_PAGE_COUNT })
  val coroutineScope = rememberCoroutineScope()
  KiwixTheme {
    SingleChoiceSegmentedButtonRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(SIXTEEN_DP)
        .testTag(SAVED_SEGMENTED_ROW_TESTING_TAG)
    ) {
      SavedSegment(
        index = BOOKMARKS_PAGE,
        title = stringResource(R.string.bookmarks),
        testingTag = LEFT_DRAWER_BOOKMARK_ITEM_TESTING_TAG,
        selectedIndex = pagerState.currentPage
      ) { coroutineScope.launch { pagerState.animateScrollToPage(BOOKMARKS_PAGE) } }
      SavedSegment(
        index = HISTORY_PAGE,
        title = stringResource(R.string.history),
        testingTag = LEFT_DRAWER_HISTORY_ITEM_TESTING_TAG,
        selectedIndex = pagerState.currentPage
      ) { coroutineScope.launch { pagerState.animateScrollToPage(HISTORY_PAGE) } }
      SavedSegment(
        index = NOTES_PAGE,
        title = stringResource(R.string.pref_notes),
        testingTag = LEFT_DRAWER_NOTES_ITEM_TESTING_TAG,
        selectedIndex = pagerState.currentPage
      ) { coroutineScope.launch { pagerState.animateScrollToPage(NOTES_PAGE) } }
    }
    HorizontalPager(state = pagerState) { page ->
      when (page) {
        BOOKMARKS_PAGE -> {
          val viewModel: BookmarkViewModel = hiltViewModel()
          BookmarkScreenRoute(navigateBack = navigateBack, viewModel = viewModel)
        }

        HISTORY_PAGE -> {
          val viewModel: HistoryViewModel = hiltViewModel()
          HistoryScreenRoute(navigateBack = navigateBack, viewModel = viewModel)
        }

        else -> {
          val viewModel: NotesViewModel = hiltViewModel()
          NotesScreenRoute(navigateBack = navigateBack, notesViewModel = viewModel)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleChoiceSegmentedButtonRowScope.SavedSegment(
  index: Int,
  title: String,
  testingTag: String,
  selectedIndex: Int,
  onClick: () -> Unit
) {
  SegmentedButton(
    selected = selectedIndex == index,
    onClick = onClick,
    shape = SegmentedButtonDefaults.itemShape(index = index, count = SAVED_PAGE_COUNT),
    modifier = Modifier.testTag(testingTag)
  ) {
    Text(title)
  }
}
