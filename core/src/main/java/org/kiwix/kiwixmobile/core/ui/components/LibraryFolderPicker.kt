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

package org.kiwix.kiwixmobile.core.ui.components

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.extensions.toast

/**
 * Remembers a launcher for the SAF folder picker. The returned function opens it at the given
 * initial location (EXTRA_INITIAL_URI) and reports the picked tree to [onPicked].
 */
@Composable
fun rememberLibraryFolderPicker(onPicked: (Uri) -> Unit): (Uri?) -> Unit {
  val context = LocalContext.current
  val currentOnPicked = rememberUpdatedState(onPicked)
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      uri?.let(currentOnPicked.value)
    }
  return remember(launcher) {
    { initialUri ->
      try {
        launcher.launch(initialUri)
      } catch (_: ActivityNotFoundException) {
        context.toast(R.string.library_folder_access_denied)
      }
    }
  }
}
