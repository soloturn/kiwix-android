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

package org.kiwix.kiwixmobile.core.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.kiwix.kiwixmobile.core.dao.entities.EpubBookRoomEntity

@Dao
abstract class EpubLibraryDao {
  /** Most recently opened or added first, then by title. */
  @Query(
    "SELECT * FROM EpubBookRoomEntity " +
      "ORDER BY MAX(lastOpenedAt, addedAt) DESC, title COLLATE NOCASE ASC"
  )
  abstract fun epubs(): Flow<List<EpubBookRoomEntity>>

  @Query("SELECT * FROM EpubBookRoomEntity WHERE id = :id")
  abstract fun getById(id: String): EpubBookRoomEntity?

  @Query("SELECT * FROM EpubBookRoomEntity WHERE path = :path")
  abstract fun getByPath(path: String): EpubBookRoomEntity?

  @Query("SELECT path FROM EpubBookRoomEntity")
  abstract fun allPaths(): List<String>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract fun upsert(entity: EpubBookRoomEntity)

  @Query("UPDATE EpubBookRoomEntity SET lastOpenedAt = :openedAt WHERE id = :id")
  abstract fun markOpened(id: String, openedAt: Long)

  @Query("DELETE FROM EpubBookRoomEntity WHERE id = :id")
  abstract fun delete(id: String)
}
