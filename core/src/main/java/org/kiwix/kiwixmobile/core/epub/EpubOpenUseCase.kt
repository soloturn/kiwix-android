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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.R.string
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.epub.reader.EpubReaderActivity
import org.kiwix.kiwixmobile.core.extensions.toast
import org.kiwix.kiwixmobile.core.utils.KiwixPermissionChecker
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.EpubImportResult
import org.kiwix.kiwixmobile.core.utils.files.importEpubContentUriResult
import org.kiwix.kiwixmobile.core.utils.files.isAppPrivateFile
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where an EPUB comes from: a readable file, or a `content://` URI that must be imported first. */
sealed interface EpubSource {
  data class Path(val file: File) : EpubSource
  data class Content(val uri: Uri) : EpubSource
}

sealed interface EpubOpenResult {
  /** Imported, validated and in the library under [bookId]. */
  data class Ready(val file: File, val bookId: String) : EpubOpenResult

  /** [message] is user-visible. */
  data class Failed(val message: String) : EpubOpenResult

  /** [file] lives outside app-private storage and the read permission is missing. */
  data class NeedsStoragePermission(val file: File) : EpubOpenResult
}

/**
 * The one way to open an EPUB: import a `content://` source, validate, record it in the library,
 * start [EpubReaderActivity]. Independent of the ZIM reader.
 */
@Singleton
class EpubOpenUseCase @Inject constructor(
  @param:ApplicationContext private val appContext: Context,
  private val libraryManager: EpubLibraryManager,
  private val permissionChecker: KiwixPermissionChecker,
  private val kiwixDataStore: KiwixDataStore,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
  internal var importer: suspend (Context, Uri) -> EpubImportResult = ::importEpubContentUriResult

  /**
   * Imports, validates and adds [source] to the library; [markOpened] stamps it as just read.
   * Never starts the reader and never shows anything.
   */
  suspend fun prepare(source: EpubSource, markOpened: Boolean): EpubOpenResult =
    when (val resolved = resolve(source)) {
      is Resolved.Failure -> resolved.failed
      is Resolved.Local -> addToLibrary(resolved.file, markOpened)
    }

  private suspend fun addToLibrary(file: File, markOpened: Boolean): EpubOpenResult {
    val entry = withContext(ioDispatcher) {
      file.takeIf { it.isFile && it.canRead() }
        ?.let { runCatching { libraryManager.add(it, markOpened) }.getOrNull() }
    }
    return if (entry == null) failed(string.epub_open_failed) else EpubOpenResult.Ready(file, entry.id)
  }

  private suspend fun resolve(source: EpubSource): Resolved = when (source) {
    is EpubSource.Path -> Resolved.Local(source.file)
    is EpubSource.Content -> when (val imported = importer(appContext, source.uri)) {
      is EpubImportResult.Imported -> Resolved.Local(imported.file)
      is EpubImportResult.TooLarge -> Resolved.Failure(
        failed(string.epub_import_too_large, size(imported.size), size(imported.max))
      )

      is EpubImportResult.NotEnoughSpace -> Resolved.Failure(
        failed(string.epub_import_no_space, size(imported.required), size(imported.available))
      )

      EpubImportResult.Invalid -> Resolved.Failure(failed(string.epub_open_failed))
    }
  }

  private sealed interface Resolved {
    data class Local(val file: File) : Resolved
    data class Failure(val failed: EpubOpenResult.Failed) : Resolved
  }

  /**
   * [prepare]s [source] as just opened and starts the reader. A failure is shown as a toast and
   * returned; a missing storage permission is returned for the caller to request.
   */
  suspend fun open(context: Context, source: EpubSource): EpubOpenResult {
    val result = when (val prepared = prepare(source, markOpened = true)) {
      is EpubOpenResult.Ready ->
        if (needsStoragePermission(prepared.file)) {
          EpubOpenResult.NeedsStoragePermission(prepared.file)
        } else {
          prepared
        }

      else -> prepared
    }
    when (result) {
      is EpubOpenResult.Ready -> start(context, result.file)
      is EpubOpenResult.Failed -> appContext.toast(result.message)
      is EpubOpenResult.NeedsStoragePermission -> Unit
    }
    return result
  }

  private suspend fun needsStoragePermission(file: File): Boolean =
    !isAppPrivateFile(appContext, file) &&
      !kiwixDataStore.isBrandedApp.first() &&
      !permissionChecker.hasReadExternalStoragePermission()

  private fun start(context: Context, file: File) {
    val intent = EpubReaderActivity.intent(context, file)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
  }

  private fun failed(resId: Int, vararg args: Any): EpubOpenResult.Failed =
    EpubOpenResult.Failed(appContext.getString(resId, *args))

  private fun size(bytes: Long) = Formatter.formatShortFileSize(appContext, bytes)
}
