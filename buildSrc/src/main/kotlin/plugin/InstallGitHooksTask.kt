/*
 * Kiwix Android
 * Copyright (c) 2019 Kiwix <android.kiwix.org>
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

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

// A doLast{} closure on a plain script task references the enclosing script to reach
// `providers`/`project`, which comes back null when the configuration cache replays it.
// ExecOperations is injected as a real service instead, so this survives config-cache replay.
abstract class InstallGitHooksTask @Inject constructor(
  private val execOperations: ExecOperations
) : DefaultTask() {
  @get:Internal
  abstract val gitHooksDir: Property<String>

  @TaskAction
  fun run() {
    execOperations.exec {
      commandLine("chmod", "-R", "+x", gitHooksDir.get())
    }
    logger.info("Git hook installed successfully.")
  }
}
