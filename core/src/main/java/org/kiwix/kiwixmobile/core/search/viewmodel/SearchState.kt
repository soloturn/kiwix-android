/*
 * Kiwix Android
 * Copyright (c) 2020 Kiwix <android.kiwix.org>
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

package org.kiwix.kiwixmobile.core.search.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.kiwix.kiwixmobile.core.search.SearchListItem
import org.kiwix.kiwixmobile.core.utils.files.Log

data class SearchState(
  val searchTerm: String,
  val searchResultsWithTerm: SearchResultsWithTerm,
  val recentResults: List<SearchListItem.RecentSearchListItem>,
  val searchOrigin: SearchOrigin
) {
  @Suppress("ReturnCount")
  suspend fun getVisibleResults(
    startIndex: Int,
    ioDispatcher: CoroutineDispatcher
  ): List<SearchListItem>? {
    if (searchTerm.isEmpty()) return recentResults
    // Cross-book results are computed up front and are not paginated.
    searchResultsWithTerm.globalResults?.let { return if (startIndex == 0) it else null }
    return searchResultsWithTerm.searchMutex?.withLock {
      searchResultsWithTerm.zimSearchResultSet?.let {
        yield()
        withContext(ioDispatcher) {
          fetchSearchResults(it, startIndex)
        }
      } ?: kotlin.run {
        recentResults
      }
    }
  }

  @Suppress("MagicNumber", "NestedBlockDepth", "TooGenericExceptionCaught")
  private suspend fun fetchSearchResults(
    zimSearchResultSet: ZimSearchResultSet,
    startIndex: Int
  ): List<SearchListItem.ZimSearchResultListItem>? {
    val results = mutableListOf<SearchListItem.ZimSearchResultListItem>()
    val cancelToken = searchResultsWithTerm.cancelToken

    if (cancelToken.isCancelled) return results

    try {
      val safeEndIndex = startIndex + 20
      yield()
      when (zimSearchResultSet) {
        is ZimSearchResultSet.Title -> {
          val searchIterator =
            zimSearchResultSet.suggestionSearch.getResults(startIndex, safeEndIndex)
          try {
            while (searchIterator.hasNext()) {
              if (cancelToken.isCancelled) break
              yield()
              val entry = searchIterator.next()
              results.add(SearchListItem.ZimSearchResultListItem(entry.title, entry.path))
            }
          } finally {
            runCatching { searchIterator.dispose() }
          }
        }

        is ZimSearchResultSet.PageContent -> {
          val searchIterator = zimSearchResultSet.search.getResults(startIndex, safeEndIndex)
          try {
            while (searchIterator.hasNext()) {
              if (cancelToken.isCancelled) break
              yield()
              // Snippet is read before next(): afterwards it describes the next hit.
              val snippet = searchIterator.snippetOrNull()
              val entry = searchIterator.next()
              results.add(SearchListItem.ZimSearchResultListItem(entry.title, entry.path, snippet))
            }
          } finally {
            runCatching { searchIterator.dispose() }
          }
        }
      }
    } catch (exception: CancellationException) {
      // The match path's cancel signal must never be logged away as a failure.
      throw exception
    } catch (exception: Exception) {
      // The match path's cancel can arrive as a plain Exception; never log that away.
      if (cancelToken.isCancelled) throw CancellationException("Search cancelled")
      Log.e(
        "SearchState",
        "Could not get the searched result for searchTerm $searchTerm\n" +
          "Original exception = $exception"
      )
    }

    /**
     * Returns null if there are no suggestions left in the iterator.
     * We check this in SearchScreen to avoid unnecessary data loading
     * while scrolling to the end of the list when there are no items available.
     */
    return results.ifEmpty { null }
  }

  val isLoading = searchTerm != searchResultsWithTerm.searchTerm
}

enum class SearchOrigin {
  FromWebView,
  FromTabView
}
