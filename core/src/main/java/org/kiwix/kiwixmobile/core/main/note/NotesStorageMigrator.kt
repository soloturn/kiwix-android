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

package org.kiwix.kiwixmobile.core.main.note

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.dao.NotesRoomDao
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.utils.files.Log
import org.kiwix.kiwixmobile.core.utils.files.UserDataFiles
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves notes saved by older versions from app-specific external storage into internal storage
 * and rewrites their stored paths, so notes are backed up and survive uninstall.
 */
@Singleton
class NotesStorageMigrator @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val notesRoomDao: NotesRoomDao,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  suspend fun migrate(
    legacyDirectory: String? = UserDataFiles.legacyNotesDirectory(context),
    newDirectory: String = UserDataFiles.notesDirectory(context)
  ) = withContext(ioDispatcher) {
    val legacyDir = legacyDirectory?.let(::File)?.takeIf(File::isDirectory) ?: return@withContext
    runCatching {
      // Copy first and delete last, so an interruption leaves the old notes readable.
      legacyDir.copyRecursively(File(newDirectory), overwrite = false) { _, exception ->
        if (exception is FileAlreadyExistsException) {
          OnErrorAction.SKIP
        } else {
          throw exception
        }
      }
      notesRoomDao.replaceNoteFilePathPrefix(legacyDirectory, newDirectory)
      legacyDir.deleteRecursively()
    }.onFailure { Log.e(TAG, "Could not migrate notes: $it") }
  }

  companion object {
    private const val TAG = "NotesStorageMigrator"
  }
}
