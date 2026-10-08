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

package org.kiwix.kiwixmobile.core.epub.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.epub.EpubLibraryManager
import org.kiwix.kiwixmobile.core.epub.EpubOpenResult
import org.kiwix.kiwixmobile.core.epub.EpubOpenUseCase
import org.kiwix.kiwixmobile.core.epub.EpubSource
import org.kiwix.kiwixmobile.core.utils.datastore.KiwixDataStore
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression
import java.io.File
import javax.inject.Inject

/** A book that is open in the reader. */
class OpenEpub(
  val publication: Publication,
  val bookId: String,
  val title: String,
  val toc: List<EpubTocItem>,
  val initialLocator: Locator?,
  val rtl: Boolean = false
)

sealed interface EpubReaderUiState {
  data object Loading : EpubReaderUiState
  data object Failed : EpubReaderUiState
  data class Ready(val book: OpenEpub) : EpubReaderUiState
}

/** Opens the book named by [EXTRA_PATH], restores and records its position, owns the settings. */
@HiltViewModel
class EpubReaderViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val opener: EpubPublicationOpener,
  private val libraryManager: EpubLibraryManager,
  private val openUseCase: EpubOpenUseCase,
  private val kiwixDataStore: KiwixDataStore
) : ViewModel() {
  private val _state = MutableStateFlow<EpubReaderUiState>(EpubReaderUiState.Loading)
  val state: StateFlow<EpubReaderUiState> = _state.asStateFlow()

  private val _settings = MutableStateFlow(EpubReaderSettings())
  val settings: StateFlow<EpubReaderSettings> = _settings.asStateFlow()

  private val _currentLocator = MutableStateFlow<Locator?>(null)
  val currentLocator: StateFlow<Locator?> = _currentLocator.asStateFlow()

  // Hidden while reading; the screen shows its own bars until a book is ready.
  private val _chromeVisible = MutableStateFlow(false)
  val chromeVisible: StateFlow<Boolean> = _chromeVisible.asStateFlow()

  private val _positions = MutableStateFlow<List<Locator>>(emptyList())
  val positions: StateFlow<List<Locator>> = _positions.asStateFlow()

  /** Open panels; they survive an activity recreation, which restores no saved state. */
  val panels = EpubReaderPanels()

  private var saveJob: Job? = null

  init {
    open(savedStateHandle.get<String>(EXTRA_PATH))
  }

  private fun open(path: String?) {
    viewModelScope.launch {
      val file = path?.let(::File)?.takeIf { it.isFile }
      if (file == null) {
        _state.value = EpubReaderUiState.Failed
        return@launch
      }
      _settings.value = EpubReaderSettings.fromJson(kiwixDataStore.epubReaderSettingsJson.first())
      // Idempotent: the launcher already added the book, but a restored task skips the launcher.
      val prepared = openUseCase.prepare(EpubSource.Path(file), markOpened = false)
      val bookId = (prepared as? EpubOpenResult.Ready)?.bookId ?: file.absolutePath
      opener.open(file).fold(
        onSuccess = { publication ->
          val locator = EpubLocatorCodec.decode(libraryManager.locator(bookId))
          val title = publication.metadata.title?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
          _currentLocator.value = locator
          _state.value = EpubReaderUiState.Ready(
            OpenEpub(
              publication,
              bookId,
              title,
              flattenToc(publication.tableOfContents),
              locator,
              rtl = publication.metadata.readingProgression == ReadingProgression.RTL
            )
          )
          _positions.value = opener.positions(publication)
        },
        onFailure = { _state.value = EpubReaderUiState.Failed }
      )
    }
  }

  /** Where a freshly attached navigator starts: the latest position, not where the book opened. */
  fun startLocator(book: OpenEpub): Locator? = _currentLocator.value ?: book.initialLocator

  fun setChromeVisible(visible: Boolean) {
    _chromeVisible.value = visible
  }

  fun changeSettings(transform: (EpubReaderSettings) -> EpubReaderSettings) {
    val updated = transform(_settings.value)
    if (updated == _settings.value) return
    _settings.value = updated
    viewModelScope.launch { kiwixDataStore.setEpubReaderSettingsJson(updated.toJson()) }
  }

  /** Called on every page turn; the write is debounced. */
  fun onLocatorChanged(locator: Locator) {
    _currentLocator.value = locator
    saveJob?.cancel()
    saveJob = viewModelScope.launch {
      delay(SAVE_DELAY_MS)
      persist(locator)
    }
  }

  /** Writes the latest position now, e.g. when the screen stops. */
  fun persistPosition() {
    val locator = _currentLocator.value ?: return
    saveJob?.cancel()
    viewModelScope.launch { persist(locator) }
  }

  private suspend fun persist(locator: Locator) {
    val book = (_state.value as? EpubReaderUiState.Ready)?.book ?: return
    withContext(NonCancellable) {
      libraryManager.saveLocator(book.bookId, EpubLocatorCodec.encode(locator))
    }
  }

  override fun onCleared() {
    (_state.value as? EpubReaderUiState.Ready)?.book?.publication?.close()
    super.onCleared()
  }

  companion object {
    const val EXTRA_PATH = "epub_path"
    private const val SAVE_DELAY_MS = 500L
  }
}
