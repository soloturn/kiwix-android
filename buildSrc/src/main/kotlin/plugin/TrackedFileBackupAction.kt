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

package plugin

import java.io.File

/**
 * Backs up files the build modifies in place (Tarask strings.xml, ObjectBox's model json) before
 * touching them, and restores the pre-build state once the build finishes - regardless of outcome
 * - so a local build never leaves the working tree dirty with generated content.
 *
 * Plain top-level functions with no `Project` access, so they're safe to call both from a real
 * `Task` (RenameTarakFileTask, at execution time) and from a `FlowAction`
 * (RestoreTrackedFilesFlowAction, registered for build-finished) - both contexts the configuration
 * cache forbids from touching `Project` at all.
 */
object TrackedFileBackupAction {
  fun backup(file: File, backupDir: File, rootDir: File) {
    if (!backupDir.exists()) backupDir.mkdirs()

    val fileKey = fileKey(file, rootDir)
    val backupFile = File(backupDir, "$fileKey.bak")
    val existsMarkerFile = File(backupDir, "$fileKey.exists")
    if (existsMarkerFile.exists()) return

    existsMarkerFile.writeText(if (file.exists()) "1" else "0")
    if (file.exists()) {
      file.copyTo(backupFile, overwrite = true)
    } else if (backupFile.exists()) {
      backupFile.delete()
    }
  }

  fun restore(file: File, backupDir: File, rootDir: File) {
    val fileKey = fileKey(file, rootDir)
    val backupFile = File(backupDir, "$fileKey.bak")
    val existsMarkerFile = File(backupDir, "$fileKey.exists")
    if (!existsMarkerFile.exists()) return

    val existedBeforeBuild = existsMarkerFile.readText().trim() == "1"
    if (existedBeforeBuild && backupFile.exists()) {
      if (!file.parentFile.exists()) file.parentFile.mkdirs()
      backupFile.copyTo(file, overwrite = true)
    } else if (!existedBeforeBuild && file.exists()) {
      file.delete()
    }
    backupFile.delete()
    existsMarkerFile.delete()
  }

  private fun fileKey(file: File, rootDir: File): String =
    file.relativeTo(rootDir).path.replace(File.separator, "_")
}
