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

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubReaderNavigationTest {
  private fun link(path: String, title: String? = null, children: List<Link> = emptyList()) =
    Link(href = Url(path)!!, title = title, children = children)

  private fun locator(path: String) = Locator(href = Url(path)!!, mediaType = MediaType.XHTML)

  private val readingOrder = listOf(link("c1.xhtml"), link("c2.xhtml"), link("c3.xhtml"))

  @Test
  fun `toc is flattened depth first with depths`() {
    val toc = listOf(
      link("c1.xhtml", "One", listOf(link("c1.xhtml#a", "One A"), link("c1.xhtml#b", "One B"))),
      link("c2.xhtml", "Two")
    )
    val items = flattenToc(toc)
    assertEquals(listOf("One", "One A", "One B", "Two"), items.map { it.title })
    assertEquals(listOf(0, 1, 1, 0), items.map { it.depth })
  }

  @Test
  fun `untitled toc entries are named after their file`() {
    val items = flattenToc(listOf(link("text/c9.xhtml#frag"), link("c2.xhtml", "  ")))
    assertEquals(listOf("c9.xhtml", "c2.xhtml"), items.map { it.title })
  }

  @Test
  fun `adjacent chapter finds the neighbours and stops at both ends`() {
    assertEquals("c3.xhtml", adjacentChapter(readingOrder, locator("c2.xhtml"), next = true)?.url()?.path)
    assertEquals("c1.xhtml", adjacentChapter(readingOrder, locator("c2.xhtml"), next = false)?.url()?.path)
    assertNull(adjacentChapter(readingOrder, locator("c1.xhtml"), next = false))
    assertNull(adjacentChapter(readingOrder, locator("c3.xhtml"), next = true))
  }

  @Test
  fun `adjacent chapter ignores fragments and unknown documents`() {
    val inside = Locator(href = Url("c2.xhtml#sec")!!, mediaType = MediaType.XHTML)
    assertNotNull(adjacentChapter(readingOrder, inside, next = true))
    assertNull(adjacentChapter(readingOrder, locator("elsewhere.xhtml"), next = true))
  }

  @Test
  fun `locator round-trips through its stored json`() {
    val original = locator("c2.xhtml").copyWithLocations(progression = 0.42, position = 7)
    val restored = EpubLocatorCodec.decode(EpubLocatorCodec.encode(original))
    assertEquals(original, restored)
    assertEquals(0.42, restored?.locations?.progression)
  }

  @Test
  fun `missing or corrupt locator json decodes to null`() {
    assertNull(EpubLocatorCodec.decode(null))
    assertNull(EpubLocatorCodec.decode(""))
    assertNull(EpubLocatorCodec.decode("{not json"))
    assertNull(EpubLocatorCodec.decode("{}"))
  }
}
