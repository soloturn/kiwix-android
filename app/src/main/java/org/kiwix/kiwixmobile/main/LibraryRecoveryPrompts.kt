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

package org.kiwix.kiwixmobile.main

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.mhutti1.utils.storage.Bytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.StorageObserver
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookOnDisk
import org.kiwix.kiwixmobile.core.extensions.toast
import org.kiwix.kiwixmobile.core.utils.HUNDERED
import org.kiwix.kiwixmobile.core.utils.dialog.AlertDialogShower
import org.kiwix.kiwixmobile.core.utils.dialog.KiwixDialog
import org.kiwix.kiwixmobile.core.utils.files.saf.LegacyBooksMover
import org.kiwix.kiwixmobile.core.utils.files.saf.LibraryFolder
import org.kiwix.kiwixmobile.nav.destination.library.local.CopyMoveProgressBarController
import javax.inject.Inject

/**
 * Startup prompts that keep the library safe across uninstalls:
 * - after a reinstall the folder grant is gone (only the files and the backed-up settings
 *   survive), so offer to pick the folder again;
 * - books left in app-specific storage by older versions are offered a move.
 */
@Suppress("LongParameterList")
class LibraryRecoveryPrompts @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val libraryFolder: LibraryFolder,
  private val legacyBooksMover: LegacyBooksMover,
  private val storageObserver: StorageObserver,
  private val libkiwixBookOnDisk: LibkiwixBookOnDisk,
  private val progressBarController: CopyMoveProgressBarController
) {
  private var promptedThisSession = false

  /** Shows at most one prompt; [pickFolder] opens the SAF tree picker at the given place. */
  suspend fun show(
    alertDialogShower: AlertDialogShower,
    scope: CoroutineScope,
    pickFolder: (Uri?) -> Unit
  ) {
    if (promptedThisSession) return
    val restoreFolder = restoreFolderName()
    val offer = if (restoreFolder == null) legacyBooksMover.offer() else null
    when {
      restoreFolder != null -> alertDialogShower.show(
        KiwixDialog.RestoreLibrary(restoreFolder),
        { scope.launch { pickFolder(libraryFolder.pickerInitialUri()) } },
        {}
      )

      offer != null -> alertDialogShower.show(
        KiwixDialog.MoveLegacyBooks(
          offer.books.size,
          Bytes(offer.totalBytes).humanReadable,
          offer.destination
        ),
        { scope.launch { move(offer, alertDialogShower) } },
        { scope.launch { legacyBooksMover.decline() } }
      )

      else -> return
    }
    promptedThisSession = true
  }

  /**
   * A folder to restore: a lost grant, or an empty library where Documents/Kiwix already
   * exists on disk. Checks the directory directly rather than backup-restored bookmarks/notes,
   * since backup isn't instant and a quick reinstall can beat it.
   */
  private suspend fun restoreFolderName(): String? {
    libraryFolder.savedTreeUri()
      ?.takeIf { libraryFolder.needsAccessRestore() }
      ?.let { return libraryFolder.describe(it) }
    val libraryIsEmpty = libkiwixBookOnDisk.getBooks().isEmpty()
    val publicFolderExists = libraryFolder.publicLibraryDirectory().exists()
    return if (libraryIsEmpty && publicFolderExists && libraryFolder.activeTreeUri() == null) {
      "Documents/${LibraryFolder.KIWIX_DIRECTORY}"
    } else {
      null
    }
  }

  private suspend fun move(offer: LegacyBooksMover.Offer, alertDialogShower: AlertDialogShower) {
    progressBarController.setAlertDialogShower(alertDialogShower)
    progressBarController.showProgress(
      context.getString(R.string.moving_books_progress, 0, offer.books.size)
    )
    val result = legacyBooksMover.move(offer) { done, total ->
      progressBarController.updateProgress(done * HUNDERED / total)
    }
    progressBarController.dismissCopyMoveProgressDialog()
    context.toast(context.getString(R.string.move_books_done, result.moved))
    if (result.failed > 0) {
      context.toast(context.getString(R.string.move_books_failed, result.failed))
    }
  }

  /** Called with the tree the user picked from the restore prompt. */
  suspend fun onFolderPicked(treeUri: Uri) {
    if (!libraryFolder.onTreePicked(treeUri)) {
      context.toast(R.string.library_folder_access_denied)
      return
    }
    val added = storageObserver.syncLibraryTree()
    context.toast(context.getString(R.string.library_folder_books_added, added))
  }
}
