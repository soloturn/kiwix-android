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
import org.junit.jupiter.api.Assertions.assertFalse
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

  private fun epub(extra: String = ""): ByteArray =
    ByteArrayOutputStream().also { out ->
      ZipOutputStream(out).use { z ->
        z.putNextEntry(ZipEntry("mimetype"))
        z.write(EPUB_MIME_TYPE.toByteArray())
        z.closeEntry()
        if (extra.isNotEmpty()) {
          z.putNextEntry(ZipEntry("extra.txt"))
          z.write(extra.toByteArray())
          z.closeEntry()
        }
      }
    }.toByteArray()

  private fun import(
    bytes: ByteArray,
    name: String? = "My Book.epub",
    size: Long = bytes.size.toLong(),
    maxBytes: Long = EPUB_MAX_IMPORT_BYTES,
    freeSpace: (File) -> Long = { Long.MAX_VALUE },
    onOpen: () -> Unit = {}
  ) = EpubContentImporter.import(dir, name, size, maxBytes, freeSpace) {
    onOpen()
    ByteArrayInputStream(bytes)
  }

  private fun imported(result: EpubImportResult): File =
    (result as EpubImportResult.Imported).file

  @Test
  fun `copies a valid epub under a sanitized name with size and content hash`() {
    val bytes = epub()
    val file = imported(import(bytes))
    assertTrue(Regex("My_Book-${bytes.size}-[0-9a-f]{16}\\.epub").matches(file.name))
    assertTrue(file.readBytes().contentEquals(bytes))
    assertEquals(listOf(file.name), dir.list()!!.toList())
  }

  @Test
  fun `second import of the same book reuses the copy without reading the stream`() {
    val bytes = epub()
    val first = imported(import(bytes))
    var opened = false
    val second = imported(import(bytes) { opened = true })
    assertEquals(first, second)
    assertFalse(opened)
  }

  @Test
  fun `same name and size but different content gets a distinct file`() {
    val a = imported(import(epub("aaaa"), size = -1))
    val b = imported(import(epub("bbbb"), size = -1))
    assertTrue(a != b)
    assertEquals(2, dir.list()!!.size)
  }

  @Test
  fun `unknown size still names by content so two books sharing a name do not overwrite`() {
    val a = imported(import(epub("one"), size = -1))
    val b = imported(import(epub("two"), size = -1))
    assertTrue(a != b)
    assertTrue(a.exists() && b.exists())
  }

  @Test
  fun `same book under another name or provider is stored once`() {
    val bytes = epub()
    val first = imported(import(bytes, name = "a.epub"))
    val second = imported(import(bytes, name = "other title.epub", size = -1))
    assertEquals(first, second)
    assertEquals(1, dir.list()!!.size)
  }

  @Test
  fun `unknown size always recopies`() {
    val bytes = epub()
    imported(import(bytes, size = -1))
    var opened = false
    import(bytes, size = -1) { opened = true }
    assertTrue(opened)
  }

  @Test
  fun `invalid content is rejected and leaves nothing behind`() {
    assertEquals(EpubImportResult.Invalid, import("not an epub".toByteArray()))
    assertEquals(0, dir.list()!!.size)
  }

  @Test
  fun `null or failing stream is invalid and leaves nothing behind`() {
    assertEquals(
      EpubImportResult.Invalid,
      EpubContentImporter.import(dir, "a.epub", 5) { null }
    )
    assertEquals(
      EpubImportResult.Invalid,
      EpubContentImporter.import(dir, "a.epub", 5) { throw IOException("boom") }
    )
    assertEquals(0, dir.list()!!.size)
  }

  @Test
  fun `a declared size above the cap is refused before reading`() {
    var opened = false
    val result = import(epub(), size = 100, maxBytes = 99) { opened = true }
    assertEquals(EpubImportResult.TooLarge(100, 99), result)
    assertFalse(opened)
  }

  @Test
  fun `an undeclared size is capped while copying`() {
    val bytes = epub("x".repeat(500))
    val result = import(bytes, size = -1, maxBytes = 100)
    assertTrue(result is EpubImportResult.TooLarge)
    assertEquals(0, dir.list()!!.size)
  }

  @Test
  fun `too little free space is refused with the numbers`() {
    val bytes = epub()
    var opened = false
    val result = import(bytes, freeSpace = { bytes.size.toLong() }) { opened = true }
    assertTrue(result is EpubImportResult.NotEnoughSpace)
    assertTrue((result as EpubImportResult.NotEnoughSpace).required > bytes.size)
    assertFalse(opened)
  }

  @Test
  fun `an already stored book needs no free space`() {
    val bytes = epub()
    imported(import(bytes))
    assertTrue(import(bytes, freeSpace = { 0L }) is EpubImportResult.Imported)
  }

  @Test
  fun `stale part files are removed on import but fresh ones are kept`() {
    val stale = File(dir, "import-1.part").apply {
      writeText("x")
      setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
    }
    val fresh = File(dir, "import-2.part").apply { writeText("x") }
    imported(import(epub()))
    assertFalse(stale.exists())
    assertTrue(fresh.exists())
  }

  @Test
  fun `deleteStaleParts with zero age clears every part file and nothing else`() {
    val part = File(dir, "import-3.part").apply { writeText("x") }
    val book = File(dir, "keep.epub").apply { writeText("x") }
    EpubContentImporter.deleteStaleParts(dir, 0L)
    assertFalse(part.exists())
    assertTrue(book.exists())
  }

  @Test
  fun `sanitizedBase strips paths, extensions and unsafe characters`() {
    assertEquals("book", EpubContentImporter.sanitizedBase("a/b/book.EPUB"))
    assertEquals("book", EpubContentImporter.sanitizedBase(null))
    assertEquals("a_b", EpubContentImporter.sanitizedBase("a:*b.epub"))
    assertEquals("book", EpubContentImporter.sanitizedBase("../.."))
  }
}
