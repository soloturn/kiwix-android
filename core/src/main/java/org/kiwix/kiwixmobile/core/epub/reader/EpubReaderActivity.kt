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

import android.app.ActivityManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ActionMode
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.base.BaseActivity
import org.kiwix.kiwixmobile.core.utils.ExternalLinkOpener
import org.kiwix.kiwixmobile.core.utils.dialog.AlertDialogShower
import org.kiwix.kiwixmobile.core.utils.dialog.DialogHost
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File
import java.util.WeakHashMap
import javax.inject.Inject

/**
 * Reads one EPUB with Readium's paginated navigator. Launch it with [intent]; the book's
 * position and the reading settings are restored from, and saved to, the app's data store.
 */
@AndroidEntryPoint
@OptIn(ExperimentalReadiumApi::class)
class EpubReaderActivity : BaseActivity() {
  @Inject lateinit var externalLinkOpener: ExternalLinkOpener

  @Inject lateinit var alertDialogShower: AlertDialogShower

  private val viewModel: EpubReaderViewModel by viewModels()
  private val darkTheme = MutableStateFlow(false)
  private val pageReady = MutableStateFlow(false)
  private val selectionMenuLocator = MutableStateFlow<Locator?>(null)
  private val pageMode = MutableStateFlow(EpubPageMode.SINGLE_PAGE)
  private val pagePreviews = MutableStateFlow<List<EpubPagePreview>>(emptyList())
  private val pagePreviewLoading = MutableStateFlow(false)
  private var selectionActionMode: ActionMode? = null
  private var selectionMenuOpening = false
  private var navigator: EpubNavigatorFragment? = null
  private var previewJob: Job? = null
  private var previewRefreshJob: Job? = null
  private var generatingPagePreviews = false
  private val overviewAttachListeners = WeakHashMap<FragmentContainerView, View.OnAttachStateChangeListener>()
  private lateinit var root: FrameLayout
  private lateinit var navigatorContainer: FragmentContainerView
  private lateinit var previousPagePeek: ImageView
  private lateinit var nextPagePeek: ImageView

  override fun onCreate(savedInstanceState: Bundle?) {
    // The navigator fragment needs a factory built from the opened book, so it can't be restored;
    // the position and open panels live in the view model instead.
    super.onCreate(null)
    externalLinkOpener.initialize(this, alertDialogShower)
    darkTheme.value = isNightMode(resources.configuration)
    navigatorContainer = FragmentContainerView(this).apply { id = View.generateViewId() }
    previousPagePeek = pagePeekView(previous = true)
    nextPagePeek = pagePeekView(previous = false)
    // Immersive: the book fills the screen and the bars and overlay float above it. Only the
    // (constant) display cutout and fixed margins are reserved, never the system bars, so the
    // text doesn't re-paginate when the bars or overlay toggle.
    ViewCompat.setOnApplyWindowInsetsListener(navigatorContainer) { view, insets ->
      val margin = (READING_MARGIN_DP * resources.displayMetrics.density).toInt()
      applyReadingInsets(view, insets, margin)
    }
    root = FrameLayout(this).apply {
      addView(navigatorContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
      // Keep the live Readium page at its normal size; the cached neighboring pages sit over
      // only the outer edge strips, where the page margin normally is.
      addView(previousPagePeek)
      addView(nextPagePeek)
      addView(
        ComposeView(context).apply { setContent { Chrome() } },
        FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
      )
    }
    setContentView(root)
    setUpImmersiveMode()
    lifecycleScope.launch {
      val ready = viewModel.state.filterIsInstance<EpubReaderUiState.Ready>().first()
      attachNavigator(ready.book)
      setTaskTitle(ready.book.title)
    }
    lifecycleScope.launch {
      combine(viewModel.settings, darkTheme, pageMode) { settings, dark, mode ->
        Triple(settings, dark, mode)
      }.collect { (settings, dark, mode) ->
        root.setBackgroundColor(settings.backgroundColor(dark))
        val preferences = settings.toPreferences(dark).let {
          when (mode) {
            EpubPageMode.SINGLE_PAGE -> it.copy(columnCount = ColumnCount.ONE)
            EpubPageMode.CAROUSEL -> it.copy(scroll = false, columnCount = ColumnCount.ONE)
            EpubPageMode.OVERVIEW -> it.copy(scroll = false, columnCount = ColumnCount.ONE)
          }
        }
        navigator?.submitPreferences(preferences)
      }
    }
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    val handled = super.dispatchTouchEvent(event)
    if (event.actionMasked == MotionEvent.ACTION_UP) openSelectionMenuAfterRelease()
    return handled
  }

  private fun openSelectionMenuAfterRelease() {
    val mode = selectionActionMode ?: return
    if (selectionMenuOpening) return
    selectionMenuOpening = true
    lifecycleScope.launch {
      var locator: Locator? = null
      repeat(SELECTION_READY_RETRIES) {
        if (locator == null) {
          delay(SELECTION_READY_RETRY_MS)
          locator = navigator?.currentSelection()?.locator
        }
      }
      val selectedLocator = locator
      if (selectedLocator == null) {
        selectionMenuOpening = false
        return@launch
      }
      selectionMenuLocator.value = selectedLocator
      mode.finish()
    }
  }

  /** System bars show with the overlay (and while loading), otherwise a swipe reveals them. */
  private fun setUpImmersiveMode() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }
    val controller = WindowCompat.getInsetsController(window, root)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    lifecycleScope.launch {
      combine(viewModel.state, viewModel.chromeVisible, pageReady) { state, chrome, shown ->
        chrome || state !is EpubReaderUiState.Ready || !shown
      }.collect { showBars ->
        if (showBars) {
          controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
          controller.hide(WindowInsetsCompat.Type.systemBars())
        }
      }
    }
  }

  @Composable
  private fun Chrome() {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val locator by viewModel.currentLocator.collectAsState()
    val previousPages by viewModel.previousPages.collectAsState()
    val chromeVisible by viewModel.chromeVisible.collectAsState()
    val positions by viewModel.positions.collectAsState()
    val search by viewModel.search.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val selectionLocator by selectionMenuLocator.collectAsState()
    val bookShown by pageReady.collectAsState()
    val shownPageMode by pageMode.collectAsState()
    val shownPagePreviews by pagePreviews.collectAsState()
    val previewsLoading by pagePreviewLoading.collectAsState()
    val overviewPages = positions
    val readingOrder = (state as? EpubReaderUiState.Ready)?.book?.publication?.readingOrder.orEmpty()
    DialogHost(alertDialogShower)
    EpubReaderScreen(
      state = state,
      settings = settings,
      reading = EpubReadingState(
        locator = locator,
        positionCount = positions.size,
        hasPreviousChapter = locator?.let { adjacentChapter(readingOrder, it, next = false) } != null,
        hasNextChapter = locator?.let { adjacentChapter(readingOrder, it, next = true) } != null
      ),
      search = search,
      previousPages = previousPages,
      pageMode = shownPageMode,
      pagePreviews = shownPagePreviews,
      overviewPages = overviewPages,
      pagePreviewLoading = previewsLoading,
      annotations = annotations,
      selectionLocator = selectionLocator,
      chromeVisible = chromeVisible,
      pageReady = bookShown,
      panels = viewModel.panels,
      actions = readerActions(positions, locator)
    )
  }

  private fun readerActions(positions: List<Locator>, locator: Locator?) = EpubReaderActions(
    onBack = { onBackPressedDispatcher.onBackPressed() },
    onTocItem = { navigator?.go(it.link) },
    onPreviousChapter = { goToAdjacentChapter(next = false) },
    onNextChapter = { goToAdjacentChapter(next = true) },
    onSeek = { fraction -> seekTarget(positions, fraction)?.let { navigator?.go(it) } },
    onSettings = viewModel::changeSettings,
    onOpenSearch = {
      viewModel.openSearch()
      viewModel.setChromeVisible(true)
    },
    onSearchQueryChange = viewModel::updateSearchQuery,
    onSearch = viewModel::searchBook,
    onSearchSuggestion = viewModel::selectSearchSuggestion,
    onCloseSearch = viewModel::closeSearch,
    onSearchResult = { result ->
      viewModel.rememberPreviousPage(navigator?.currentLocator?.value ?: locator)
      navigator?.go(result)
      viewModel.closeSearch()
      viewModel.setChromeVisible(false)
    },
    onReturnToPreviousPage = { target ->
      viewModel.rememberPreviousPage(navigator?.currentLocator?.value ?: locator)
      navigator?.go(target)
    },
    onBookmark = viewModel::addBookmark,
    onShowAnnotations = { viewModel.panels.showAnnotations = true },
    onAnnotationSelected = {
      viewModel.rememberPreviousPage(navigator?.currentLocator?.value ?: locator)
      navigator?.go(it.locator)
    },
    onDeleteAnnotation = { viewModel.deleteAnnotation(it.id) },
    onDismissSelectionMenu = ::dismissSelectionMenu,
    onCopySelection = ::copySelection,
    onShareSelection = ::shareSelection,
    onSearchSelection = ::searchSelection,
    onSelectAll = ::selectAll,
    onBookmarkSelection = { viewModel.toggleBookmark(it) },
    onFavoriteSelection = viewModel::addFavorite,
    onHighlightSelection = viewModel::addHighlight,
    onUpdateAnnotationNote = { annotation, note ->
      viewModel.updateAnnotationNote(annotation.id, note)
    },
    onCopyAnnotation = ::copyAnnotation,
    onPageModeSelected = ::selectPageMode,
    onCreateOverviewPage = ::createOverviewPage,
    onDestroyOverviewPage = ::destroyOverviewPage,
    onNoteSelection = viewModel::addNote
  )

  private fun createOverviewPage(container: FragmentContainerView, locator: Locator) {
    if (supportFragmentManager.findFragmentById(container.id) != null) return
    fun install() {
      if (!container.isAttachedToWindow || supportFragmentManager.findFragmentById(container.id) != null) return
      overviewAttachListeners.remove(container)?.let(container::removeOnAttachStateChangeListener)
      installOverviewPageFragment(container, locator)
    }
    if (container.isAttachedToWindow) {
      install()
    } else {
      val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = install()
        override fun onViewDetachedFromWindow(view: View) = Unit
      }
      overviewAttachListeners[container] = listener
      container.addOnAttachStateChangeListener(listener)
    }
  }

  private fun installOverviewPageFragment(container: FragmentContainerView, locator: Locator) {
    val book = (viewModel.state.value as? EpubReaderUiState.Ready)?.book ?: return
    val factory = EpubNavigatorFactory(book.publication).createFragmentFactory(
      initialLocator = locator,
      initialPreferences = viewModel.settings.value.toPreferences(darkTheme.value).copy(
        scroll = false,
        columnCount = ColumnCount.ONE
      ),
      listener = linkListener,
      paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageLoaded() = Unit
      },
      configuration = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false)
    )
    val fragment = factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
    supportFragmentManager.commitNow {
      add(container.id, fragment, "epub-overview-${container.id}")
    }
  }

  private fun destroyOverviewPage(container: FragmentContainerView) {
    overviewAttachListeners.remove(container)?.let(container::removeOnAttachStateChangeListener)
    supportFragmentManager.findFragmentById(container.id)?.let { fragment ->
      supportFragmentManager.commitNow { remove(fragment) }
    }
  }

  private fun attachNavigator(book: OpenEpub) {
    val factory = EpubNavigatorFactory(book.publication).createFragmentFactory(
      initialLocator = viewModel.startLocator(book),
      initialPreferences = viewModel.settings.value.toPreferences(darkTheme.value),
      listener = linkListener,
      paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageLoaded() = revealBook()
      },
      // The container's padding already keeps the book clear of the cutout.
      configuration = EpubNavigatorFragment.Configuration(
        shouldApplyInsetsPadding = false,
        selectionActionModeCallback = selectionActionModeCallback
      )
    )
    supportFragmentManager.fragmentFactory = factory
    supportFragmentManager.commitNow {
      replace(navigatorContainer.id, EpubNavigatorFragment::class.java, null, NAVIGATOR_TAG)
    }
    val fragment = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
      ?: return
    navigator = fragment
    observeHighlightAnnotations(fragment)
    // The loading screen covers the book; a failed load must not leave it there.
    observePageLoading(fragment)
  }

  private fun observeHighlightAnnotations(fragment: EpubNavigatorFragment) {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.annotations.collectLatest { annotations ->
          val highlights = annotations
            .filter { it.kind == EpubAnnotationKind.HIGHLIGHT }
            .map { annotation ->
              org.readium.r2.navigator.Decoration(
                id = annotation.id,
                locator = annotation.locator,
                style = org.readium.r2.navigator.Decoration.Style.Highlight(
                  tint = annotation.color?.tint ?: EpubHighlightColor.LIGHT_YELLOW.tint
                )
              )
            }
          fragment.applyDecorations(highlights, ANNOTATION_DECORATION_GROUP)
        }
      }
    }
  }

  private fun observePageLoading(fragment: EpubNavigatorFragment) {
    lifecycleScope.launch {
      delay(REVEAL_TIMEOUT_MS)
      revealBook()
    }
    fragment.addInputListener(
      object : InputListener {
        override fun onTap(event: TapEvent) = handleTap(fragment, event)
      }
    )
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        fragment.currentLocator.collect { locator ->
          if (!generatingPagePreviews) {
            viewModel.onLocatorChanged(locator)
            if (pageMode.value == EpubPageMode.CAROUSEL) {
              updateCarouselPeeks(pagePreviews.value)
              refreshCarouselPreviews(fragment)
            }
          }
        }
      }
    }
  }

  private fun selectPageMode(mode: EpubPageMode) {
    previewJob?.cancel()
    previewRefreshJob?.cancel()
    previewJob = lifecycleScope.launch {
      pageMode.value = mode
      val carousel = mode == EpubPageMode.CAROUSEL
      setNavigatorSize(carousel)
      if (mode == EpubPageMode.SINGLE_PAGE) {
        pagePreviews.value = emptyList()
        pagePreviewLoading.value = false
        return@launch
      }
      if (mode == EpubPageMode.OVERVIEW) {
        pagePreviewLoading.value = false
        setPageEdgePeeks(false)
        return@launch
      }
      pagePreviewLoading.value = true
      setPageEdgePeeks(false)
      delay(PREVIEW_LAYOUT_SETTLE_MS)
      val fragment = navigator
      val previews = fragment?.let { capturePagePreviews(it, PAGE_PREVIEW_RADIUS) }.orEmpty()
      pagePreviews.value = previews
      if (carousel) updateCarouselPeeks(previews)
      pagePreviewLoading.value = false
    }
  }

  private fun setNavigatorSize(carousel: Boolean) {
    // Keep Readium's WebView laid out for the full display so its EPUB pagination stays stable.
    // Android applies this transform to the rendered View; it does not trigger a smaller reflow.
    navigatorContainer.pivotX = navigatorContainer.width / 2f
    navigatorContainer.pivotY = navigatorContainer.height / 2f
    navigatorContainer.scaleX = if (carousel) CAROUSEL_PAGE_WIDTH else 1f
    navigatorContainer.scaleY = if (carousel) CAROUSEL_PAGE_HEIGHT else 1f
    if (carousel) {
      val peekWidth = (root.width * PAGE_PEEK_WIDTH).toInt()
      val peekHeight = (root.height * CAROUSEL_PAGE_HEIGHT).toInt()
      root.updateViewLayout(
        previousPagePeek,
        FrameLayout.LayoutParams(peekWidth, peekHeight, Gravity.START or Gravity.CENTER_VERTICAL)
      )
      root.updateViewLayout(
        nextPagePeek,
        FrameLayout.LayoutParams(peekWidth, peekHeight, Gravity.END or Gravity.CENTER_VERTICAL)
      )
    }
    navigatorContainer.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    setPageEdgePeeks(carousel)
  }

  private fun setPageEdgePeeks(visible: Boolean) {
    previousPagePeek.visibility = if (visible) View.VISIBLE else View.GONE
    nextPagePeek.visibility = if (visible) View.VISIBLE else View.GONE
  }

  private fun pagePeekView(previous: Boolean) = ImageView(this).apply {
    scaleType = ImageView.ScaleType.MATRIX
    elevation = PAGE_PEEK_ELEVATION
    visibility = View.GONE
    setBackgroundColor(viewModel.settings.value.backgroundColor(darkTheme.value))
    setOnClickListener { selectPeekPage(previous) }
  }

  private fun selectPeekPage(previous: Boolean) {
    val fragment = navigator
    val current = fragment?.currentLocator?.value
    val pageOffset = if (previous) PREVIOUS_PAGE_OFFSET else NEXT_PAGE_OFFSET
    val index = pagePreviews.value.indexOfFirst { it.locator == current }
    val target = pagePreviews.value.getOrNull(index + pageOffset)
    if (fragment != null && current != null && target != null) {
      viewModel.rememberPreviousPage(current)
      fragment.go(target.locator)
    }
  }

  private fun updateCarouselPeeks(previews: List<EpubPagePreview>) {
    val current = navigator?.currentLocator?.value
    val index = previews.indexOfFirst { it.locator == current }
    setPagePeekImage(previousPagePeek, previews.getOrNull(index - 1)?.bitmap, previous = true)
    setPagePeekImage(nextPagePeek, previews.getOrNull(index + 1)?.bitmap, previous = false)
    previousPagePeek.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
    nextPagePeek.visibility = if (index in 0 until previews.lastIndex) View.VISIBLE else View.INVISIBLE
  }

  private fun setPagePeekImage(view: ImageView, bitmap: Bitmap?, previous: Boolean) {
    view.setImageBitmap(bitmap)
    if (bitmap == null) return
    view.post {
      if (view.width == 0 || view.height == 0) return@post
      val scale = view.height.toFloat() / bitmap.height
      val x = if (previous) view.width - bitmap.width * scale else 0f
      view.imageMatrix = Matrix().apply {
        setValues(floatArrayOf(scale, 0f, x, 0f, scale, 0f, 0f, 0f, 1f))
      }
    }
  }

  private fun refreshCarouselPreviews(fragment: EpubNavigatorFragment) {
    if (generatingPagePreviews || pagePreviewLoading.value) return
    val current = fragment.currentLocator.value
    val index = pagePreviews.value.indexOfFirst { it.locator == current }
    if (index !in 0..1 && index < pagePreviews.value.lastIndex - 1) return
    previewRefreshJob?.cancel()
    previewRefreshJob = lifecycleScope.launch {
      delay(PREVIEW_REFRESH_DEBOUNCE_MS)
      if (pageMode.value != EpubPageMode.CAROUSEL || generatingPagePreviews) return@launch
      pagePreviewLoading.value = true
      val previews = capturePagePreviews(fragment, PAGE_PREVIEW_RADIUS)
      if (pageMode.value == EpubPageMode.CAROUSEL) {
        pagePreviews.value = previews
        updateCarouselPeeks(previews)
      }
      pagePreviewLoading.value = false
    }
  }

  private suspend fun capturePagePreviews(
    fragment: EpubNavigatorFragment,
    radius: Int
  ): List<EpubPagePreview> {
    val saved = fragment.currentLocator.value
    val before = mutableListOf<EpubPagePreview>()
    val after = mutableListOf<EpubPagePreview>()
    val current = capturePreview(fragment, saved)
    generatingPagePreviews = true
    return try {
      var atBeginning = false
      repeat(radius) {
        if (!atBeginning) {
          val preview = turnAndCapture(fragment, forward = false)
          if (preview == null) atBeginning = true else before += preview
        }
      }
      repeat(before.size) { turnAndCapture(fragment, forward = true) }
      var atEnd = false
      repeat(radius) {
        if (!atEnd) {
          val preview = turnAndCapture(fragment, forward = true)
          if (preview == null) atEnd = true else after += preview
        }
      }
      before.asReversed() + listOfNotNull(current) + after
    } finally {
      withContext(NonCancellable) {
        restoreLocator(fragment, saved)
        viewModel.onLocatorChanged(fragment.currentLocator.value)
      }
      generatingPagePreviews = false
    }
  }

  private suspend fun turnAndCapture(
    fragment: EpubNavigatorFragment,
    forward: Boolean
  ): EpubPagePreview? {
    val before = fragment.currentLocator.value
    if (forward) fragment.goForward(animated = false) else fragment.goBackward(animated = false)
    val after = withTimeoutOrNull(PAGE_PREVIEW_TURN_TIMEOUT_MS) {
      fragment.currentLocator.first { it != before }
    } ?: return null
    delay(PREVIEW_FRAME_DELAY_MS)
    return capturePreview(fragment, after)
  }

  private suspend fun restoreLocator(fragment: EpubNavigatorFragment, locator: Locator) {
    if (fragment.currentLocator.value != locator) {
      fragment.go(locator)
      withTimeoutOrNull(PAGE_PREVIEW_TURN_TIMEOUT_MS) {
        fragment.currentLocator.first { it == locator }
      }
    }
  }

  private fun capturePreview(fragment: EpubNavigatorFragment, locator: Locator): EpubPagePreview? {
    val view = fragment.publicationView
    if (view.width <= 0 || view.height <= 0) return null
    val width = (view.width * PAGE_PREVIEW_SCALE).toInt().coerceAtLeast(1)
    val height = (view.height * PAGE_PREVIEW_SCALE).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
      scale(PAGE_PREVIEW_SCALE, PAGE_PREVIEW_SCALE)
      view.draw(this)
    }
    return EpubPagePreview(locator, bitmap)
  }

  /** Names the Recents entry after the book. */
  private fun setTaskTitle(title: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(title).build())
    } else {
      @Suppress("DEPRECATION")
      setTaskDescription(ActivityManager.TaskDescription(title))
    }
  }

  private fun revealBook() {
    pageReady.value = true
  }

  private val selectionActionModeCallback = object : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
      selectionActionMode = mode
      selectionMenuOpening = false
      menu.clear()
      return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: android.view.Menu) = false

    override fun onActionItemClicked(mode: ActionMode, item: android.view.MenuItem) = false

    override fun onDestroyActionMode(mode: ActionMode) {
      if (selectionActionMode === mode) selectionActionMode = null
    }
  }

  private fun dismissSelectionMenu() {
    selectionMenuLocator.value = null
    navigator?.clearSelection()
  }

  private fun copySelection(text: String) {
    if (text.isNotBlank()) {
      val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.epub_selected_text), text))
    }
    dismissSelectionMenu()
  }

  private fun copyAnnotation(annotation: EpubAnnotation) {
    val text = buildString {
      if (annotation.title.isNotBlank()) append(annotation.title).append('\n')
      append(annotation.text)
      annotation.note?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
    }
    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.epub_annotations), text))
  }

  private fun shareSelection(text: String) {
    dismissSelectionMenu()
    if (text.isBlank()) return
    startActivity(
      Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
        getString(R.string.share)
      )
    )
  }

  private fun searchSelection(text: String) {
    dismissSelectionMenu()
    if (text.isBlank()) return
    startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text))
  }

  private fun selectAll() {
    dismissSelectionMenu()
    lifecycleScope.launch {
      navigator?.evaluateJavascript("window.getSelection()?.selectAllChildren(document.body)")
    }
  }

  /** Links never reach here; Readium follows them itself. Scrolling books have no edge zones. */
  private fun handleTap(fragment: EpubNavigatorFragment, event: TapEvent): Boolean {
    // Taps reach the book through the loading screen, which must not act on them.
    if (!pageReady.value) return true
    val overflow = fragment.overflow.value
    val zone = if (overflow.scroll) {
      TapZone.CENTER
    } else {
      EpubTapZones.classify(
        event.point.x,
        fragment.publicationView.width.toFloat(),
        overflow.readingProgression == ReadingProgression.RTL
      )
    }
    val overlayVisible = viewModel.chromeVisible.value
    val action = EpubTapZones.actionFor(zone, overlayVisible)
    if (EpubTapZones.dismissesOverlay(action, overlayVisible)) viewModel.setChromeVisible(false)
    when (action) {
      TapAction.PREVIOUS_PAGE -> fragment.goBackward(animated = true)
      TapAction.NEXT_PAGE -> fragment.goForward(animated = true)
      TapAction.SHOW_OVERLAY -> viewModel.setChromeVisible(true)
      TapAction.HIDE_OVERLAY -> Unit
    }
    return true
  }

  private fun goToAdjacentChapter(next: Boolean) {
    val book = (viewModel.state.value as? EpubReaderUiState.Ready)?.book ?: return
    val current = navigator?.currentLocator?.value ?: return
    adjacentChapter(book.publication.readingOrder, current, next)?.let { navigator?.go(it) }
  }

  private val linkListener = object : EpubNavigatorFragment.Listener {
    override fun onExternalLinkActivated(url: AbsoluteUrl) {
      val intent = EpubExternalLinks.intentFor(url.toString()) ?: return
      lifecycleScope.launch { externalLinkOpener.openExternalUrl(intent) }
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    darkTheme.value = isNightMode(newConfig)
  }

  override fun onStop() {
    viewModel.persistPosition()
    super.onStop()
  }

  private fun isNightMode(configuration: Configuration) =
    configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

  companion object {
    private const val NAVIGATOR_TAG = "epubNavigator"
    private const val BOOK_SCHEME = "epub"
    private const val REVEAL_TIMEOUT_MS = 5_000L

    // Constant top and bottom margin around the page, so the overlay toggling never reflows it.
    private const val READING_MARGIN_DP = 32
    private const val ANNOTATION_DECORATION_GROUP = "kiwix-annotations"
    private const val SELECTION_READY_RETRIES = 5
    private const val SELECTION_READY_RETRY_MS = 100L
    private const val PAGE_PREVIEW_SCALE = 0.4f
    private const val PAGE_PREVIEW_TURN_TIMEOUT_MS = 2_000L
    private const val PREVIEW_FRAME_DELAY_MS = 80L
    private const val PREVIEW_LAYOUT_SETTLE_MS = 150L
    private const val PAGE_PREVIEW_RADIUS = 4
    private const val CAROUSEL_PAGE_WIDTH = 0.9f
    private const val CAROUSEL_PAGE_HEIGHT = 0.96f
    private const val PAGE_PEEK_WIDTH = 0.1f
    private const val PREVIEW_REFRESH_DEBOUNCE_MS = 250L
    private const val PREVIOUS_PAGE_OFFSET = -1
    private const val NEXT_PAGE_OFFSET = 1
    private const val PAGE_PEEK_ELEVATION = 8f

    /**
     * The main activity is singleInstance, so the reader can't share its task. Each book is its
     * own document task (see the manifest): its own Recents entry, and opening the same book
     * again, e.g. after the launcher icon returned to the main task, resumes that task. The data
     * URI is only what tells books apart.
     */
    fun intent(context: Context, file: File) =
      Intent(context, EpubReaderActivity::class.java)
        .setData(Uri.fromParts(BOOK_SCHEME, file.path, null))
        .putExtra(EpubReaderViewModel.EXTRA_PATH, file.path)
  }
}
