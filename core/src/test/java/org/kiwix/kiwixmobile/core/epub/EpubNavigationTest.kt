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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubNavigationTest {
  @TempDir lateinit var dir: File

  @Test
  fun `toc maps to nested sections with levels and urls`() {
    val toc = listOf(
      TocEntry(
        "One",
        "OEBPS/ch1.xhtml",
        null,
        listOf(TocEntry("Sec", "OEBPS/ch1.xhtml", "s1", emptyList()))
      ),
      TocEntry("Two", "OEBPS/ch 2.xhtml", null, emptyList())
    )
    val sections = EpubTocMapper.toSections(toc)
    assertEquals(listOf("One", "Sec", "Two"), sections.map { it.title })
    assertEquals(listOf(1, 2, 1), sections.map { it.level })
    assertEquals("https://kiwix.app/OEBPS/ch1.xhtml#s1", sections[1].url)
    assertEquals("https://kiwix.app/OEBPS/ch%202.xhtml", sections[2].url)
  }

  @Test
  fun `unresolvable toc entries are dropped but their children kept`() {
    val toc = listOf(
      TocEntry(
        "Part",
        null,
        null,
        listOf(TocEntry("Child", "a.xhtml", null, emptyList()))
      )
    )
    val sections = EpubTocMapper.toSections(toc)
    assertEquals(listOf("Child"), sections.map { it.title })
    assertEquals(2, sections.single().level)
  }

  @Test
  fun `empty toc maps to empty list`() {
    assertEquals(emptyList<Any>(), EpubTocMapper.toSections(emptyList()))
  }

  private fun reader(): EpubFileReader {
    val container = """<?xml version="1.0"?>
      <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
      <rootfiles><rootfile full-path="c.opf" media-type="application/oebps-package+xml"/></rootfiles>
      </container>"""
    val opf = """<?xml version="1.0"?>
      <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:identifier id="bid">id1</dc:identifier><dc:title>T</dc:title><dc:language>en</dc:language>
      </metadata>
      <manifest>
        <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
        <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
        <item id="c" href="c.xhtml" media-type="application/xhtml+xml"/>
      </manifest>
      <spine><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/></spine>
      </package>"""
    val f = File(dir, "n.epub")
    ZipOutputStream(f.outputStream()).use { z ->
      listOf(
        "META-INF/container.xml" to container,
        "c.opf" to opf,
        "a.xhtml" to "a",
        "b.xhtml" to "b",
        "c.xhtml" to "c"
      ).forEach { (n, c) ->
        z.putNextEntry(ZipEntry(n))
        z.write(c.toByteArray())
        z.closeEntry()
      }
    }
    return EpubFileReader(f)
  }

  @Test
  fun `nav state is disabled at the ends and enabled in the middle`() {
    val r = reader()
    assertEquals(EpubNavState(hasPrevious = false, hasNext = true), r.navStateFor("https://kiwix.app/a.xhtml"))
    assertEquals(EpubNavState(hasPrevious = true, hasNext = true), r.navStateFor("https://kiwix.app/b.xhtml#x"))
    assertEquals(EpubNavState(hasPrevious = true, hasNext = false), r.navStateFor("https://kiwix.app/c.xhtml"))
  }

  @Test
  fun `nav state is empty for null or unknown urls`() {
    val r = reader()
    assertEquals(EpubNavState(), r.navStateFor(null))
    assertEquals(EpubNavState(), r.navStateFor("https://kiwix.app/zzz.xhtml"))
    assertEquals(EpubNavState(), r.navStateFor("https://example.com/a.xhtml"))
  }

  @Test
  fun `position round trips including urls with separators and fragments`() {
    val p = EpubReadingPosition("https://kiwix.app/a|b.xhtml#frag", 1234)
    assertEquals(p, EpubReadingPosition.decode(p.encode()))
  }

  @Test
  fun `position decode rejects malformed values and clamps negatives`() {
    assertNull(EpubReadingPosition.decode(null))
    assertNull(EpubReadingPosition.decode(""))
    assertNull(EpubReadingPosition.decode("abc|https://kiwix.app/a.xhtml"))
    assertNull(EpubReadingPosition.decode("12|"))
    assertNull(EpubReadingPosition.decode("12"))
    assertEquals(0, EpubReadingPosition.decode("-5|https://kiwix.app/a.xhtml")?.scrollY)
  }
}
