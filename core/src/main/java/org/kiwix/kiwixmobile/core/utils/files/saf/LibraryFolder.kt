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

package org.kiwix.kiwixmobile.core.utils.files.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the library lives.
 *
 * - [Kind.Tree]: a folder the user picked through the SAF tree picker. Other apps (file
 *   managers, Syncthing, ...) can share it, and it survives uninstall.
 * - [Kind.PublicDocuments]: the default `Documents/Kiwix`, written without a picker. It also
 *   survives uninstall, but after a reinstall the app needs a tree grant to read it again.
 * - [Kind.AppSpecific]: legacy app-specific storage (or an SD card path), wiped on uninstall.
 */
@Singleton
class LibraryFolder @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val kiwixDataStore: KiwixDataStore
) {
  sealed class Kind {
    data class Tree(val treeUri: Uri) : Kind()
    data class PublicDocuments(val directory: File) : Kind()
    data class AppSpecific(val directory: File) : Kind()
  }

  val documentTree: DocumentTree get() = DocumentTree(context.contentResolver)

  suspend fun current(): Kind {
    activeTreeUri()?.let { return Kind.Tree(it) }
    val selected = File(kiwixDataStore.selectedStorage.first())
    return if (isPublicDocuments(selected)) {
      Kind.PublicDocuments(File(selected, KIWIX_DIRECTORY))
    } else {
      Kind.AppSpecific(selected)
    }
  }

  suspend fun savedTreeUri(): Uri? =
    kiwixDataStore.libraryTreeUri.first()?.takeIf(String::isNotEmpty)?.toUri()

  /** The picked library tree, but only while we still hold its persisted read/write grant. */
  suspend fun activeTreeUri(): Uri? = savedTreeUri()?.takeIf(::hasTreeAccess)

  /** Additional shared tree scanned with the library, independent of its download location. */
  suspend fun activeScanTreeUris(): List<Uri> {
    val documentsTree = savedDocumentsScanTreeUri()
    val mediaTree = savedMediaScanTreeUri()
    if (documentsTree != null) restrictScanTreeToRead(documentsTree)
    if (mediaTree != null) restrictScanTreeToRead(mediaTree)
    return listOfNotNull(
      activeTreeUri(),
      documentsTree?.takeIf(::hasReadTreeAccess),
      mediaTree?.takeIf(::hasReadTreeAccess)
    ).distinct()
  }

  suspend fun savedDocumentsScanTreeUri(): Uri? =
    kiwixDataStore.documentsScanTreeUri.first()?.takeIf(String::isNotEmpty)?.toUri()

  suspend fun documentsScanTreeNeedsAccessRestore(): Boolean =
    savedDocumentsScanTreeUri()?.let { !hasReadTreeAccess(it) } ?: false

  suspend fun onDocumentsScanTreePicked(treeUri: Uri): Boolean {
    val taken = runCatching {
      context.contentResolver.takePersistableUriPermission(treeUri, READ)
    }.onFailure { Log.e(TAG, "No persistable Documents-tree grant for $treeUri: $it") }.isSuccess
    if (!taken) return false
    restrictScanTreeToRead(treeUri)
    val previous = savedDocumentsScanTreeUri()
    if (previous != null && previous != treeUri) {
      releaseScanTreeGrant(previous, savedMediaScanTreeUri() == previous)
    }
    kiwixDataStore.setDocumentsScanTreeUri("$treeUri")
    return true
  }

  suspend fun documentsScanPickerInitialUri(): Uri =
    savedDocumentsScanTreeUri()?.let(::treeRootDocumentUri)
      ?: DocumentsContract.buildDocumentUri(
        EXTERNAL_STORAGE_AUTHORITY,
        "$PRIMARY_VOLUME:${Environment.DIRECTORY_DOCUMENTS}"
      )

  suspend fun savedMediaScanTreeUri(): Uri? =
    kiwixDataStore.mediaScanTreeUri.first()?.takeIf(String::isNotEmpty)?.toUri()

  suspend fun mediaScanTreeNeedsAccessRestore(): Boolean =
    savedMediaScanTreeUri()?.let { !hasReadTreeAccess(it) } ?: false

  /** Android's picker grant for the shared media directory is stored separately from the library. */
  suspend fun onMediaScanTreePicked(treeUri: Uri): Boolean {
    val taken = runCatching {
      context.contentResolver.takePersistableUriPermission(treeUri, READ)
    }.onFailure { Log.e(TAG, "No persistable media-tree grant for $treeUri: $it") }.isSuccess
    if (!taken) return false
    restrictScanTreeToRead(treeUri)
    val previous = savedMediaScanTreeUri()
    if (previous != null && previous != treeUri) {
      releaseScanTreeGrant(previous, savedDocumentsScanTreeUri() == previous)
    }
    kiwixDataStore.setMediaScanTreeUri("$treeUri")
    return true
  }

  suspend fun mediaScanPickerInitialUri(): Uri =
    savedMediaScanTreeUri()?.let(::treeRootDocumentUri)
      ?: DocumentsContract.buildDocumentUri(
        EXTERNAL_STORAGE_AUTHORITY,
        "$PRIMARY_VOLUME:Android/media"
      )

  /** A folder was picked before, but the grant is gone, typically after a reinstall. */
  suspend fun needsAccessRestore(): Boolean = savedTreeUri() != null && activeTreeUri() == null

  fun hasTreeAccess(treeUri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any {
      it.uri == treeUri && it.isReadPermission && it.isWritePermission
    }

  private fun hasReadTreeAccess(treeUri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any {
      it.uri == treeUri && it.isReadPermission
    }

  private suspend fun restrictScanTreeToRead(treeUri: Uri) {
    val hasWriteGrant = context.contentResolver.persistedUriPermissions.any {
      it.uri == treeUri && it.isWritePermission
    }
    if (savedTreeUri() != treeUri && hasWriteGrant) {
      runCatching {
        context.contentResolver.releasePersistableUriPermission(
          treeUri,
          Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
      }
    }
  }

  /** Drop an old scan grant's write bit, unless this tree is also the writable library. */
  private suspend fun releaseScanTreeGrant(treeUri: Uri, usedByOtherScan: Boolean) {
    if (savedTreeUri() != treeUri) {
      runCatching {
        val flags = if (usedByOtherScan) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else READ_WRITE
        context.contentResolver.releasePersistableUriPermission(treeUri, flags)
      }
    }
  }

  /** Persists the grant for a freshly picked tree and makes it the library folder. */
  suspend fun onTreePicked(treeUri: Uri): Boolean {
    val taken = runCatching {
      context.contentResolver.takePersistableUriPermission(treeUri, READ_WRITE)
    }.onFailure { Log.e(TAG, "No persistable grant for $treeUri: $it") }.isSuccess
    if (!taken) return false
    val previous = savedTreeUri()
    if (previous != null && previous != treeUri) {
      runCatching { context.contentResolver.releasePersistableUriPermission(previous, READ_WRITE) }
    }
    kiwixDataStore.setLibraryTreeUri("$treeUri")
    return true
  }

  /** Where the folder picker should open: the last picked folder, else `Documents/Kiwix`. */
  suspend fun pickerInitialUri(): Uri =
    savedTreeUri()?.let(::treeRootDocumentUri)
      ?: DocumentsContract.buildDocumentUri(
        EXTERNAL_STORAGE_AUTHORITY,
        "$PRIMARY_VOLUME:${Environment.DIRECTORY_DOCUMENTS}/$KIWIX_DIRECTORY"
      )

  fun publicLibraryDirectory(): File =
    File(publicDocumentsDirectory(), KIWIX_DIRECTORY)

  fun publicDocumentsDirectory(): File = kiwixDataStore.publicDocumentsDirectory()

  fun isPublicDocuments(directory: File): Boolean =
    runCatching { directory.canonicalPath == publicDocumentsDirectory().canonicalPath }
      .getOrDefault(false)

  /** Human readable location, e.g. "Documents/Kiwix" or "SD card 1A2B-3C4D: Books". */
  fun describe(treeUri: Uri): String {
    val documentId = treeDocumentId(treeUri) ?: return "$treeUri"
    val volume = documentId.substringBefore(':')
    val path = documentId.substringAfter(':', "")
    return when {
      treeUri.authority != EXTERNAL_STORAGE_AUTHORITY ->
        documentTree.stat(treeRootDocumentUri(treeUri))?.name ?: documentId

      volume == PRIMARY_VOLUME -> path.ifEmpty { "/" }
      else -> "$volume: $path"
    }
  }

  /**
   * Directory of the volume a tree lives on, for free-space and filesystem checks. Null when
   * the provider is not backed by a local volume we can stat (e.g. a cloud provider).
   */
  fun volumeDirectory(treeUri: Uri): File? {
    val documentId = treeDocumentId(treeUri)
      ?.takeIf { treeUri.authority == EXTERNAL_STORAGE_AUTHORITY } ?: return null
    val volume = documentId.substringBefore(':')
    val root = if (volume == PRIMARY_VOLUME) {
      Environment.getExternalStorageDirectory()
    } else {
      secondaryVolumeDirectory(volume)
    }
    return root?.let { File(it, documentId.substringAfter(':', "")).takeIf(File::exists) ?: it }
  }

  private fun treeDocumentId(treeUri: Uri): String? =
    runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()

  private fun secondaryVolumeDirectory(uuid: String): File? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val storageManager = context.getSystemService(StorageManager::class.java)
      storageManager?.storageVolumes
        ?.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
        ?.directory
        ?.let { return it }
    }
    return File("/storage/$uuid").takeIf(File::isDirectory)
  }

  /** Free bytes on the volume behind [treeUri], or null if that volume cannot be determined. */
  fun availableBytes(treeUri: Uri): Long? =
    volumeDirectory(treeUri)?.let { directory ->
      runCatching { StatFs(directory.path).availableBytes }.getOrNull()
    }

  fun treeRootDocumentUri(treeUri: Uri): Uri =
    DocumentsContract.buildDocumentUriUsingTree(
      treeUri,
      DocumentsContract.getTreeDocumentId(treeUri)
    )

  companion object {
    /**
     * If [documentUri] (e.g. from ACTION_OPEN_DOCUMENT) lies inside a folder we hold a tree
     * grant for, returns it re-addressed through that tree. Such files need no per-file grant,
     * which matters because per-file grants are capped (128 up to Android 10, 512 after).
     * Relies on path-like document ids, as ExternalStorageProvider uses.
     */
    fun documentInGrantedTree(resolver: ContentResolver, documentUri: Uri): Uri? {
      val documentId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull()
        ?: return null
      return resolver.persistedUriPermissions
        .map { it.uri }
        .filter { it.authority == documentUri.authority && DocumentsContract.isTreeUri(it) }
        .firstOrNull { tree ->
          val root = DocumentsContract.getTreeDocumentId(tree)
          documentId == root || documentId.startsWith("$root/")
        }
        ?.let { DocumentsContract.buildDocumentUriUsingTree(it, documentId) }
    }

    private const val TAG = "LibraryFolder"
    const val KIWIX_DIRECTORY = "Kiwix"
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val PRIMARY_VOLUME = "primary"
    const val READ_WRITE =
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
  }
}
