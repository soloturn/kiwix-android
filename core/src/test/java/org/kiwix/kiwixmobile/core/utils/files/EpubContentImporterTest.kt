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

package org.kiwix.kiwixmobile.core.utils.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubContentImporterTest {
  @TempDir
  lateinit var dir: File

  private fun epub(): ByteArray =
    ByteArrayOutputStream().also { out ->
      ZipOutputStream(out).use { z ->
        z.putNextEntry(ZipEntry("mimetype"))
        z.write(EPUB_MIME_TYPE.toByteArray())
        z.closeEntry()
      }
    }.toByteArray()

  private fun import(
    bytes: ByteArray,
    name: String? = "My Book.epub",
    size: Long = bytes.size.toLong(),
    onOpen: () -> Unit = {}
  ) = EpubContentImporter.import(dir, name, size) {
    onOpen()
    ByteArrayInputStream(bytes)
  }

  @Test
  fun `copies a valid epub under a stable sanitized name`() {
    val bytes = epub()
    val file = import(bytes)
    assertNotNull(file)
    assertEquals("My_Book-${bytes.size}.epub", file!!.name)
    assertTrue(file.readBytes().contentEquals(bytes))
    assertEquals(listOf(file.name), dir.list()!!.toList())
  }

  @Test
  fun `second import of the same book reuses the copy without reading the stream`() {
    val bytes = epub()
    val first = import(bytes)
    var opened = false
    val second = import(bytes) { opened = true }
    assertEquals(first, second)
    assertTrue(!opened)
  }

  @Test
  fun `same name but different size gets a distinct file`() {
    val a = import(epub())
    val b = import(epub() + byteArrayOf(0))
    assertNotNull(a)
    assertNotNull(b)
    assertTrue(a != b)
  }

  @Test
  fun `invalid content is rejected and leaves nothing behind`() {
    val junk = "not an epub".toByteArray()
    assertNull(import(junk))
    assertEquals(0, dir.list()!!.size)
  }

  @Test
  fun `null or failing stream yields null`() {
    assertNull(EpubContentImporter.import(dir, "a.epub", 5) { null })
    assertNull(EpubContentImporter.import(dir, "a.epub", 5) { throw IOException("boom") })
    assertEquals(0, dir.list()!!.size)
  }

  @Test
  fun `unknown size always recopies`() {
    val bytes = epub()
    val first = import(bytes, size = -1)
    assertEquals("My_Book.epub", first!!.name)
    var opened = false
    import(bytes, size = -1) { opened = true }
    assertTrue(opened)
  }

  @Test
  fun `stableFileName strips paths, extensions and unsafe characters`() {
    assertEquals("book-10.epub", EpubContentImporter.stableFileName("a/b/book.EPUB", 10))
    assertEquals("book.epub", EpubContentImporter.stableFileName(null, -1))
    assertEquals("a_b-1.epub", EpubContentImporter.stableFileName("a:*b.epub", 1))
    assertEquals("book-3.epub", EpubContentImporter.stableFileName("../..", 3))
  }
}
