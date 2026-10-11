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

package org.kiwix.kiwixmobile.core.dao.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An EPUB in the local library. [id] is the dc:identifier, or the file path when the identifier
 * is missing or already used by another file (same value the EPUB reader reports as its id).
 */
@Entity(indices = [Index(value = ["path"], unique = true)])
data class EpubBookRoomEntity(
  @PrimaryKey val id: String,
  val path: String,
  val title: String,
  val authors: String,
  val language: String,
  /** Absolute path of the cached cover thumbnail, or null. */
  val coverPath: String?,
  val size: Long,
  val addedAt: Long,
  val lastOpenedAt: Long,
  /** Readium Locator JSON of the last reading position, or null. */
  val lastLocator: String? = null
)
