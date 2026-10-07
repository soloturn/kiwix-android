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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubFileReaderTest {
  @TempDir lateinit var dir: File

  private fun zip(name: String, entries: List<Pair<String, String>>): File {
    val f = File(dir, name)
    ZipOutputStream(f.outputStream()).use { z ->
      entries.forEach { (n, c) ->
        z.putNextEntry(ZipEntry(n))
        z.write(c.toByteArray())
        z.closeEntry()
      }
    }
    return f
  }

  private val container = """<?xml version="1.0"?>
    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
    </container>"""

  private fun epub3(cover: Boolean = true, extra: List<Pair<String, String>> = emptyList()): File {
    val coverItem = if (cover) {
      """<item id="cov" href="images/cover.png" media-type="image/png" properties="cover-image"/>"""
    } else {
      ""
    }
    val opf = """<?xml version="1.0"?>
      <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
          <dc:identifier>other</dc:identifier>
          <dc:identifier id="bid">urn:uuid:1234</dc:identifier>
          <dc:title>My Book</dc:title>
          <dc:creator>Jane Doe</dc:creator><dc:creator>John Roe</dc:creator>
          <dc:language>en</dc:language>
        </metadata>
        <manifest>
          <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
          <item id="c1" href="ch%201.xhtml" media-type="application/xhtml+xml"/>
          <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
          <item id="css" href="style.css" media-type="text/css"/>
          $coverItem
        </manifest>
        <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="nav" linear="no"/></spine>
      </package>"""
    val nav = """<?xml version="1.0"?>
      <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
        <nav epub:type="landmarks"><ol><li><a href="ch%201.xhtml">Start</a></li></ol></nav>
        <nav epub:type="toc"><ol>
          <li><a href="ch%201.xhtml">Chapter  One</a>
            <ol><li><a href="ch%201.xhtml#s1">Section 1</a></li></ol></li>
          <li><a href="text/ch2.xhtml">Chapter Two</a></li>
        </ol></nav></body></html>"""
    return zip(
      "b3.epub",
      listOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to container,
        "OEBPS/content.opf" to opf,
        "OEBPS/nav.xhtml" to nav,
        "OEBPS/ch 1.xhtml" to "<html>one</html>",
        "OEBPS/text/ch2.xhtml" to "<html>two</html>",
        "OEBPS/style.css" to "body{}",
        "OEBPS/images/cover.png" to "PNG"
      ) + extra
    )
  }

  private fun epub2(): File {
    val opf = """<?xml version="1.0"?>
      <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
          <dc:title>Old Book</dc:title><dc:creator>A. Writer</dc:creator>
          <dc:identifier id="id">isbn:1</dc:identifier><dc:language>fr</dc:language>
          <meta name="cover" content="coverimg"/>
        </metadata>
        <manifest>
          <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
          <item id="a" href="a.html" media-type="application/xhtml+xml"/>
          <item id="b" href="b.html" media-type="application/xhtml+xml"/>
          <item id="coverimg" href="Cover.JPG" media-type="image/jpeg"/>
        </manifest>
        <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine>
      </package>"""
    val ncx = """<?xml version="1.0"?>
      <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
      <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
        <navPoint id="1"><navLabel><text>Alpha</text></navLabel><content src="a.html"/>
          <navPoint id="2"><navLabel><text>Alpha sub</text></navLabel><content src="a.html#x"/></navPoint>
        </navPoint>
        <navPoint id="3"><navLabel><text>Beta</text></navLabel><content src="b.html"/></navPoint>
      </navMap></ncx>"""
    return zip(
      "b2.epub",
      listOf(
        "META-INF/container.xml" to container.replace("OEBPS/content.opf", "content.opf"),
        "content.opf" to opf,
        "toc.ncx" to ncx,
        "a.html" to "A",
        "b.html" to "B",
        "Cover.JPG" to "JPG"
      )
    )
  }

  @Test
  fun epub3MetadataManifestAndSpine() {
    val r = EpubFileReader(epub3())
    assertEquals("My Book", r.title)
    assertEquals(listOf("Jane Doe", "John Roe"), r.metadata.creators)
    assertEquals("en", r.metadata.language)
    assertEquals("urn:uuid:1234", r.metadata.identifier)
    assertEquals("OEBPS/images/cover.png", r.metadata.coverPath)
    assertEquals("https://kiwix.app/OEBPS/images/cover.png", r.coverUrl)
    assertEquals("https://kiwix.app/OEBPS/ch%201.xhtml", r.mainPageUrl)
    assertEquals(3, r.spineUrls.size)
    r.dispose()
  }

  @Test
  fun epub3TocFromNavPicksTocNavAndNests() {
    val r = EpubFileReader(epub3())
    assertEquals(listOf("Chapter One", "Chapter Two"), r.toc.map { it.title })
    val sub = r.toc[0].children.single()
    assertEquals("Section 1", sub.title)
    assertEquals("OEBPS/ch 1.xhtml", sub.path)
    assertEquals("s1", sub.fragment)
    assertEquals("https://kiwix.app/OEBPS/ch%201.xhtml#s1", sub.url)
    assertEquals("OEBPS/text/ch2.xhtml", r.toc[1].path)
    r.dispose()
  }

  @Test
  fun epub2NcxFallbackAndMetaCover() {
    val r = EpubFileReader(epub2())
    assertEquals("Old Book", r.title)
    assertEquals("fr", r.metadata.language)
    assertEquals("Cover.JPG", r.metadata.coverPath)
    assertEquals(listOf("Alpha", "Beta"), r.toc.map { it.title })
    assertEquals("a.html", r.toc[0].children.single().path)
    assertEquals("x", r.toc[0].children.single().fragment)
    assertEquals("image/jpeg", r.getMimeTypeFromUrl("https://kiwix.app/Cover.JPG"))
    r.dispose()
  }

  @Test
  fun missingCoverIsNull() {
    val r = EpubFileReader(epub3(cover = false))
    assertNull(r.metadata.coverPath)
    assertNull(r.coverUrl)
    r.dispose()
  }

  @Test
  fun titleFallsBackToFileName() {
    val opf = """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest>
      <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
      <spine><itemref idref="a"/></spine></package>"""
    val f = zip(
      "untitled.epub",
      listOf(
        "META-INF/container.xml" to container.replace("OEBPS/content.opf", "c.opf"),
        "c.opf" to opf,
        "a.xhtml" to "x"
      )
    )
    val r = EpubFileReader(f)
    assertEquals("untitled", r.title)
    assertTrue(r.toc.isEmpty())
    r.dispose()
  }

  @Test
  fun percentEncodedNamesLoad() {
    val r = EpubFileReader(epub3())
    assertEquals("<html>one</html>", r.load("https://kiwix.app/OEBPS/ch%201.xhtml")!!.readBytes().decodeToString())
    r.dispose()
  }

  @Test
  fun unicodeNamesRoundTrip() {
    val f = epub3(extra = listOf("OEBPS/café.txt" to "latte"))
    val r = EpubFileReader(f)
    val url = r.resolveUrl("https://kiwix.app/OEBPS/text/ch2.xhtml", "../caf%C3%A9.txt")
    assertEquals("https://kiwix.app/OEBPS/caf%C3%A9.txt", url)
    assertEquals("latte", r.load(url!!)!!.readBytes().decodeToString())
    r.dispose()
  }

  @Test
  fun maliciousTraversalIsRejected() {
    val f = epub3(
      extra = listOf(
        "../evil.txt" to "evil",
        "OEBPS/../../evil2.txt" to "evil",
        "/abs.txt" to "abs"
      )
    )
    val r = EpubFileReader(f)
    assertNull(r.load("https://kiwix.app/../evil.txt"))
    assertNull(r.load("https://kiwix.app/%2e%2e/evil.txt"))
    assertNull(r.load("https://kiwix.app/OEBPS/%2E%2E/%2E%2E/evil2.txt"))
    assertNull(r.load("https://kiwix.app/abs.txt"))
    assertNull(r.resolveUrl("https://kiwix.app/OEBPS/nav.xhtml", "../../evil.txt"))
    assertNull(r.load("https://kiwix.app/OEBPS/a%5C..%5Cb"))
    r.dispose()
  }

  @Test
  fun caseInsensitiveFallbackOnlyWhenUnambiguous() {
    val f = epub3(extra = listOf("OEBPS/Dup.txt" to "1", "OEBPS/dup.txt" to "2"))
    val r = EpubFileReader(f)
    assertNotNull(r.load("https://kiwix.app/oebps/STYLE.CSS"))
    assertNotNull(r.load("https://kiwix.app/OEBPS/Dup.txt"))
    assertNull(r.load("https://kiwix.app/OEBPS/DUP.txt"))
    r.dispose()
  }

  @Test
  fun mimeTypes() {
    val r = EpubFileReader(epub3())
    assertEquals("application/xhtml+xml", r.getMimeTypeFromUrl("https://kiwix.app/OEBPS/ch%201.xhtml"))
    assertEquals("text/css", r.getMimeTypeFromUrl("https://kiwix.app/OEBPS/style.css"))
    assertEquals("image/png", r.getMimeTypeFromUrl("https://kiwix.app/OEBPS/images/cover.png"))
    assertNull(r.getMimeTypeFromUrl("https://kiwix.app/OEBPS/missing.css"))
    assertEquals("font/woff2", EpubMimeTypes.fromPath("a/b.WOFF2"))
    assertEquals("application/x-dtbncx+xml", EpubMimeTypes.fromPath("toc.ncx"))
    assertNull(EpubMimeTypes.fromPath("noext"))
    r.dispose()
  }

  @Test
  fun externalUrlsRejected() {
    val r = EpubFileReader(epub3())
    assertTrue(r.isExternalUrl("https://example.com/OEBPS/style.css"))
    assertTrue(r.isExternalUrl("http://kiwix.app/OEBPS/style.css"))
    assertTrue(r.isExternalUrl("https://kiwix.app@evil.com/OEBPS/style.css"))
    assertTrue(r.isExternalUrl("https://kiwix.app.evil.com/x"))
    assertTrue(r.isExternalUrl("https://kiwix.app:8443/x"))
    assertTrue(r.isExternalUrl("file:///etc/passwd"))
    assertTrue(r.isExternalUrl("not a url"))
    assertFalse(r.isExternalUrl("https://KIWIX.app/OEBPS/style.css"))
    assertNull(r.load("https://example.com/OEBPS/style.css"))
    assertNull(r.load("file:///etc/passwd"))
    assertNull(r.resolveUrl("https://example.com/a.xhtml", "b.xhtml"))
    assertNull(r.resolveUrl("https://kiwix.app/OEBPS/nav.xhtml", "https://evil.com/x"))
    assertNull(r.resolveUrl("https://kiwix.app/OEBPS/nav.xhtml", "//evil.com/x"))
    assertTrue(r.isInternalUrl("https://kiwix.app/OEBPS/style.css"))
    assertFalse(r.isInternalUrl("https://kiwix.app/OEBPS/nope.css"))
    r.dispose()
  }

  @Test
  fun relativeResolution() {
    val r = EpubFileReader(epub3())
    assertEquals(
      "https://kiwix.app/OEBPS/style.css",
      r.resolveUrl("https://kiwix.app/OEBPS/text/ch2.xhtml", "../style.css")
    )
    assertEquals(
      "https://kiwix.app/OEBPS/text/ch2.xhtml#top",
      r.resolveUrl("https://kiwix.app/OEBPS/ch%201.xhtml", "text/ch2.xhtml#top")
    )
    assertEquals(
      "https://kiwix.app/OEBPS/style.css",
      r.resolveUrl("https://kiwix.app/OEBPS/text/ch2.xhtml", "/OEBPS/style.css")
    )
    r.dispose()
  }

  @Test
  fun spineNavigationSkipsNonLinear() {
    val r = EpubFileReader(epub3())
    val c1 = "https://kiwix.app/OEBPS/ch%201.xhtml"
    val c2 = "https://kiwix.app/OEBPS/text/ch2.xhtml"
    assertNull(r.previousUrl(c1))
    assertEquals(c2, r.nextUrl(c1))
    assertEquals(c1, r.previousUrl(c2))
    assertNull(r.nextUrl(c2))
    assertNull(r.nextUrl("https://kiwix.app/OEBPS/style.css"))
    r.dispose()
  }

  @Test
  fun xxeIsNotExpanded() {
    val secret = File(dir, "secret.txt").apply { writeText("TOPSECRET") }
    val opf = """<?xml version="1.0"?>
      <!DOCTYPE package [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
      <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>&xxe;</dc:title></metadata>
        <manifest/><spine/></package>"""
    val f = zip(
      "xxe.epub",
      listOf("META-INF/container.xml" to container.replace("OEBPS/content.opf", "c.opf"), "c.opf" to opf)
    )
    val r = EpubFileReader(f)
    assertFalse(r.metadata.title.contains("TOPSECRET"))
    r.dispose()
  }

  @Test
  fun invalidEpubsThrow() {
    assertThrows(EpubException::class.java) { EpubFileReader(zip("e1.epub", listOf("x" to "y"))) }
    val badContainer = zip("e2.epub", listOf("META-INF/container.xml" to "<container><oops"))
    assertThrows(EpubException::class.java) { EpubFileReader(badContainer) }
  }

  @Test
  fun loadAfterDisposeIsNull() {
    val r = EpubFileReader(epub3())
    r.dispose()
    r.dispose()
    assertNull(r.load("https://kiwix.app/OEBPS/style.css"))
  }
}
