/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.kiwix.kiwixmobile.nav.destination.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kiwix.kiwixmobile.R
import org.kiwix.kiwixmobile.core.data.remote.KiwixService
import org.kiwix.kiwixmobile.core.entity.LibkiwixBook
import org.kiwix.kiwixmobile.core.zim_manager.Category
import org.kiwix.kiwixmobile.core.zim_manager.ConnectivityObserver
import org.kiwix.kiwixmobile.core.zim_manager.NetworkState
import org.kiwix.kiwixmobile.nav.destination.library.online.helper.ObserveCategories
import org.kiwix.kiwixmobile.nav.destination.library.online.repository.OnlineLibraryRepository
import org.kiwix.kiwixmobile.nav.destination.library.online.viewmodel.OnlineLibraryViewModel.OnlineLibraryRequest
import org.kiwix.kiwixmobile.nav.destination.library.online.viewmodel.OnlineLibraryViewModel.OnlineLibraryState
import org.kiwix.kiwixmobile.nav.destination.library.online.viewmodel.OnlineLibraryViewModel.OnlineLibraryState.Success
import javax.inject.Inject

private const val SEARCH_DEBOUNCE_MILLIS = 400L

@HiltViewModel
class HomeViewModel @Inject constructor(
  private val application: Application,
  private val observeCategories: ObserveCategories,
  private val onlineLibraryRepository: OnlineLibraryRepository,
  private val connectivityObserver: ConnectivityObserver
) : ViewModel() {
  data class UiState(
    val categories: List<Category> = emptyList(),
    val selectedCategories: Set<String> = emptySet(),
    val booksByCategory: Map<String, List<LibkiwixBook>> = emptyMap(),
    val searchResults: List<LibkiwixBook> = emptyList(),
    val query: String = "",
    val loadingCategories: Boolean = true,
    val loadingShelves: Set<String> = emptySet()
  )

  private val _uiState = MutableStateFlow(UiState())
  val uiState = _uiState.asStateFlow()
  private val shelfJobs = mutableMapOf<String, Job>()
  private var queryJob: Job? = null

  init {
    connectivityObserver.register()
    loadCategories()
  }

  fun setQuery(query: String) {
    val current = uiState.value
    val affectedShelves = current.selectedCategories + SEARCH_SHELF
    affectedShelves.forEach { shelfJobs.remove(it)?.cancel() }
    _uiState.update { state ->
      state.copy(
        query = query,
        searchResults = emptyList(),
        booksByCategory = state.booksByCategory.mapValues { (category, books) ->
          if (category in current.selectedCategories) emptyList() else books
        },
        loadingShelves = state.loadingShelves - affectedShelves
      )
    }
    queryJob?.cancel()
    queryJob = viewModelScope.launch {
      delay(SEARCH_DEBOUNCE_MILLIS)
      val latest = uiState.value
      if (latest.selectedCategories.isEmpty()) {
        loadShelf(SEARCH_SHELF, null, query)
      } else {
        latest.categories
          .filter { it.category in latest.selectedCategories }
          .forEach { loadShelf(it.category, it.category, query) }
      }
    }
  }

  fun toggleCategory(category: Category) {
    val selected = uiState.value.selectedCategories.toMutableSet()
    if (!selected.add(category.category)) selected.remove(category.category)
    if (category.category !in selected) shelfJobs.remove(category.category)?.cancel()
    _uiState.update { it.copy(selectedCategories = selected) }
    if (category.category !in selected) {
      _uiState.update { it.copy(loadingShelves = it.loadingShelves - category.category) }
    } else {
      loadShelf(category.category, category.category, uiState.value.query)
    }
  }

  fun refresh() {
    val current = uiState.value
    if (current.selectedCategories.isEmpty()) {
      if (current.query.isNotBlank()) loadShelf(SEARCH_SHELF, null, current.query)
    } else {
      current.categories
        .filter { it.category in current.selectedCategories }
        .forEach { loadShelf(it.category, it.category, current.query) }
    }
  }

  private fun loadCategories() {
    viewModelScope.launch {
      val isOnline = connectivityObserver.networkStates.value == NetworkState.CONNECTED
      when (
        val result = observeCategories(
          errorNoCategory = application.getString(R.string.no_category_available),
          errorNoNetwork = application.getString(org.kiwix.kiwixmobile.core.R.string.no_network_connection),
          isOnline = isOnline
        )
      ) {
        is ObserveCategories.Result.Success -> _uiState.update {
          it.copy(categories = result.categories, loadingCategories = false)
        }

        is ObserveCategories.Result.Error -> _uiState.update { it.copy(loadingCategories = false) }
      }
    }
  }

  private fun loadShelf(key: String, category: String?, query: String) {
    shelfJobs[key]?.cancel()
    if (query.isBlank() && category == null) {
      _uiState.update { it.copy(searchResults = emptyList()) }
      return
    }
    _uiState.update { it.copy(loadingShelves = it.loadingShelves + key) }
    shelfJobs[key] = viewModelScope.launch {
      var books = emptyList<LibkiwixBook>()
      onlineLibraryRepository.fetchOnlineLibrary(
        OnlineLibraryRequest(
          query = query.takeIf(String::isNotBlank),
          category = category,
          lang = null,
          isLoadMoreItem = false,
          page = 0
        )
      ).collect { result ->
        if (result is Success) books = result.books.take(KiwixService.ITEMS_PER_PAGE)
        if (result is OnlineLibraryState.Error) books = emptyList()
      }
      _uiState.update { state ->
        state.copy(
          booksByCategory = if (key == SEARCH_SHELF) {
            state.booksByCategory
          } else {
            state.booksByCategory + (key to books)
          },
          searchResults = if (key == SEARCH_SHELF) books else state.searchResults,
          loadingShelves = state.loadingShelves - key
        )
      }
    }
  }

  override fun onCleared() {
    shelfJobs.values.forEach(Job::cancel)
    connectivityObserver.unregister()
    super.onCleared()
  }

  companion object {
    const val SEARCH_SHELF = "__home_search__"
  }
}
