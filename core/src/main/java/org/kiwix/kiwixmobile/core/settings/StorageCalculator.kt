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

package org.kiwix.kiwixmobile.core.settings

import eu.mhutti1.utils.storage.Bytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.di.IoDispatcher
import org.kiwix.kiwixmobile.core.extensions.freeSpace
import org.kiwix.kiwixmobile.core.extensions.isFileExist
import org.kiwix.kiwixmobile.core.extensions.totalSpace
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.saf.LibraryFolder
import java.io.File
import javax.inject.Inject

class StorageCalculator @Inject constructor(
  private val kiwixDataStore: KiwixDataStore,
  @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
  private val libraryFolder: LibraryFolder
) {
  private suspend fun getStorageFile(file: File? = null) =
    file ?: File(kiwixDataStore.selectedStorage.first())

  suspend fun calculateAvailableSpace(file: File? = null): String =
    if (file == null && libraryFolder.activeTreeUri() != null) {
      treeAvailableBytes()?.let { Bytes(it).humanReadable }
        ?: kiwixDataStore.context.getString(R.string.unknown_free_space)
    } else {
      Bytes(availableBytes(getStorageFile(file))).humanReadable
    }

  suspend fun calculateTotalSpace(file: File? = null): String =
    Bytes(totalBytes(getStorageFile(file))).humanReadable

  suspend fun calculateUsedSpace(file: File): String =
    Bytes(totalBytes(file) - availableBytes(file)).humanReadable

  /**
   * Free bytes where the library is written. For a picked SAF folder on a volume we cannot
   * stat (e.g. a cloud provider) the space is unknown; it is then reported as unlimited so the
   * write is attempted and fails with a proper error instead of being blocked upfront.
   */
  suspend fun availableBytes(file: File? = null): Long {
    if (file == null && libraryFolder.activeTreeUri() != null) {
      return treeAvailableBytes() ?: Long.MAX_VALUE
    }
    val storageFile = getStorageFile(file)
    return if (storageFile.isFileExist(ioDispatcher)) {
      storageFile.freeSpace(ioDispatcher)
    } else {
      0L
    }
  }

  private suspend fun treeAvailableBytes(): Long? = withContext(ioDispatcher) {
    libraryFolder.activeTreeUri()?.let(libraryFolder::availableBytes)
  }

  suspend fun totalBytes(file: File) =
    if (file.isFileExist(ioDispatcher)) file.totalSpace(ioDispatcher) else 0L
}
