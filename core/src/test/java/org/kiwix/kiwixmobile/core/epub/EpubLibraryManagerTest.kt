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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kiwix.kiwixmobile.core.dao.EpubLibraryDao
import org.kiwix.kiwixmobile.core.dao.entities.EpubBookRoomEntity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubLibraryManagerTest {
  @TempDir lateinit var root: File
  private lateinit var dir: File

  private lateinit var dao: FakeEpubLibraryDao
  private val coverStore: EpubCoverStore = mockk(relaxed = true)
  private var now = 1000L
  private lateinit var manager: EpubLibraryManager

  @BeforeEach
  fun setUp() {
    dao = FakeEpubLibraryDao()
    dir = File(root, "t${System.nanoTime()}").also { it.mkdirs() }
    now = 1000L
    infos.clear()
    manager = EpubLibraryManager(dao, coverStore, { infos[it.name] }, Dispatchers.Unconfined)
      .apply { clock = { now } }
  }

  /** What the (faked) Readium metadata reader returns, by file name; unknown files can't be parsed. */
  private val infos = mutableMapOf<String, EpubBookInfo>()

  private fun epub(name: String, identifier: String?, title: String = "My Book"): File {
    infos[name] = EpubBookInfo(identifier, title, listOf("Jane Doe", "John Roe"), "en", null)
    // Just valid enough for the content check; parsing is the (fake) reader's job here.
    return File(dir, name).also { f ->
      ZipOutputStream(f.outputStream()).use { z ->
        z.putNextEntry(ZipEntry("mimetype"))
        z.write("application/epub+zip".toByteArray())
        z.closeEntry()
      }
    }
  }

  @Test
  fun `add stores metadata with identifier as id`() = runTest {
    val file = epub("a.epub", "urn:uuid:1")
    val entry = manager.add(file)!!
    assertEquals("urn:uuid:1", entry.id)
    assertEquals(file.absolutePath, entry.path)
    assertEquals("My Book", entry.title)
    assertEquals("Jane Doe, John Roe", entry.authors)
    assertEquals("en", entry.language)
    assertEquals(file.length(), entry.size)
    assertEquals(1000L, entry.addedAt)
    assertEquals(0L, entry.lastOpenedAt)
    assertEquals(entry, dao.rows.single())
  }

  @Test
  fun `add without identifier falls back to the path`() = runTest {
    val file = epub("b.epub", null)
    assertEquals(file.absolutePath, manager.add(file)!!.id)
  }

  @Test
  fun `add twice dedupes by path and only touches lastOpenedAt`() = runTest {
    val file = epub("a.epub", "id")
    manager.add(file)
    now = 2000L
    val again = manager.add(file, markOpened = true)!!
    assertEquals(1, dao.rows.size)
    assertEquals(1000L, again.addedAt)
    assertEquals(2000L, again.lastOpenedAt)
    assertEquals(2000L, dao.rows.single().lastOpenedAt)
  }

  @Test
  fun `a second file reusing an identifier gets its path as id`() = runTest {
    val first = epub("a.epub", "same")
    val second = epub("b.epub", "same")
    manager.add(first)
    val entry = manager.add(second)!!
    assertEquals(second.absolutePath, entry.id)
    assertEquals(2, dao.rows.size)
  }

  @Test
  fun `a title-less epub is named after its file`() = runTest {
    val entry = manager.add(epub("fallback.epub", "x", title = ""))!!
    assertEquals("fallback", entry.title)
  }

  @Test
  fun `add keeps the cover through the cover store`() = runTest {
    val file = epub("c.epub", "c")
    infos[file.name] = EpubBookInfo("c", "T", emptyList(), null, byteArrayOf(1, 2, 3))
    every { coverStore.save("c", any()) } returns "/covers/c.jpg"
    assertEquals("/covers/c.jpg", manager.add(file)!!.coverPath)
  }

  @Test
  fun `add returns null for a file that is not an epub`() = runTest {
    val junk = File(dir, "junk.epub").apply { writeText("not a zip") }
    assertNull(manager.add(junk))
    assertTrue(dao.rows.isEmpty())
  }

  @Test
  fun `importScanned adds new valid epubs and skips known and invalid ones`() = runTest {
    val known = epub("known.epub", "k")
    val fresh = epub("fresh.epub", "f")
    val junk = File(dir, "junk.epub").apply { writeText("nope") }
    manager.add(known)
    val added = manager.importScanned(listOf(known, fresh, junk, fresh))
    assertEquals(1, added)
    assertEquals(setOf("k", "f"), dao.rows.map { it.id }.toSet())
  }

  @Test
  fun `remove drops the entry and its cover but keeps the file`() = runTest {
    val file = epub("a.epub", "id")
    dao.rows += entity("id", file.absolutePath, cover = "/c/1.jpg")
    manager.remove("id")
    assertTrue(dao.rows.isEmpty())
    assertTrue(file.exists())
    verify { coverStore.delete("/c/1.jpg") }
  }

  @Test
  fun `deleteFileAndEntry removes file and entry`() = runTest {
    val file = epub("a.epub", "id")
    dao.rows += entity("id", file.absolutePath)
    assertTrue(manager.deleteFileAndEntry("id", file))
    assertFalse(file.exists())
    assertTrue(dao.rows.isEmpty())
  }

  @Test
  fun `deleteFileAndEntry keeps the entry when the file cannot be deleted`() = runTest {
    val file: File = mockk()
    every { file.exists() } returns true
    every { file.delete() } returns false
    dao.rows += entity("id", "/x.epub")
    assertFalse(manager.deleteFileAndEntry("id", file))
    assertNotNull(dao.getById("id"))
  }

  private fun entity(id: String, path: String, cover: String? = null) = EpubBookRoomEntity(
    id,
    path,
    "t",
    "a",
    "en",
    cover,
    1L,
    0L,
    0L
  )

  private class FakeEpubLibraryDao : EpubLibraryDao() {
    val rows = mutableListOf<EpubBookRoomEntity>()
    override fun epubs(): Flow<List<EpubBookRoomEntity>> = MutableStateFlow(rows.toList())
    override fun getById(id: String) = rows.firstOrNull { it.id == id }
    override fun getByPath(path: String) = rows.firstOrNull { it.path == path }
    override fun allPaths() = rows.map { it.path }
    override fun upsert(entity: EpubBookRoomEntity) {
      rows.removeAll { it.id == entity.id || it.path == entity.path }
      rows += entity
    }

    override fun markOpened(id: String, openedAt: Long) {
      rows.replaceAll { if (it.id == id) it.copy(lastOpenedAt = openedAt) else it }
    }

    override fun delete(id: String) {
      rows.removeAll { it.id == id }
    }
  }
}
