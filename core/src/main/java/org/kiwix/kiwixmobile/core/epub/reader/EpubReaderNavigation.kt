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

import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/** One row of the table-of-contents list: [depth] is 0 for top-level entries. */
data class EpubTocItem(val title: String, val link: Link, val depth: Int)

/** Depth-first flattening of [links]; untitled entries are named after their file. */
fun flattenToc(links: List<Link>, depth: Int = 0): List<EpubTocItem> =
  links.flatMap { link ->
    val title = link.title?.trim().orEmpty()
      .ifEmpty { link.url().removeFragment().filename.orEmpty() }
    listOf(EpubTocItem(title, link, depth)) + flattenToc(link.children, depth + 1)
  }

/** The reading-order chapter before/after the one [current] is in, or null at either end. */
fun adjacentChapter(readingOrder: List<Link>, current: Locator, next: Boolean): Link? {
  val currentHref = current.href.removeFragment()
  val index = readingOrder.indexOfFirst { it.url().removeFragment() == currentHref }
  if (index < 0) return null
  return readingOrder.getOrNull(if (next) index + 1 else index - 1)
}

/** Round-trips a Readium [Locator] through its JSON form for storage. */
object EpubLocatorCodec {
  fun encode(locator: Locator): String = locator.toJSON().toString()

  /** Null for missing or corrupt input. */
  fun decode(value: String?): Locator? {
    if (value.isNullOrBlank()) return null
    return runCatching { Locator.fromJSON(JSONObject(value)) }.getOrNull()
  }
}
