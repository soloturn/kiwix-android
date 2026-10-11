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

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.dao.EpubLibraryDao
import org.kiwix.kiwixmobile.core.dao.entities.EpubBookRoomEntity
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.utils.files.isValidEpubFile
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Persists EPUBs as library entries (separate from the libkiwix ZIM library). */
@Singleton
class EpubLibraryManager @Inject constructor(
  private val dao: EpubLibraryDao,
  private val coverStore: EpubCoverStore,
  private val metadataReader: EpubMetadataReader,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  @VisibleForTesting
  internal var clock: () -> Long = System::currentTimeMillis

  fun epubs(): Flow<List<EpubBookRoomEntity>> = dao.epubs()

  /**
   * Adds [file] (deduped by path) or refreshes it when its size changed; [markOpened] stamps
   * lastOpenedAt. Returns the entry, or null if the file can't be parsed as an EPUB.
   */
  suspend fun add(file: File, markOpened: Boolean = false): EpubBookRoomEntity? =
    withContext(ioDispatcher) {
      val path = file.absolutePath
      val now = clock()
      val existing = dao.getByPath(path)
      if (existing != null && existing.size == file.length()) {
        if (markOpened) dao.markOpened(existing.id, now)
        return@withContext if (markOpened) existing.copy(lastOpenedAt = now) else existing
      }
      val info = metadataReader.read(file) ?: return@withContext null
      val id = existing?.id ?: resolveId(info.identifier, path)
      coverStore.delete(existing?.coverPath)
      val entity = info.toEntity(
        id = id,
        path = path,
        size = file.length(),
        coverPath = info.cover?.takeIf { it.size <= MAX_COVER_BYTES }?.let { coverStore.save(id, it) },
        addedAt = existing?.addedAt ?: now,
        lastOpenedAt = if (markOpened) now else existing?.lastOpenedAt ?: 0L,
        lastLocator = existing?.lastLocator
      )
      dao.upsert(entity)
      entity
    }

  /** The Readium locator JSON last saved for [id], or null. */
  suspend fun locator(id: String): String? = withContext(ioDispatcher) { dao.getLocator(id) }

  suspend fun saveLocator(id: String, locatorJson: String) = withContext(ioDispatcher) {
    dao.setLocator(id, locatorJson)
  }

  /** Adds scanned files not already known by path; returns how many were added. */
  suspend fun importScanned(files: Collection<File>): Int = withContext(ioDispatcher) {
    val known = dao.allPaths().toHashSet()
    files.distinctBy { it.absolutePath }
      .filter { it.absolutePath !in known && isValidEpubFile(it) }
      .count { add(it) != null }
  }

  /** Drops the library entry only; the file is left alone. */
  suspend fun remove(id: String) = withContext(ioDispatcher) {
    coverStore.delete(dao.getById(id)?.coverPath)
    dao.delete(id)
  }

  /** Deletes the file itself (plain delete, no ZIM chunk handling) then the entry. */
  suspend fun deleteFileAndEntry(id: String, file: File): Boolean = withContext(ioDispatcher) {
    if (file.exists()) file.delete()
    if (file.exists()) return@withContext false
    remove(id)
    true
  }

  private suspend fun resolveId(identifier: String?, path: String): String {
    val candidate = identifier?.trim().takeUnless { it.isNullOrEmpty() } ?: return path
    val owner = dao.getById(candidate)
    return if (owner == null || owner.path == path) candidate else path
  }

  private fun EpubBookInfo.toEntity(
    id: String,
    path: String,
    size: Long,
    coverPath: String?,
    addedAt: Long,
    lastOpenedAt: Long,
    lastLocator: String?
  ) = EpubBookRoomEntity(
    id = id,
    path = path,
    title = title.ifBlank { File(path).nameWithoutExtension },
    authors = authors.joinToString(", "),
    language = language.orEmpty(),
    coverPath = coverPath,
    size = size,
    addedAt = addedAt,
    lastOpenedAt = lastOpenedAt,
    lastLocator = lastLocator
  )

  private companion object {
    const val MAX_COVER_BYTES = 8 * 1024 * 1024
  }
}
