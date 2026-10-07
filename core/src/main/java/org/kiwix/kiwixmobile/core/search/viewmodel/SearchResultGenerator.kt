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

import org.kiwix.kiwixmobile.core.reader.ZimReaderContainer
import javax.inject.Inject

interface SearchResultGenerator {
  suspend fun generateSearchResults(
    searchTerm: String,
    searchMode: SearchMode,
    zimReaderContainer: ZimReaderContainer
  ): ZimSearchResultSet?
}

class ZimSearchResultGenerator @Inject constructor() : SearchResultGenerator {
  override suspend fun generateSearchResults(
    searchTerm: String,
    searchMode: SearchMode,
    zimReaderContainer: ZimReaderContainer
  ): ZimSearchResultSet? = if (searchTerm.isBlank()) {
    null
  } else {
    // withReader hops onto ioDispatcher itself and leases the reader for the
    // duration of this call, so it can't be disposed mid-search.
    zimReaderContainer.withReader {
      when (searchMode) {
        SearchMode.TITLE -> it.searchSuggestions(searchTerm)?.let(ZimSearchResultSet::Title)
        SearchMode.PAGE_CONTENT ->
          it.searchFullText(searchTerm)?.let(ZimSearchResultSet::PageContent)
            // Books without a full-text index fall back to the title search.
            ?: it.searchSuggestions(searchTerm)?.let(ZimSearchResultSet::Title)
      }
    }
  }
}
