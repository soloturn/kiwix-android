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

package org.kiwix.kiwixmobile.settings

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.mhutti1.utils.storage.StorageDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.StorageObserver
import org.kiwix.kiwixmobile.core.ThemeConfig
import org.kiwix.kiwixmobile.core.dao.LibkiwixBookmarks
import org.kiwix.kiwixmobile.core.data.DataSource
import org.kiwix.kiwixmobile.core.settings.StorageCalculator
import org.kiwix.kiwixmobile.core.settings.viewmodel.Action.ShowToast
import org.kiwix.kiwixmobile.core.settings.viewmodel.CoreSettingsViewModel
import org.kiwix.kiwixmobile.core.settings.viewmodel.CoreSettingsViewModel.LibraryFolderState
import org.kiwix.kiwixmobile.core.utils.KiwixPermissionChecker
import org.kiwix.kiwixmobile.core.utils.StorageDeviceProvider
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.kiwix.kiwixmobile.core.utils.files.saf.LibraryFolder
import javax.inject.Inject

@Suppress("LongParameterList")
@HiltViewModel
class KiwixSettingsViewModel @Inject constructor(
  context: Application,
  kiwixDataStore: KiwixDataStore,
  dataSource: DataSource,
  storageCalculator: StorageCalculator,
  themeConfig: ThemeConfig,
  libkiwixBookmarks: LibkiwixBookmarks,
  kiwixPermissionChecker: KiwixPermissionChecker,
  storageDeviceProvider: StorageDeviceProvider,
  private val libraryFolder: LibraryFolder,
  private val storageObserver: StorageObserver
) : CoreSettingsViewModel(
    context,
    kiwixDataStore,
    dataSource,
    storageCalculator,
    themeConfig,
    libkiwixBookmarks,
    kiwixPermissionChecker,
    storageDeviceProvider,
  ) {
  private var storageDeviceList: List<StorageDevice> = listOf()
  override suspend fun setStorage() {
    settingsUiState.update { it.copy(shouldShowStorageCategory = true) }
    if (storageDeviceList.isNotEmpty()) {
      // update the storage when user switch to other storage.
      setUpStoragePreference()
      return
    }
    showHideProgressBarWhileFetchingStorageInfo(true)
    storageDeviceList = storageDeviceProvider.getWritableStorage()
    showHideProgressBarWhileFetchingStorageInfo(false)
    setUpStoragePreference()
  }

  private suspend fun setUpStoragePreference() {
    settingsUiState.value =
      settingsUiState.value.copy(storageDeviceList = emptyList())
    settingsUiState.value =
      settingsUiState.value.copy(
        storageDeviceList = storageDeviceList,
        libraryFolder = libraryFolderState()
      )
  }

  private suspend fun libraryFolderState(): LibraryFolderState {
    val summary = when (val kind = libraryFolder.current()) {
      is LibraryFolder.Kind.Tree ->
        context.getString(R.string.library_folder_summary, libraryFolder.describe(kind.treeUri))

      is LibraryFolder.Kind.PublicDocuments ->
        context.getString(
          R.string.library_folder_summary,
          "${Environment.DIRECTORY_DOCUMENTS}/${LibraryFolder.KIWIX_DIRECTORY}"
        )

      is LibraryFolder.Kind.AppSpecific ->
        context.getString(R.string.library_folder_app_specific_summary)
    }
    val restoreSummary = libraryFolder.savedTreeUri()
      ?.takeIf { libraryFolder.needsAccessRestore() }
      ?.let { context.getString(R.string.restore_library_summary, libraryFolder.describe(it)) }
    return LibraryFolderState(summary, restoreSummary)
  }

  override suspend fun libraryFolderPickerUri(): Uri = libraryFolder.pickerInitialUri()

  override fun onLibraryFolderPicked(treeUri: Uri) {
    viewModelScope.launch {
      if (!libraryFolder.onTreePicked(treeUri)) {
        sendAction(ShowToast(context.getString(R.string.library_folder_access_denied)))
        return@launch
      }
      setUpStoragePreference()
      val added = storageObserver.syncLibraryTree()
      sendAction(ShowToast(context.getString(R.string.library_folder_books_added, added)))
    }
  }

  override suspend fun showExternalLinksPreference() {
    settingsUiState.update { it.copy(shouldShowExternalLinkPreference = true) }
  }

  override suspend fun showPrefWifiOnlyPreference() {
    settingsUiState.update { it.copy(shouldShowPrefWifiOnlyPreference = true) }
  }

  override suspend fun showPermissionItem() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
      !kiwixDataStore.isPlayStoreBuild.first()
    ) {
      val externalStorageManager = Environment.isExternalStorageManager()
      val permissionSummary = if (externalStorageManager) {
        context.getString(R.string.allowed)
      } else {
        context.getString(R.string.not_allowed)
      }
      settingsUiState.update { it.copy(permissionItem = true to permissionSummary) }
    }
  }

  override suspend fun showLanguageCategory() {
    settingsUiState.update { it.copy(shouldShowLanguageCategory = true) }
  }

  override suspend fun showRatingCategory() {
    settingsUiState.update { it.copy(shouldShowRatingCategory = kiwixDataStore.isPlayStoreBuild.first()) }
  }

  /**
   * Shows or hides the progress bar while the application is fetching
   * storage information in the background. The progress bar is displayed
   * with a title indicating that the storage information is being retrieved.
   *
   * @param show If true, the progress bar will be displayed; otherwise, it will be hidden.
   */
  private fun showHideProgressBarWhileFetchingStorageInfo(show: Boolean) {
    settingsUiState.update { it.copy(isLoadingStorageDetails = show) }
  }
}
