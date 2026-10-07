/*
 * Kiwix Android
 * Copyright (c) 2025 Kiwix <android.kiwix.org>
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

package org.kiwix.kiwixmobile.core.search

sealed class SearchListItem {
  abstract val value: String
  abstract val url: String?

  data class RecentSearchListItem(override val value: String, override val url: String?) :
    SearchListItem()

  data class ZimSearchResultListItem constructor(
    override val value: String,
    override val url: String?,
    /**
     * Quote of the sentence the term was found in, with matches in `<b>` tags
     * as the Xapian index returns them. Full-text results only.
     */
    val snippet: String? = null,
    /** Book this result came from; set for cross-book search only. */
    val bookTitle: String? = null,
    /**
     * `ZimReaderSource.toDatabase()` of the book this result came from, so the
     * reader can switch to it before opening. Cross-book results only.
     */
    val zimReaderSourceDatabaseValue: String? = null
  ) : SearchListItem()
}
