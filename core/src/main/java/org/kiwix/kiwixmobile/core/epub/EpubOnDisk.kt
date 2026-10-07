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

import org.kiwix.kiwixmobile.core.dao.entities.EpubBookRoomEntity
import java.io.File

/** A library row for an EPUB, as shown in the local library list. */
data class EpubOnDisk(
  val id: String,
  val file: File,
  val title: String,
  val authors: String,
  val language: String,
  val coverPath: String?,
  val size: Long,
  val addedAt: Long,
  val lastOpenedAt: Long,
  val isSelected: Boolean = false
)

fun EpubBookRoomEntity.toEpubOnDisk(isSelected: Boolean = false) = EpubOnDisk(
  id = id,
  file = File(path),
  title = title,
  authors = authors,
  language = language,
  coverPath = coverPath,
  size = size,
  addedAt = addedAt,
  lastOpenedAt = lastOpenedAt,
  isSelected = isSelected
)

/** Maps [entities] to list items, keeping the selection of items that were already shown. */
fun List<EpubBookRoomEntity>.toEpubItems(previous: List<EpubOnDisk> = emptyList()): List<EpubOnDisk> {
  val selected = previous.filter { it.isSelected }.map { it.id }.toSet()
  return map { it.toEpubOnDisk(isSelected = it.id in selected) }
}
