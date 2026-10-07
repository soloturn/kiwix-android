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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kiwix.kiwixmobile.core.dao.entities.EpubBookRoomEntity
import java.io.File

class EpubOnDiskTest {
  private fun entity(id: String) = EpubBookRoomEntity(
    id = id,
    path = "/books/$id.epub",
    title = "T$id",
    authors = "Jane Doe, John Roe",
    language = "en",
    coverPath = "/covers/$id.jpg",
    size = 7L,
    addedAt = 1L,
    lastOpenedAt = 2L
  )

  @Test
  fun `maps every entity field`() {
    val item = entity("a").toEpubOnDisk()
    assertEquals("a", item.id)
    assertEquals(File("/books/a.epub"), item.file)
    assertEquals("Ta", item.title)
    assertEquals("Jane Doe, John Roe", item.authors)
    assertEquals("en", item.language)
    assertEquals("/covers/a.jpg", item.coverPath)
    assertEquals(7L, item.size)
    assertEquals(1L, item.addedAt)
    assertEquals(2L, item.lastOpenedAt)
    assertFalse(item.isSelected)
  }

  @Test
  fun `toEpubItems keeps selection of previously shown items only`() {
    val previous = listOf(entity("a").toEpubOnDisk(true), entity("b").toEpubOnDisk(false))
    val items = listOf(entity("a"), entity("b"), entity("c")).toEpubItems(previous)
    assertEquals(listOf(true, false, false), items.map { it.isSelected })
  }

  @Test
  fun `toEpubItems drops items no longer in the library`() {
    val previous = listOf(entity("a").toEpubOnDisk(true))
    assertTrue(emptyList<EpubBookRoomEntity>().toEpubItems(previous).isEmpty())
  }
}
