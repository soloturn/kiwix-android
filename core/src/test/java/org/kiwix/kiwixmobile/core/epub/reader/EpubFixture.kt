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

import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A small but complete EPUB 3 (two chapters, nav document, cover image) for Readium tests. */
object EpubFixture {
  const val IDENTIFIER = "urn:uuid:readium-fixture"
  const val TITLE = "Fixture Book"

  private const val PNG_1X1 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAHtYr9wAAAABJRU5ErkJggg=="

  fun write(file: File, title: String = TITLE, identifier: String = IDENTIFIER): File {
    val entries = linkedMapOf(
      "mimetype" to "application/epub+zip".toByteArray(),
      "META-INF/container.xml" to CONTAINER.toByteArray(),
      "OEBPS/content.opf" to opf(title, identifier).toByteArray(),
      "OEBPS/nav.xhtml" to NAV.toByteArray(),
      "OEBPS/c1.xhtml" to chapter("Chapter One").toByteArray(),
      "OEBPS/c2.xhtml" to chapter("Chapter Two").toByteArray(),
      "OEBPS/cover.png" to Base64.getDecoder().decode(PNG_1X1)
    )
    ZipOutputStream(file.outputStream()).use { zip ->
      entries.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return file
  }

  private const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

  private fun opf(title: String, identifier: String) = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bid">$identifier</dc:identifier>
    <dc:title>$title</dc:title>
    <dc:creator>Jane Doe</dc:creator>
    <dc:creator>John Roe</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>"""

  private const val NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <head><title>Contents</title></head>
  <body>
    <nav epub:type="toc"><ol>
      <li><a href="c1.xhtml">Chapter One</a></li>
      <li><a href="c2.xhtml">Chapter Two</a></li>
    </ol></nav>
  </body>
</html>"""

  private fun chapter(heading: String) = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>$heading</title></head>
  <body><h1>$heading</h1><p>Some text.</p></body>
</html>"""
}
