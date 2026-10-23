/*
 * Kiwix Android
 * Copyright (c) 2026 Kiwix <android.kiwix.org>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.kiwix.kiwixmobile.nav.destination.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBarScrollBehavior
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import org.kiwix.kiwixmobile.core.base.BackPressActivityExtensions
import org.kiwix.kiwixmobile.core.entity.LibkiwixBook
import org.kiwix.kiwixmobile.core.epub.EpubOnDisk
import org.kiwix.kiwixmobile.core.main.reader.OnBackPressed
import org.kiwix.kiwixmobile.core.ui.components.KiwixAppBar
import org.kiwix.kiwixmobile.core.ui.components.KiwixSnackbarHost
import org.kiwix.kiwixmobile.core.ui.components.SwipeRefreshLayout
import org.kiwix.kiwixmobile.core.ui.models.ActionMenuItem
import org.kiwix.kiwixmobile.core.zim_manager.Category
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.BooksOnDiskListItem.BookOnDisk
import org.kiwix.kiwixmobile.nav.destination.library.local.LocalLibraryViewModel.LocalLibraryUiState
import org.kiwix.kiwixmobile.ui.BookIcon
import java.io.File
import org.kiwix.kiwixmobile.core.R as CoreR

private val HOME_BOOK_WIDTH = 112.dp
private val BOOK_COVER_SIZE = 64.dp

data class HomeScreenCallbacks(
  val onRefresh: () -> Unit,
  val onLocalBookClick: (BookOnDisk) -> Unit,
  val onEpubBookClick: (EpubOnDisk) -> Unit,
  val onCatalogBookClick: (LibkiwixBook, String?) -> Unit,
  val onUserBackPressed: () -> BackPressActivityExtensions.Super,
  val navigationIcon: @Composable () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  libraryState: LocalLibraryUiState,
  homeViewModel: HomeViewModel,
  callbacks: HomeScreenCallbacks,
  actionMenuItems: List<ActionMenuItem>,
  bottomAppBarScrollBehaviour: BottomAppBarScrollBehavior?,
  navHostController: NavHostController,
  snackbarHostState: SnackbarHostState
) {
  val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
  val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
  Scaffold(
    snackbarHost = { KiwixSnackbarHost(snackbarHostState = snackbarHostState) },
    topBar = {
      KiwixAppBar(
        title = stringResource(CoreR.string.home),
        navigationIcon = callbacks.navigationIcon,
        actionMenuItems = actionMenuItems,
        topAppBarScrollBehavior = scrollBehavior
      )
    },
    modifier = Modifier
      .nestedScroll(scrollBehavior.nestedScrollConnection)
      .let { modifier ->
        bottomAppBarScrollBehaviour?.let { modifier.nestedScroll(it.nestedScrollConnection) } ?: modifier
      }
  ) { padding ->
    SwipeRefreshLayout(
      isRefreshing = libraryState.isSwipeRefreshing,
      isEnabled = !libraryState.scanning.isScanning,
      onRefresh = callbacks.onRefresh,
      modifier = Modifier.fillMaxSize().padding(padding)
    ) {
      HomeContent(libraryState, homeState, homeViewModel, callbacks, navHostController)
    }
  }
}

@Composable
private fun HomeContent(
  libraryState: LocalLibraryUiState,
  homeState: HomeViewModel.UiState,
  homeViewModel: HomeViewModel,
  callbacks: HomeScreenCallbacks,
  navHostController: NavHostController
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 20.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp)
  ) {
    item { OnBackPressed(callbacks.onUserBackPressed, navHostController) }
    item { SearchBooksField(homeState.query, homeViewModel::setQuery) }
    item { LocalBooksShelf(libraryState, homeState.query, callbacks) }
    item { CategoryPicker(homeState, homeViewModel::toggleCategory) }
    if (homeState.query.isNotBlank() && homeState.selectedCategories.isEmpty()) {
      item { SearchResultsShelf(homeState, callbacks.onCatalogBookClick) }
    }
    items(
      items = homeState.categories.filter { it.category in homeState.selectedCategories },
      key = { it.category }
    ) { category ->
      BookShelf(
        title = category.category,
        books = homeState.booksByCategory[category.category].orEmpty(),
        loading = category.category in homeState.loadingShelves,
        onBookClick = { callbacks.onCatalogBookClick(it, category.category) }
      )
    }
  }
}

@Composable
private fun SearchBooksField(query: String, onQueryChanged: (String) -> Unit) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChanged,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    singleLine = true,
    label = { Text(stringResource(CoreR.string.home_search_books)) },
    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
    trailingIcon = {
      if (query.isNotEmpty()) {
        IconButton(onClick = { onQueryChanged("") }) {
          Icon(Icons.Filled.Clear, contentDescription = "Clear search")
        }
      }
    }
  )
}

@Composable
private fun LocalBooksShelf(
  state: LocalLibraryUiState,
  query: String,
  callbacks: HomeScreenCallbacks
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ShelfHeading(stringResource(CoreR.string.home_from_library))
    val zimBooks = state.fileSelectListState.bookOnDiskListItems
      .filterIsInstance<BookOnDisk>()
      .filter { query.isBlank() || it.book.title.contains(query, true) }
    val epubs = state.fileSelectListState.epubItems
      .filter { query.isBlank() || it.title.contains(query, true) }
    if (zimBooks.isEmpty() && epubs.isEmpty()) {
      Text(
        text = stringResource(CoreR.string.home_books_added_hint),
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    } else {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        items(zimBooks, key = { "zim:${it.id}" }) { book ->
          Column(
            modifier = Modifier.width(HOME_BOOK_WIDTH).clickable {
              callbacks.onLocalBookClick(book)
            },
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            BookIcon(iconSource = book.book.favicon, isOnlineLibrary = false)
            BookTitle(book.book.title)
          }
        }
        items(epubs, key = { "epub:${it.id}" }) { book ->
          Column(
            modifier = Modifier.width(HOME_BOOK_WIDTH).clickable {
              callbacks.onEpubBookClick(book)
            },
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            AsyncImage(
              model = book.coverPath?.let(::File),
              contentDescription = book.title,
              modifier = Modifier.size(BOOK_COVER_SIZE),
              placeholder = androidx.compose.ui.res.painterResource(
                CoreR.drawable.default_epub_cover_icon
              ),
              error = androidx.compose.ui.res.painterResource(
                CoreR.drawable.default_epub_cover_icon
              )
            )
            BookTitle(book.title)
          }
        }
      }
    }
  }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CategoryPicker(state: HomeViewModel.UiState, onToggle: (Category) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ShelfHeading(stringResource(CoreR.string.home_categories))
    if (state.loadingCategories && state.categories.isEmpty()) {
      Text(stringResource(CoreR.string.home_loading_categories), modifier = Modifier.padding(horizontal = 16.dp))
    } else {
      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        state.categories.forEach { category ->
          FilterChip(
            selected = category.category in state.selectedCategories,
            onClick = { onToggle(category) },
            label = { Text(category.category) }
          )
        }
      }
    }
  }
}

@Composable
private fun SearchResultsShelf(
  state: HomeViewModel.UiState,
  onBookClick: (LibkiwixBook, String?) -> Unit
) {
  BookShelf(
    title = stringResource(CoreR.string.home_search_results),
    books = state.searchResults,
    loading = HomeViewModel.SEARCH_SHELF in state.loadingShelves,
    onBookClick = { onBookClick(it, null) }
  )
}

@Composable
private fun BookShelf(
  title: String,
  books: List<LibkiwixBook>,
  loading: Boolean,
  onBookClick: (LibkiwixBook) -> Unit
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ShelfHeading(title)
    when {
      loading && books.isEmpty() -> Text(
        stringResource(CoreR.string.home_loading_books),
        modifier = Modifier.padding(horizontal = 16.dp)
      )
      books.isEmpty() -> Text(
        stringResource(CoreR.string.home_no_books_found),
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      else -> LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        items(books, key = { it.id }) { book ->
          Column(
            modifier = Modifier.width(HOME_BOOK_WIDTH).clickable { onBookClick(book) },
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            BookIcon(iconSource = book.favicon, isOnlineLibrary = true)
            BookTitle(book.title)
          }
        }
      }
    }
  }
}

@Composable
private fun ShelfHeading(title: String) {
  Text(
    text = title,
    modifier = Modifier.padding(horizontal = 16.dp),
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold
  )
}

@Composable
private fun BookTitle(title: String) {
  Text(
    title,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    style = MaterialTheme.typography.bodySmall
  )
}
