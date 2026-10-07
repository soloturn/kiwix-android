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
import org.kiwix.libzim.Search
import org.kiwix.libzim.SearchIterator
import org.kiwix.libzim.SuggestionSearch

/** A search result handle, for either the article titles or the full page content. */
sealed class ZimSearchResultSet {
  /** Call when superseded; each keystroke builds a new native handle. */
  abstract fun dispose()

  data class Title(val suggestionSearch: SuggestionSearch) : ZimSearchResultSet() {
    override fun dispose() = suggestionSearch.dispose()
  }

  data class PageContent(val search: Search) : ZimSearchResultSet() {
    override fun dispose() = search.dispose()
  }
}

/**
 * Snippet at the iterator's current position — call before `next()` advances it.
 * A cancellation thrown from the match path is rethrown, never converted to null.
 */
@Suppress("TooGenericExceptionCaught")
internal fun SearchIterator.snippetOrNull(): String? =
  try {
    snippet
  } catch (exception: CancellationException) {
    throw exception
  } catch (exception: Exception) {
    null
  }
