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
class EpubReaderProgressTest {
  private fun locator(
    href: String,
    total: Double? = null,
    position: Int? = null,
    title: String? = null
  ) = Locator(href = Url(href)!!, mediaType = MediaType.XHTML, title = title)
    .copyWithLocations(totalProgression = total, position = position)

  private fun positions(count: Int) =
    List(count) { locator("c$it.xhtml", total = it / count.toDouble(), position = it + 1) }

  @Test
  fun `progress comes from the total progression`() {
    val progress = readingProgress(locator("c1.xhtml", total = 0.5), 100)
    assertEquals(0.5f, progress.fraction, 0f)
    assertEquals(50, progress.percent)
    assertEquals(51, progress.position)
    assertEquals(100, progress.total)
  }

  @Test
  fun `progress falls back to the position, then to the start`() {
    assertEquals(0.09f, readingProgress(locator("c.xhtml", position = 10), 100).fraction, 0.0001f)
    assertEquals(0f, readingProgress(locator("c.xhtml"), 100).fraction, 0f)
    assertEquals(0f, readingProgress(null, 100).fraction, 0f)
  }

  @Test
  fun `without positions the page is zero and the percentage still works`() {
    val progress = readingProgress(locator("c.xhtml", total = 0.25), 0)
    assertEquals(0, progress.position)
    assertEquals(25, progress.percent)
  }

  @Test
  fun `the end of the book is the last position, never one past`() {
    assertEquals(100, readingProgress(locator("c.xhtml", total = 1.0), 100).position)
    assertEquals(1, readingProgress(locator("c.xhtml", total = 0.0), 100).position)
    assertEquals(100, positionForFraction(2f, 100))
    assertEquals(1, positionForFraction(-1f, 100))
  }

  @Test
  fun `seeking maps the scrubber to the matching Readium position`() {
    val all = positions(10)
    assertEquals(all[0], seekTarget(all, 0f))
    assertEquals(all[5], seekTarget(all, 0.5f))
    assertEquals(all[9], seekTarget(all, 1f))
    assertNull(seekTarget(emptyList(), 0.5f))
  }

  @Test
  fun `seeking to a position and reading it back agree`() {
    val all = positions(37)
    (0..36).forEach { index ->
      val target = seekTarget(all, index / 37f)!!
      assertEquals(index + 1, readingProgress(target, 37).position)
    }
  }

  @Test
  fun `chapter title prefers the whole-file entry, then any entry, then the locator title`() {
    val toc = listOf(
      EpubTocItem("Intro", Link(href = Url("c1.xhtml#a")!!), 0),
      EpubTocItem("One", Link(href = Url("c1.xhtml")!!), 0),
      EpubTocItem("Two", Link(href = Url("c2.xhtml#s")!!), 0)
    )
    assertEquals("One", chapterTitle(toc, locator("c1.xhtml")))
    assertEquals("Two", chapterTitle(toc, locator("c2.xhtml")))
    assertEquals("Own", chapterTitle(toc, locator("c3.xhtml", title = "Own")))
    assertNull(chapterTitle(toc, locator("c3.xhtml")))
    assertNull(chapterTitle(toc, null))
  }
}
