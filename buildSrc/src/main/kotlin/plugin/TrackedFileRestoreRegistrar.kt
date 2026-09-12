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

import org.gradle.api.flow.FlowScope
import java.io.File
import javax.inject.Inject

/**
 * `FlowScope` is only injectable into a class's constructor, not accessible directly from a
 * `.gradle.kts` script - `project.objects.newInstance(TrackedFileRestoreRegistrar::class.java)`
 * gets Gradle to supply it. See [RestoreTrackedFilesFlowAction].
 */
abstract class TrackedFileRestoreRegistrar @Inject constructor(private val flowScope: FlowScope) {
  fun register(
    trackedFiles: List<File>,
    backupDir: File,
    rootDir: File
  ) {
    flowScope.always(RestoreTrackedFilesFlowAction::class.java) {
      parameters.trackedFiles.set(trackedFiles)
      parameters.backupDir.set(backupDir)
      parameters.rootDir.set(rootDir)
    }
  }
}
