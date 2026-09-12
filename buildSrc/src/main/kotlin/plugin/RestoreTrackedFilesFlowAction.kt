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

import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import java.io.File

/**
 * Configuration-cache-compatible replacement for a `gradle.buildFinished {}` listener (which the
 * configuration cache rejects outright) - restores the tracked files [RenameTarakFileTask] backed
 * up, regardless of how the build finished.
 */
abstract class RestoreTrackedFilesFlowAction : FlowAction<RestoreTrackedFilesFlowAction.Parameters> {
  interface Parameters : FlowParameters {
    @get:Input
    val trackedFiles: ListProperty<File>

    @get:Input
    val backupDir: Property<File>

    @get:Input
    val rootDir: Property<File>
  }

  override fun execute(parameters: Parameters) {
    val backupDir = parameters.backupDir.get()
    val rootDir = parameters.rootDir.get()
    parameters.trackedFiles.get().forEach {
      TrackedFileBackupAction.restore(it, backupDir, rootDir)
    }
    if (backupDir.exists() && backupDir.listFiles().isNullOrEmpty()) {
      backupDir.delete()
    }
  }
}
