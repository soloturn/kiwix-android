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
import android.app.Presentation
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.base.BaseActivity
import org.kiwix.kiwixmobile.core.utils.ExternalLinkOpener
import org.kiwix.kiwixmobile.core.utils.dialog.AlertDialogShower
import org.kiwix.kiwixmobile.core.utils.dialog.DialogHost
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File
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

  internal val viewModel: EpubReaderViewModel by viewModels()
  internal val darkTheme = MutableStateFlow(false)
  internal val pageReady = MutableStateFlow(false)
  internal val pageModesAvailable = MutableStateFlow(false)
  private val selectionMenuLocator = MutableStateFlow<Locator?>(null)
  internal val pageMode = MutableStateFlow(EpubPageMode.SINGLE_PAGE)
  internal val pagePreviews = MutableStateFlow<List<EpubPagePreview>>(emptyList())
  internal val overviewPages = MutableStateFlow<List<EpubPagePreview>>(emptyList())
  internal val previewCache by lazy { EpubPreviewCache(cacheDir) }
  internal val overviewState = OverviewState()
  internal val overviewViewport = MutableStateFlow(OverviewViewport())
  internal val overviewCaptured = MutableSharedFlow<String>(extraBufferCapacity = OVERVIEW_CAPTURE_BUFFER)
  internal val visiblePageKey = MutableStateFlow<PageKey?>(null)
  internal val previewPageKey = MutableStateFlow<PageKey?>(null)
  internal val resourcePageCounts = HashMap<String, Int>()
  internal var promotedAtMs = 0L
  internal data class PageKey(val resourceHref: String, val pageIndex: Int, val totalPages: Int)
  internal val previewMutex = Mutex()
  private var selectionActionMode: ActionMode? = null
  private var selectionMenuOpening = false
  internal val carouselDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
  internal val carouselTapDetector = DoubleTapDetector(carouselDoubleTapTimeout)
  internal var carouselTapJob: Job? = null
  internal var navigator: EpubNavigatorFragment? = null
  internal var activeNavigatorPositionJob: Job? = null
  internal var previewNavigator: EpubNavigatorFragment? = null
  internal var previousCarouselNavigator: EpubNavigatorFragment? = null
  internal var nextCarouselNavigator: EpubNavigatorFragment? = null
  internal var previewCapture: VulkanPreviewCapture? = null
  internal var previewCaptureInitializing = false
  internal var previewPresentation: Presentation? = null
  internal var previewJob: Job? = null
  internal var previewRefreshJob: Job? = null
  internal var overviewWorkerJob: Job? = null
  internal var previewInvalidationJob: Job? = null
  internal var previewGeneration = 0L
  private var previewViewport = 0 to 0
  internal lateinit var root: FrameLayout
  internal var previewNavigatorContainer: FragmentContainerView? = null
  internal lateinit var navigatorContainer: FragmentContainerView
  internal lateinit var previousCarouselContainer: FragmentContainerView
  internal lateinit var nextCarouselContainer: FragmentContainerView
  internal lateinit var previousPagePeek: ImageView
  internal lateinit var nextPagePeek: ImageView

  override fun onCreate(savedInstanceState: Bundle?) {
    // The navigator fragment needs a factory built from the opened book, so it can't be restored;
    // the position and open panels live in the view model instead.
    super.onCreate(null)
    externalLinkOpener.initialize(this, alertDialogShower)
    darkTheme.value = isNightMode(resources.configuration)
    setupReaderViews()
    setUpImmersiveMode()
    lifecycleScope.launch {
      val ready = viewModel.state.filterIsInstance<EpubReaderUiState.Ready>().first()
      attachNavigator(ready.book)
      setTaskTitle(ready.book.title)
    }
    lifecycleScope.launch {
      var previousRenderSettings: Pair<EpubReaderSettings, Boolean>? = null
      combine(viewModel.settings, darkTheme, pageMode) { settings, dark, mode ->
        Triple(settings, dark, mode)
      }.collect { (settings, dark, mode) ->
        val renderSettings = settings to dark
        if (
          navigator != null &&
          previousRenderSettings != null &&
          previousRenderSettings != renderSettings
        ) {
          invalidatePreviewCache()
        }
        previousRenderSettings = renderSettings
        root.setBackgroundColor(settings.backgroundColor(dark))
        val preferences = navigatorPreferences(settings, dark, mode)
        navigator?.submitPreferences(preferences)
        previewNavigator?.submitPreferences(preferences)
        previousCarouselNavigator?.submitPreferences(preferences)
        nextCarouselNavigator?.submitPreferences(preferences)
      }
    }
  }

  private fun setupReaderViews() {
    navigatorContainer = FragmentContainerView(this).apply { id = View.generateViewId() }
    previousCarouselContainer = FragmentContainerView(this).apply {
      id = View.generateViewId()
      visibility = View.GONE
      isClickable = false
    }
    nextCarouselContainer = FragmentContainerView(this).apply {
      id = View.generateViewId()
      visibility = View.GONE
      isClickable = false
    }
    previousPagePeek = pagePeekView(previous = true)
    nextPagePeek = pagePeekView(previous = false)
    applyReaderInsetsToCarouselPages()
    root = FrameLayout(this).apply {
      addView(navigatorContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
      addView(previousCarouselContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
      addView(nextCarouselContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
      // Place each live Readium page over the full reader area; carousel transforms size them.
      addView(previousPagePeek)
      addView(nextPagePeek)
      addView(
        ComposeView(context).apply { setContent { Chrome() } },
        FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
      )
    }
    setContentView(root)
    root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
      val size = (right - left) to (bottom - top)
      if (shouldInitializePreviewCapture(size)) {
        initializePreviewCapture(size.first, size.second)
      }
      if (previewViewport.first > 0 && previewViewport != size && navigator != null) {
        invalidatePreviewCache()
      }
      previewViewport = size
    }
  }

  private fun shouldInitializePreviewCapture(size: Pair<Int, Int>) =
    size.first > 0 && size.second > 0 && previewCapture == null && !previewCaptureInitializing

  private fun initializePreviewCapture(width: Int, height: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
      previewCapture != null ||
      previewCaptureInitializing
    ) {
      return
    }
    previewCaptureInitializing = true
    lifecycleScope.launch {
      val capture = withContext(Dispatchers.IO) {
        runCatching { VulkanPreviewCapture(this@EpubReaderActivity, width, height) }.getOrNull()
      }
      previewCaptureInitializing = false
      if (isFinishing || isDestroyed) {
        capture?.close()
        return@launch
      }
      if (capture == null || !capture.isAvailable || previewCapture != null) {
        capture?.close()
        pageModesAvailable.value = false
        return@launch
      }
      previewCapture = capture
      if (!showPreviewProbe(capture)) {
        disablePreviewModes(capture)
        return@launch
      }
      lifecycleScope.launch(Dispatchers.IO) {
        val frame = capture.frameSequence()
        val probe = capture.captureAfter(
          frame,
          PREVIEW_PROBE_BITMAP_SIZE,
          PREVIEW_PROBE_BITMAP_SIZE,
          PREVIEW_FRAME_TIMEOUT_MS
        )
        withContext(Dispatchers.Main) {
          val canUsePreviewCapture =
            probe != null &&
              previewCapture === capture &&
              !isFinishing &&
              !isDestroyed
          probe?.recycle()
          if (canUsePreviewCapture) {
            pageModesAvailable.value = true
          } else {
            disablePreviewModes(capture)
          }
        }
      }
    }
  }

  private fun showPreviewProbe(
    capture: VulkanPreviewCapture
  ): Boolean = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
    false
  } else {
    val presentation = capture.display
      ?.let { display -> runCatching { Presentation(this, display) }.getOrNull() }
    if (presentation != null) {
      presentation.setContentView(
        FrameLayout(presentation.context).apply {
          setBackgroundColor(viewModel.settings.value.backgroundColor(darkTheme.value))
        },
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
      )
    }
    val shown = presentation?.let { runCatching { it.show() }.isSuccess } == true
    if (shown) {
      previewPresentation = presentation
    } else {
      presentation?.dismiss()
    }
    shown
  }

  internal fun disablePreviewModes(
    capture: VulkanPreviewCapture,
    reason: String = "preview capture unavailable"
  ) {
    if (previewCapture !== capture) return
    cancelCarouselTap()
    Log.e(PREVIEW_LOG_TAG, "Disabling page previews: $reason")
    previewGeneration++
    previewJob?.cancel()
    previewRefreshJob?.cancel()
    overviewWorkerJob?.cancel()
    previewInvalidationJob?.cancel()
    previewNavigator?.let { fragment ->
      supportFragmentManager.commitNow { remove(fragment) }
    }
    previewNavigator = null
    previewNavigatorContainer = null
    previousCarouselNavigator = null
    nextCarouselNavigator = null
    if (::previousCarouselContainer.isInitialized) previousCarouselContainer.visibility = View.GONE
    if (::nextCarouselContainer.isInitialized) nextCarouselContainer.visibility = View.GONE
    previewPresentation?.dismiss()
    previewPresentation = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) capture.close()
    previewCapture = null
    previewCaptureInitializing = false
    pageModesAvailable.value = false
    pageMode.value = EpubPageMode.SINGLE_PAGE
    previewPageKey.value = null
    if (::navigatorContainer.isInitialized) setNavigatorSize(carousel = false)
    pagePreviews.value = emptyList()
    overviewPages.value = emptyList()
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    val handled = super.dispatchTouchEvent(event)
    if (event.actionMasked == MotionEvent.ACTION_UP) openSelectionMenuAfterRelease()
    return handled
  }

  override fun onDestroy() {
    cancelCarouselTap()
    previewGeneration++
    previewJob?.cancel()
    previewRefreshJob?.cancel()
    previewInvalidationJob?.cancel()
    activeNavigatorPositionJob?.cancel()
    navigator = null
    supportFragmentManager.findFragmentByTag(PREVIEW_NAVIGATOR_TAG)?.let { fragment ->
      supportFragmentManager.beginTransaction()
        .remove(fragment)
        .commitNowAllowingStateLoss()
    }
    supportFragmentManager.findFragmentByTag(LIVE_PREVIOUS_TAG)?.let { fragment ->
      supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }
    supportFragmentManager.findFragmentByTag(LIVE_NEXT_TAG)?.let { fragment ->
      supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }
    previousCarouselNavigator = null
    nextCarouselNavigator = null
    previewNavigator = null
    previewNavigatorContainer = null
    previewPresentation?.dismiss()
    previewPresentation = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) previewCapture?.close()
    previewCapture = null
    pagePreviews.value = emptyList()
    if (::previousPagePeek.isInitialized) previousPagePeek.setImageDrawable(null)
    if (::nextPagePeek.isInitialized) nextPagePeek.setImageDrawable(null)
    super.onDestroy()
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
    DialogHost(alertDialogShower)
    ReaderScreenHost()
  }

  /** Wires the screen to the view model; the swipe-rate carousel pages are left to [CarouselHost]. */
  @Composable
  private fun ReaderScreenHost() {
    val state by viewModel.state.collectAsState()
    val reading = collectReadingState(state)
    val positions by viewModel.positions.collectAsState()
    EpubReaderScreen(
      state = state,
      settings = viewModel.settings.collectAsState().value,
      reading = reading,
      search = viewModel.search.collectAsState().value,
      previousPages = viewModel.previousPages.collectAsState().value,
      pageMode = pageMode.collectAsState().value,
      pageModesAvailable = pageModesAvailable.collectAsState().value,
      overviewPages = overviewPages.collectAsState().value,
      loadPreviewBitmap = { loadPreviewBitmap(it) },
      peekPreviewBitmap = { previewCache.peek(it.cacheKey) },
      annotations = viewModel.annotations.collectAsState().value,
      selectionLocator = selectionMenuLocator.collectAsState().value,
      chromeVisible = viewModel.chromeVisible.collectAsState().value,
      pageReady = pageReady.collectAsState().value,
      panels = viewModel.panels,
      actions = readerActions(positions, reading.locator),
      content = remember<@Composable () -> Unit> { { CarouselHost() } }
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
    onToggleBookmark = viewModel::toggleBookmark,
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
    onHighlightSelection = viewModel::addHighlight,
    onUpdateAnnotationNote = { annotation, note ->
      viewModel.updateAnnotationNote(annotation.id, note)
    },
    onCopyAnnotation = ::copyAnnotation,
    onPageModeSelected = ::selectPageMode,
    onOverviewViewportChanged = ::onOverviewViewportChanged,
    onSelectOverviewPage = { target ->
      viewModel.rememberPreviousPage(navigator?.currentLocator?.value ?: locator)
      navigator?.go(target)
      viewModel.setChromeVisible(true)
    },
    onSelectCarouselPage = ::selectCarouselPage,
    onCarouselPositionChanged = ::onCarouselPositionChanged,
    onCarouselTap = ::handleCarouselTap,
    onNoteSelection = viewModel::addNote
  )

  private fun attachNavigator(book: OpenEpub) {
    val initialLocator = viewModel.startLocator(book)
    val initialPreferences = navigatorPreferences(
      viewModel.settings.value,
      darkTheme.value,
      EpubPageMode.SINGLE_PAGE
    )
    lateinit var mainFragment: EpubNavigatorFragment
    val mainFactory = EpubNavigatorFactory(book.publication).createFragmentFactory(
      initialLocator = initialLocator,
      initialPreferences = initialPreferences,
      listener = linkListener,
      paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageLoaded() {
          pageReady.value = true
        }

        override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
          if (navigator === mainFragment) visiblePageKey.value = pageKey(locator, pageIndex, totalPages)
        }
      },
      // The container's padding already keeps the book clear of the cutout.
      configuration = EpubNavigatorFragment.Configuration(
        shouldApplyInsetsPadding = false,
        selectionActionModeCallback = selectionActionModeCallback
      )
    )
    supportFragmentManager.fragmentFactory = mainFactory
    mainFragment = mainFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
      as EpubNavigatorFragment
    supportFragmentManager.commitNow {
      add(navigatorContainer.id, mainFragment, NAVIGATOR_TAG)
    }
    setActiveNavigator(mainFragment)
    observeHighlightAnnotations(mainFragment, invalidateCacheOnChange = true)
    // The loading screen covers the book; a failed load must not leave it there.
    observePageLoading(mainFragment)
    lifecycleScope.launch {
      visiblePageKey
        .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
        .collect {
          if (pageMode.value == EpubPageMode.CAROUSEL) {
            val windowReady = it != null && ensureCarouselWindow(it)
            syncLiveCarouselNeighbors(pagePreviews.value, it)
            if (!windowReady) refreshCarouselPreviews()
          }
        }
    }
  }

  internal fun observeHighlightAnnotations(
    fragment: EpubNavigatorFragment,
    invalidateCacheOnChange: Boolean = false
  ) {
    lifecycleScope.launch {
      var previousHighlights: List<org.readium.r2.navigator.Decoration>? = null
      combine(viewModel.annotations, viewModel.settings, darkTheme) { annotations, settings, nightMode ->
        val darkReadingTheme = settings.effectivePreset(nightMode) in setOf(
          EpubThemePreset.DARK,
          EpubThemePreset.BLACK
        )
        annotations
          .filter { it.kind == EpubAnnotationKind.HIGHLIGHT }
          .map { annotation ->
            org.readium.r2.navigator.Decoration(
              id = annotation.id,
              locator = annotation.locator,
              style = org.readium.r2.navigator.Decoration.Style.Highlight(
                tint = annotation.color?.tintFor(darkReadingTheme)
                  ?: EpubHighlightColor.LIGHT_YELLOW.tintFor(darkReadingTheme)
              )
            )
          }
      }
        .distinctUntilChanged()
        .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
        .collectLatest { highlights ->
          if (
            invalidateCacheOnChange &&
            previousHighlights != null &&
            previousHighlights != highlights
          ) {
            invalidatePreviewCache()
          }
          previousHighlights = highlights
          fragment.applyDecorations(highlights, ANNOTATION_DECORATION_GROUP)
        }
    }
  }

  internal fun ensurePreviewNavigator(initialLocator: Locator): EpubNavigatorFragment? =
    previewNavigator ?: ensurePreviewPresentation()?.let { container ->
      (viewModel.state.value as? EpubReaderUiState.Ready)?.book?.let { book ->
        createPreviewNavigator(container, book, initialLocator)
      }
    }

  private fun createPreviewNavigator(
    container: FragmentContainerView,
    book: OpenEpub,
    initialLocator: Locator
  ): EpubNavigatorFragment {
    val factory = EpubNavigatorFactory(book.publication).createFragmentFactory(
      initialLocator = initialLocator,
      initialPreferences = navigatorPreferences(
        viewModel.settings.value,
        darkTheme.value,
        pageMode.value
      ),
      listener = linkListener,
      paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageLoaded() = Unit

        override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
          previewPageKey.value = pageKey(locator, pageIndex, totalPages)
        }
      },
      configuration = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false)
    )
    val fragment = factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
      as EpubNavigatorFragment
    previewNavigator = fragment
    attachPreviewNavigator(container, fragment)
    observeHighlightAnnotations(fragment)
    return fragment
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

  internal val selectionActionModeCallback = object : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
      selectionActionMode = mode
      selectionMenuOpening = false
      menu.clear()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        mode.hide(SELECTION_ACTION_MODE_HIDE_MS)
      }
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
  private fun goToAdjacentChapter(next: Boolean) {
    val book = (viewModel.state.value as? EpubReaderUiState.Ready)?.book ?: return
    val current = navigator?.currentLocator?.value ?: return
    adjacentChapter(book.publication.readingOrder, current, next)?.let { navigator?.go(it) }
  }

  internal val linkListener = object : EpubNavigatorFragment.Listener {
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
    cancelCarouselTap()
    previewGeneration++
    previewJob?.cancel()
    previewRefreshJob?.cancel()
    previewInvalidationJob?.cancel()
    viewModel.persistPosition()
    super.onStop()
  }

  override fun onStart() {
    super.onStart()
    if (navigator != null && pageMode.value != EpubPageMode.SINGLE_PAGE) {
      selectPageMode(pageMode.value)
    }
  }

  private fun isNightMode(configuration: Configuration) =
    configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

  companion object {
    private const val NAVIGATOR_TAG = "epubNavigator"
    internal const val LIVE_PREVIOUS_TAG = "epubLiveCarouselPrevious"
    internal const val LIVE_NEXT_TAG = "epubLiveCarouselNext"
    internal const val PREVIEW_NAVIGATOR_TAG = "epubPreviewNavigator"
    internal const val PREVIEW_LOG_TAG = "EpubPreview"
    private const val BOOK_SCHEME = "epub"

    // Constant top and bottom margin around the page, so the overlay toggling never reflows it.
    internal const val READING_MARGIN_DP = 32
    private const val ANNOTATION_DECORATION_GROUP = "kiwix-annotations"
    private const val SELECTION_READY_RETRIES = 5
    private const val SELECTION_READY_RETRY_MS = 100L
    private const val SELECTION_ACTION_MODE_HIDE_MS = 600_000L
    internal const val PAGE_PREVIEW_SCALE = 0.3f
    internal const val PAGE_PREVIEW_INITIAL_READY_TIMEOUT_MS = 15_000L
    internal const val PAGE_PREVIEW_TURN_TIMEOUT_MS = 2_000L
    internal const val PREVIEW_FRAME_TIMEOUT_MS = 2_000L
    private const val PREVIEW_PROBE_BITMAP_SIZE = 8
    internal const val PAGE_CAROUSEL_RADIUS = 1
    internal const val CAROUSEL_PAGE_WIDTH = 0.82f
    internal const val CAROUSEL_PAGE_HEIGHT = 0.76f
    internal const val LIVE_CAROUSEL_PAGE_WIDTH = 0.77f
    internal const val LIVE_CAROUSEL_PAGE_STEP_INSET_DP = 80
    internal const val CAROUSEL_PEEK_HEIGHT = 0.72f
    internal const val CAROUSEL_PEEK_SCALE = 0.72f
    internal const val PAGE_PEEK_WIDTH = 0.1f
    private const val OVERVIEW_CAPTURE_BUFFER = 64
    internal const val OVERVIEW_SETTLE_TIMEOUT_MS = 400L
    internal const val PREVIEW_REFRESH_DEBOUNCE_MS = 250L
    internal const val PREVIEW_INVALIDATION_DEBOUNCE_MS = 150L
    internal const val PREVIOUS_PAGE_OFFSET = -1
    internal const val NEXT_PAGE_OFFSET = 1
    internal const val PAGE_EDGE_PROGRESS = 0.001

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
