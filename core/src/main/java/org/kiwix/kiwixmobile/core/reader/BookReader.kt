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
package org.kiwix.kiwixmobile.core.reader

import java.io.InputStream

/** Format-agnostic content reader (ZIM, EPUB, ...) served to the WebView. */
interface BookReader {
  /** Stable identity of the opened source (ZIM: path or uri). */
  val sourceId: String
  val id: String
  val title: String
  val mainPage: String?
  val creator: String
  val publisher: String
  val name: String
  val date: String
  val description: String
  val favicon: String?
  val language: String

  suspend fun load(uri: String): InputStream?
  fun getMimeTypeFromUrl(uri: String): String?
  fun getRedirect(url: String): String
  fun isRedirect(url: String): Boolean
  fun dispose()
}
