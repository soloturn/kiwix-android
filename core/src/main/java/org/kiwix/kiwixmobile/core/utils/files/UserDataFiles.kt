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

import android.content.Context
import java.io.File

/**
 * Bookmarks, library indexes and notes live in internal storage so that they are covered by
 * auto-backup and survive uninstall/reinstall. Earlier versions kept them in app-specific
 * external storage, which is wiped on uninstall and excluded from backup.
 */
object UserDataFiles {
  const val BOOKMARKS_DIR = "Bookmarks"
  const val LOCAL_LIBRARY_DIR = "ZIMFiles"
  const val BOOKMARK_FILE = "bookmark.xml"
  const val LIBRARY_FILE = "library.xml"
  private const val NOTES_DIR = "Kiwix/Notes/"

  fun internalFile(context: Context, dir: String, name: String): File =
    File(File(context.filesDir, dir), name)

  /**
   * Locations used before the move to internal storage. Emulators wrote straight into filesDir.
   */
  fun legacyFiles(context: Context, dir: String, name: String): List<File> =
    listOfNotNull(
      context.getExternalFilesDir(null)?.let { File(File(it, dir), name) },
      File(context.filesDir, name)
    )

  /** Legacy file to import from, or null when the internal copy already exists. */
  fun migrationSource(context: Context, dir: String, name: String): File? =
    if (internalFile(context, dir, name).exists()) {
      null
    } else {
      legacyFiles(context, dir, name).firstOrNull { it.isFile && it.length() > 0 }
    }

  fun notesDirectory(context: Context): String = "${context.filesDir}/$NOTES_DIR"

  fun legacyNotesDirectory(context: Context): String? =
    context.getExternalFilesDir("")?.let { "$it/$NOTES_DIR" }
}
