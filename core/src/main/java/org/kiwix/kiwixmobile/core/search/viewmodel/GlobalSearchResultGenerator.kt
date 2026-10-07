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

package org.kiwix.kiwixmobile.core.search.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookOnDisk
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.reader.ZimFileReader
import org.kiwix.kiwixmobile.core.search.SearchListItem.ZimSearchResultListItem
import org.kiwix.kiwixmobile.core.utils.files.Log
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem.BookOnDisk
import javax.inject.Inject

private const val TAG = "GlobalSearchGenerator"
const val GLOBAL_SEARCH_MAX_RESULTS_PER_BOOK = 10
const val GLOBAL_SEARCH_MAX_TOTAL_RESULTS = 60

/** Searches every book on the device, tagging each result with the book it came from. */
interface GlobalSearchResultGenerator {
  suspend fun generateSearchResults(
    searchTerm: String,
    searchMode: SearchMode,
    cancelToken: SearchCancelToken
  ): List<ZimSearchResultListItem>
}

class GlobalSearchResultGeneratorImpl @Inject constructor(
  private val libkiwixBookOnDisk: LibkiwixBookOnDisk,
  private val zimFileReaderFactory: ZimFileReader.Factory,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : GlobalSearchResultGenerator {
  override suspend fun generateSearchResults(
    searchTerm: String,
    searchMode: SearchMode,
    cancelToken: SearchCancelToken
  ): List<ZimSearchResultListItem> {
    if (searchTerm.isBlank() || cancelToken.isCancelled) return emptyList()
    return withContext(ioDispatcher) {
      val books = libkiwixBookOnDisk.getBooks().sortedBy { it.book.title.lowercase() }
      val results = mutableListOf<ZimSearchResultListItem>()
      for (bookOnDisk in books) {
        if (cancelToken.isCancelled || results.size >= GLOBAL_SEARCH_MAX_TOTAL_RESULTS) break
        results += searchInBook(bookOnDisk, searchTerm, searchMode, cancelToken)
      }
      results.take(GLOBAL_SEARCH_MAX_TOTAL_RESULTS)
    }
  }

  // Each book gets a throw-away reader, so no native archive outlives the search.
  @Suppress("TooGenericExceptionCaught")
  private suspend fun searchInBook(
    bookOnDisk: BookOnDisk,
    searchTerm: String,
    searchMode: SearchMode,
    cancelToken: SearchCancelToken
  ): List<ZimSearchResultListItem> {
    val reader = zimFileReaderFactory.create(bookOnDisk.zimReaderSource, false) ?: return emptyList()
    return try {
      collectResults(reader, bookOnDisk, searchTerm, searchMode, cancelToken)
    } catch (exception: CancellationException) {
      throw exception
    } catch (exception: Exception) {
      // The match path's cancel can arrive as a plain Exception; never log that away.
      if (cancelToken.isCancelled) throw CancellationException("Search cancelled")
      Log.e(TAG, "Could not search in ${bookOnDisk.book.title}. $exception")
      emptyList()
    } finally {
      runCatching { reader.dispose() }
      // URI-backed books open their own AFDs per reader; the archive must die first.
      runCatching { bookOnDisk.zimReaderSource.releaseDescriptors() }
    }
  }

  // Null when the book has no full-text index — caller falls back to title search.
  @Suppress("NestedBlockDepth")
  private suspend fun collectPageContent(
    reader: ZimFileReader,
    searchTerm: String,
    cancelToken: SearchCancelToken,
    bookTitle: String,
    sourceDb: String
  ): List<ZimSearchResultListItem>? {
    val search = reader.searchFullText(searchTerm) ?: return null
    val results = mutableListOf<ZimSearchResultListItem>()
    try {
      cancelToken.attach(search::cancel)
      val iterator = search.getResults(0, GLOBAL_SEARCH_MAX_RESULTS_PER_BOOK)
      try {
        while (iterator.hasNext()) {
          if (cancelToken.isCancelled) break
          yield()
          // Snippet before next(): afterwards it describes the next hit.
          val snippet = iterator.snippetOrNull()
          val entry = iterator.next()
          results.add(
            ZimSearchResultListItem(
              value = entry.title,
              url = entry.path,
              snippet = snippet,
              bookTitle = bookTitle,
              zimReaderSourceDatabaseValue = sourceDb
            )
          )
        }
      } finally {
        runCatching { iterator.dispose() }
      }
    } finally {
      cancelToken.detach()
      search.dispose()
    }
    return results
  }

  @Suppress("NestedBlockDepth", "ReturnCount")
  private suspend fun collectResults(
    reader: ZimFileReader,
    bookOnDisk: BookOnDisk,
    searchTerm: String,
    searchMode: SearchMode,
    cancelToken: SearchCancelToken
  ): List<ZimSearchResultListItem> {
    val bookTitle = bookOnDisk.book.title
    val sourceDb = bookOnDisk.zimReaderSource.toDatabase()
    val results = mutableListOf<ZimSearchResultListItem>()

    if (searchMode == SearchMode.PAGE_CONTENT) {
      collectPageContent(reader, searchTerm, cancelToken, bookTitle, sourceDb)?.let { return it }
    }

    // Title search, or the full-text fallback for books without an index.
    val suggestionSearch = reader.searchSuggestions(searchTerm) ?: return results
    try {
      val iterator = suggestionSearch.getResults(0, GLOBAL_SEARCH_MAX_RESULTS_PER_BOOK)
      try {
        while (iterator.hasNext()) {
          if (cancelToken.isCancelled) break
          yield()
          val entry = iterator.next()
          results.add(
            ZimSearchResultListItem(
              value = entry.title,
              url = entry.path,
              bookTitle = bookTitle,
              zimReaderSourceDatabaseValue = sourceDb
            )
          )
        }
      } finally {
        runCatching { iterator.dispose() }
      }
    } finally {
      suggestionSearch.dispose()
    }
    return results
  }
}
