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

package org.kiwix.kiwixmobile.core.epub

import org.kiwix.kiwixmobile.core.main.reader.DocumentSection

/** Whether chapter (spine) navigation is possible from the current page. */
data class EpubNavState(val hasPrevious: Boolean = false, val hasNext: Boolean = false)

/** Nav state for the document at [url] (any kiwix.app URL, fragment allowed); both false if unknown. */
fun EpubFileReader.navStateFor(url: String?): EpubNavState =
  if (url == null) {
    EpubNavState()
  } else {
    EpubNavState(hasPrevious = previousUrl(url) != null, hasNext = nextUrl(url) != null)
  }

/** Maps the EPUB table of contents onto the ZIM ToC drawer's [DocumentSection] rows. */
object EpubTocMapper {
  /** Depth-first, level = depth + 1. Entries without a resolvable target are dropped, their children kept. */
  fun toSections(toc: List<TocEntry>, depth: Int = 1): List<DocumentSection> =
    toc.flatMap { entry ->
      val self = entry.url?.let {
        listOf(DocumentSection(title = entry.title, id = "", level = depth, url = it))
      }.orEmpty()
      self + toSections(entry.children, depth + 1)
    }
}

/** Last read position of one EPUB: the page URL (may carry a fragment) and its scroll offset. */
data class EpubReadingPosition(val url: String, val scrollY: Int) {
  fun encode(): String = "$scrollY$SEPARATOR$url"

  companion object {
    private const val SEPARATOR = '|'

    fun decode(value: String?): EpubReadingPosition? {
      val scroll = value?.substringBefore(SEPARATOR, "")?.toIntOrNull()
      val url = value?.substringAfter(SEPARATOR, "")?.takeIf { it.isNotEmpty() }
      return if (scroll == null || url == null) null else EpubReadingPosition(url, scroll.coerceAtLeast(0))
    }
  }
}
