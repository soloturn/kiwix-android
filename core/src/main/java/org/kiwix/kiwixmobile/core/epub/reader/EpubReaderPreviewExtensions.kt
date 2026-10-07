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
 */

@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package org.kiwix.kiwixmobile.core.epub.reader

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.Display
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewTreeObserver
import android.view.Window
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import kotlin.coroutines.resume

private typealias PageKey = EpubReaderActivity.PageKey
private const val PAGE_LOADING_REVEAL_TIMEOUT_MS = 5_000L
private const val MAX_CAROUSEL_WINDOW_PAGES = 7

internal fun EpubReaderActivity.navigatorPreferences(
  settings: EpubReaderSettings,
  dark: Boolean,
  mode: EpubPageMode
) = settings.toPreferences(dark).let {
  when (mode) {
    EpubPageMode.SINGLE_PAGE -> it.copy(columnCount = ColumnCount.ONE)
    EpubPageMode.CAROUSEL, EpubPageMode.OVERVIEW ->
      it.copy(scroll = false, columnCount = ColumnCount.ONE)
  }
}

internal fun EpubReaderActivity.observePageLoading(fragment: EpubNavigatorFragment) {
  lifecycleScope.launch {
    delay(PAGE_LOADING_REVEAL_TIMEOUT_MS)
    pageReady.value = true
  }
  fragment.addInputListener(
    object : InputListener {
      override fun onTap(event: TapEvent) = handleTap(fragment, event)
    }
  )
}

internal fun EpubReaderActivity.selectPageMode(mode: EpubPageMode) {
  if (mode != EpubPageMode.SINGLE_PAGE && !pageModesAvailable.value) return
  cancelCarouselTap()
  pageMode.value = mode
  setNavigatorSize(mode == EpubPageMode.CAROUSEL)
  if (mode == EpubPageMode.SINGLE_PAGE) {
    previewGeneration++
    cancelPreviewJobs()
    return
  }
  val generation = ++previewGeneration
  cancelPreviewJobs()
  if (mode == EpubPageMode.OVERVIEW) {
    startOverview(generation)
  } else {
    previewJob = lifecycleScope.launch {
      generatePreviews(generation)
    }
  }
}

internal fun EpubReaderActivity.cancelPreviewJobs() {
  previewJob?.cancel()
  previewRefreshJob?.cancel()
  overviewWorkerJob?.cancel()
}

private suspend fun EpubReaderActivity.generatePreviews(generation: Long) {
  previewMutex.withLock {
    val fragment = navigator
    if (generation == previewGeneration && fragment != null) {
      val current = fragment.currentLocator.value
      val key = visiblePageKey.value ?: withTimeoutOrNull(
        EpubReaderActivity.PAGE_PREVIEW_INITIAL_READY_TIMEOUT_MS
      ) {
        visiblePageKey.first { it != null }
      }
      publishInitialCarouselPage(key, current)
      val previewFragment = key?.let { ensurePreviewNavigator(current) }
      if (key != null && previewFragment != null) {
        prepareCarouselWindow(generation, current, key, previewFragment)
      } else if (generation == previewGeneration) {
        Log.e(EpubReaderActivity.PREVIEW_LOG_TAG, "Timed out waiting for the EPUB page to become ready")
      }
    }
  }
}

private fun EpubReaderActivity.publishInitialCarouselPage(key: PageKey?, locator: Locator) {
  if (key != null) {
    publishCarouselPreviews(
      listOf(
        EpubPagePreview(
          locator,
          cacheKey = liveCarouselPageKey(key),
          pageIndex = key.pageIndex,
          resourceHref = key.resourceHref,
          totalPages = key.totalPages,
          chapterTitle = viewModel.chapterTitleFor(locator)
        )
      )
    )
  }
}

private suspend fun EpubReaderActivity.prepareCarouselWindow(
  generation: Long,
  current: Locator,
  key: PageKey,
  fragment: EpubNavigatorFragment
) {
  val currentPage = pagePreviews.value.firstOrNull { it.matches(key) }
  if (currentPage != null) {
    val positioned = navigatePreviewTo(fragment, current, key, generation)
    if (positioned && generation == previewGeneration) {
      completeCarouselWindow(fragment, current, key, currentPage, generation)
    }
  }
}

private suspend fun EpubReaderActivity.completeCarouselWindow(
  fragment: EpubNavigatorFragment,
  current: Locator,
  key: PageKey,
  currentPage: EpubPagePreview,
  generation: Long
) {
  val previous = discoverNeighborPages(
    fragment,
    EpubReaderActivity.PAGE_CAROUSEL_RADIUS,
    generation,
    forward = false
  )
  val restored = generation == previewGeneration &&
    navigatePreviewTo(fragment, current, key, generation)
  if (restored) {
    val next = discoverNeighborPages(
      fragment,
      EpubReaderActivity.PAGE_CAROUSEL_RADIUS,
      generation,
      forward = true
    )
    if (generation == previewGeneration) {
      publishPreviewWindow(previous.asReversed() + currentPage + next)
    }
  }
}

private fun EpubReaderActivity.publishCarouselPreviews(previews: List<EpubPagePreview>) {
  pagePreviews.value = previews
  updateCarouselPeeks(previews)
}

private fun EpubReaderActivity.publishPreviewWindow(previews: List<EpubPagePreview>) {
  publishCarouselPreviews(previews)
  refreshCarouselPreviews()
}

internal fun EpubReaderActivity.setNavigatorSize(carousel: Boolean) {
  // Keep Readium's WebView laid out for the full display so its EPUB pagination stays stable.
  // Android applies this transform to the rendered View; it does not trigger a smaller reflow.
  if (carousel) {
    scaleCarouselPage(navigatorContainer)
  } else {
    navigatorContainer.scaleX = 1f
    navigatorContainer.scaleY = 1f
  }
  navigatorContainer.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
  setPageEdgePeeks(false)
  setLiveCarouselMode(carousel)
}

internal fun EpubReaderActivity.applyReaderInsetsToCarouselPages() {
  val margin = (EpubReaderActivity.READING_MARGIN_DP * resources.displayMetrics.density).toInt()
  listOf(navigatorContainer, previousCarouselContainer, nextCarouselContainer).forEach { container ->
    ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
      // Keep every live page on the same content viewport so neighboring text isn't larger.
      applyReadingInsets(view, insets, margin)
    }
  }
}

internal fun EpubReaderActivity.setActiveNavigator(fragment: EpubNavigatorFragment) {
  navigator = fragment
  activeNavigatorPositionJob?.cancel()
  activeNavigatorPositionJob = lifecycleScope.launch {
    fragment.currentLocator
      .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
      .collect(viewModel::onLocatorChanged)
  }
}

internal fun EpubReaderActivity.selectCarouselPage(target: Locator) {
  val current = navigator ?: return
  val pages = pagePreviews.value
  val currentIndex = pages.indexOfFirst { it.matches(visiblePageKey.value) }
  // Compare page identity, not bookmark position: neighbors can share a position or progression.
  val targetIndex = pages.indexOfFirst { it.locator == target }
    .takeIf { it >= 0 } ?: pages.indexOfFirst { it.locator.sameBookmarkPosition(target) }
  if (targetIndex >= 0 && targetIndex == currentIndex) return
  viewModel.rememberPreviousPage(current.currentLocator.value)
  val forward = when (targetIndex - currentIndex) {
    1 -> true
    -1 -> false
    else -> null
  }
  // The neighbor was sent to its page when the window was published; don't wait for it to report.
  val neighbor = when (forward) {
    true -> nextCarouselNavigator
    false -> previousCarouselNavigator
    null -> null
  }
  if (forward != null && neighbor != null) {
    promoteCarouselNeighbor(forward, pages[targetIndex])
  } else if (!current.currentLocator.value.sameBookmarkPosition(target)) {
    current.go(target)
  }
}

private fun EpubReaderActivity.promoteCarouselNeighbor(
  forward: Boolean,
  page: EpubPagePreview
) {
  val currentFragment = navigator ?: return
  val previousFragment = previousCarouselNavigator
  val nextFragment = nextCarouselNavigator
  val currentContainer = navigatorContainer
  val previousContainer = previousCarouselContainer
  val nextContainer = nextCarouselContainer
  val promoted = (if (forward) nextFragment else previousFragment) ?: return

  if (forward) {
    navigatorContainer = nextContainer
    previousCarouselContainer = currentContainer
    nextCarouselContainer = previousContainer
    previousCarouselNavigator = currentFragment
    nextCarouselNavigator = previousFragment
  } else {
    navigatorContainer = previousContainer
    previousCarouselContainer = nextContainer
    nextCarouselContainer = currentContainer
    previousCarouselNavigator = nextFragment
    nextCarouselNavigator = currentFragment
  }

  setActiveNavigator(promoted)
  val key = PageKey(page.resourceHref, page.pageIndex, page.totalPages)
  visiblePageKey.value = key
  // Publish the new neighbors now so the recycled navigator starts loading during the snap.
  // The pager reports its own position next, so views aren't moved here.
  val windowReady = try {
    Trace.beginSection("epub.promote")
    ensureCarouselWindow(key).also {
      syncLiveCarouselNeighbors(pagePreviews.value, key, moveViews = false)
    }
  } finally {
    Trace.endSection()
  }
  promotedAtMs = SystemClock.elapsedRealtime()
  if (!windowReady) Log.d(EpubReaderActivity.PREVIEW_LOG_TAG, "wall: no arithmetic neighbor for $key")
}

/** Logs how long the recycled neighbor took to show its page after a promotion. */
private fun EpubReaderActivity.logNeighborReady() {
  val startedAt = promotedAtMs
  if (startedAt > 0L) {
    promotedAtMs = 0L
    Log.d(
      EpubReaderActivity.PREVIEW_LOG_TAG,
      "neighborReady ms=${SystemClock.elapsedRealtime() - startedAt}"
    )
  }
}

/**
 * The page next to [key], found by arithmetic on the page index, or null when it can't be known
 * yet (page count of an unvisited chapter, right-to-left books) and the hidden navigator must find it.
 */
internal fun EpubReaderActivity.neighborPage(key: PageKey, forward: Boolean): EpubPagePreview? {
  val book = (viewModel.state.value as? EpubReaderUiState.Ready)?.book?.takeUnless { it.rtl }
  val order = book?.publication?.readingOrder.orEmpty()
  val index = order.indexOfFirst { it.href.toString().substringBefore('#') == key.resourceHref }
  val target = if (index >= 0 && key.totalPages > 0) neighborTarget(order, index, key, forward) else null
  return if (book == null || target == null) null else neighborPreview(book.publication, target)
}

private class NeighborTarget(val link: Link, val href: String, val pageIndex: Int, val total: Int)

private fun EpubReaderActivity.neighborTarget(
  order: List<Link>,
  index: Int,
  key: PageKey,
  forward: Boolean
): NeighborTarget? {
  val step = if (forward) 1 else -1
  val inChapter = key.pageIndex + step in 0 until key.totalPages
  val link = if (inChapter) order[index] else order.getOrNull(index + step)
  val href = link?.href?.toString()?.substringBefore('#').orEmpty()
  val total = if (inChapter) key.totalPages else resourcePageCounts[href] ?: 0
  val pageIndex = when {
    inChapter -> key.pageIndex + step
    forward -> 0
    else -> total - 1
  }
  return if (link == null || pageIndex < 0) null else NeighborTarget(link, href, pageIndex, total)
}

private fun EpubReaderActivity.neighborPreview(
  publication: Publication,
  target: NeighborTarget
): EpubPagePreview? {
  val progression = if (target.total > 0) target.pageIndex.toDouble() / target.total else 0.0
  return publication.locatorFromLink(target.link)?.let { base ->
    val locator = base.copy(locations = base.locations.copy(progression = progression))
    EpubPagePreview(
      locator,
      liveCarouselPageKey(PageKey(target.href, target.pageIndex, target.total)),
      target.pageIndex,
      target.href,
      target.total,
      viewModel.chapterTitleFor(locator)
    )
  }
}

/**
 * Makes sure the current page has a neighbor on each side, adding arithmetic ones to the window.
 * Returns false when the hidden navigator has to rebuild the window instead.
 */
internal fun EpubReaderActivity.ensureCarouselWindow(key: PageKey): Boolean {
  val pages = pagePreviews.value
  val index = pages.indexOfFirst { it.matches(key) }
  val window = if (index < 0) null else windowWithNeighbors(pages, index, key)
  window?.takeIf { it !== pages }?.let { pagePreviews.value = it }
  return window != null
}

private fun EpubReaderActivity.windowWithNeighbors(
  pages: List<EpubPagePreview>,
  index: Int,
  key: PageKey
): List<EpubPagePreview>? {
  val locator = pages[index].locator
  val before = if (index == 0) edgeNeighbor(key, locator, forward = false) else emptyList()
  val after = if (index == pages.lastIndex) edgeNeighbor(key, locator, forward = true) else emptyList()
  val extended = if (before == null || after == null) null else before + pages + after
  return when {
    extended == null -> null
    extended.size == pages.size -> pages
    before?.isNotEmpty() == true -> extended.take(MAX_CAROUSEL_WINDOW_PAGES)
    else -> extended.takeLast(MAX_CAROUSEL_WINDOW_PAGES)
  }
}

/** Nothing to add at a book boundary; null when the neighbor can't be computed. */
private fun EpubReaderActivity.edgeNeighbor(
  key: PageKey,
  locator: Locator,
  forward: Boolean
): List<EpubPagePreview>? =
  if (isBookBoundary(key, locator, forward)) emptyList() else neighborPage(key, forward)?.let(::listOf)

private fun EpubReaderActivity.scaleCarouselPage(view: View) {
  view.pivotX = root.width / 2f
  view.pivotY = root.height / 2f
  view.scaleX = EpubReaderActivity.LIVE_CAROUSEL_PAGE_WIDTH
  view.scaleY = EpubReaderActivity.CAROUSEL_PAGE_HEIGHT
}

internal fun EpubReaderActivity.syncLiveCarouselNeighbors(
  pages: List<EpubPagePreview>,
  key: EpubReaderActivity.PageKey?,
  moveViews: Boolean = true
) {
  val currentIndex = if (pageMode.value == EpubPageMode.CAROUSEL && key != null) {
    pages.indexOfFirst { it.matches(key) }
  } else {
    -1
  }
  if (currentIndex >= 0) {
    previousCarouselNavigator = syncLiveCarouselNavigator(
      previousCarouselContainer,
      previousCarouselNavigator,
      pages.getOrNull(currentIndex - 1)?.locator,
      EpubReaderActivity.LIVE_PREVIOUS_TAG
    )
    nextCarouselNavigator = syncLiveCarouselNavigator(
      nextCarouselContainer,
      nextCarouselNavigator,
      pages.getOrNull(currentIndex + 1)?.locator,
      EpubReaderActivity.LIVE_NEXT_TAG
    )
    if (moveViews) setLiveCarouselPosition(currentIndex.toFloat())
  }
}

private fun EpubReaderActivity.syncLiveCarouselNavigator(
  container: FragmentContainerView,
  fragment: EpubNavigatorFragment?,
  target: Locator?,
  tag: String
): EpubNavigatorFragment? = when {
  target == null -> {
    container.visibility = View.GONE
    fragment
  }

  fragment != null -> {
    container.visibility = View.VISIBLE
    if (!fragment.currentLocator.value.sameBookmarkPosition(target)) fragment.go(target)
    fragment
  }

  else -> {
    val book = (viewModel.state.value as? EpubReaderUiState.Ready)?.book
    if (book == null) {
      null
    } else {
      lateinit var created: EpubNavigatorFragment
      val factory = EpubNavigatorFactory(book.publication).createFragmentFactory(
        initialLocator = target,
        initialPreferences = navigatorPreferences(
          viewModel.settings.value,
          darkTheme.value,
          EpubPageMode.CAROUSEL
        ),
        listener = linkListener,
        paginationListener = object : EpubNavigatorFragment.PaginationListener {
          override fun onPageLoaded() {
            if (navigator === created) pageReady.value = true
          }

          override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
            if (navigator === created) {
              visiblePageKey.value = pageKey(locator, pageIndex, totalPages)
            } else {
              pageKey(locator, pageIndex, totalPages)
              logNeighborReady()
            }
          }
        },
        configuration = EpubNavigatorFragment.Configuration(
          shouldApplyInsetsPadding = false,
          selectionActionModeCallback = selectionActionModeCallback
        )
      )
      created = factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
        as EpubNavigatorFragment
      container.visibility = View.VISIBLE
      supportFragmentManager.commitNow { add(container.id, created, tag) }
      observeHighlightAnnotations(created)
      observePageLoading(created)
      created
    }
  }
}

internal fun EpubReaderActivity.onCarouselPositionChanged(cacheKey: String?, offset: Float) {
  val index = pagePreviews.value.indexOfFirst { it.cacheKey == cacheKey }
  if (index >= 0) setLiveCarouselPosition(index + offset)
}

internal fun EpubReaderActivity.setLiveCarouselPosition(position: Float) {
  if (root.width > 0) {
    visiblePageKey.value?.let { key ->
      val baseIndex = pagePreviews.value.indexOfFirst { it.matches(key) }
      if (baseIndex >= 0) {
        val step = root.width - (
          EpubReaderActivity.LIVE_CAROUSEL_PAGE_STEP_INSET_DP * resources.displayMetrics.density
        )
        fun place(view: View, pageIndex: Int) {
          // Neighbor containers may not have completed layout when their WebViews are attached.
          // Use the known full-screen container size so the first frame is centered and scaled.
          scaleCarouselPage(view)
          view.translationX = (pageIndex - position) * step
        }
        place(navigatorContainer, baseIndex)
        place(previousCarouselContainer, baseIndex - 1)
        place(nextCarouselContainer, baseIndex + 1)
      }
    }
  }
}

internal fun EpubReaderActivity.setLiveCarouselMode(enabled: Boolean) {
  previousCarouselContainer.visibility =
    if (enabled && previousCarouselNavigator != null) View.VISIBLE else View.GONE
  nextCarouselContainer.visibility =
    if (enabled && nextCarouselNavigator != null) View.VISIBLE else View.GONE
  if (enabled) {
    visiblePageKey.value?.let { key ->
      val currentIndex = pagePreviews.value.indexOfFirst { it.matches(key) }
      if (currentIndex >= 0) setLiveCarouselPosition(currentIndex.toFloat())
    }
  } else {
    val liveFragments = listOfNotNull(previousCarouselNavigator, nextCarouselNavigator)
    if (liveFragments.isNotEmpty()) {
      supportFragmentManager.commitNow { liveFragments.forEach(::remove) }
    }
    previousCarouselNavigator = null
    nextCarouselNavigator = null
    navigatorContainer.translationX = 0f
    previousCarouselContainer.translationX = 0f
    nextCarouselContainer.translationX = 0f
    navigatorContainer.scaleX = 1f
    previousCarouselContainer.scaleX = 1f
    nextCarouselContainer.scaleX = 1f
    navigatorContainer.scaleY = 1f
    previousCarouselContainer.scaleY = 1f
    nextCarouselContainer.scaleY = 1f
  }
}

internal fun EpubReaderActivity.setPageEdgePeeks(visible: Boolean) {
  previousPagePeek.visibility = if (visible) View.VISIBLE else View.GONE
  nextPagePeek.visibility = if (visible) View.VISIBLE else View.GONE
}

internal fun EpubReaderActivity.pagePeekView(previous: Boolean) = ImageView(this).apply {
  scaleType = ImageView.ScaleType.MATRIX
  visibility = View.GONE
  setBackgroundColor(viewModel.settings.value.backgroundColor(darkTheme.value))
  setOnClickListener { selectPeekPage(previous) }
}

internal fun EpubReaderActivity.selectPeekPage(previous: Boolean) {
  val fragment = navigator
  val key = visiblePageKey.value
  val pageOffset = if (previous) EpubReaderActivity.PREVIOUS_PAGE_OFFSET else EpubReaderActivity.NEXT_PAGE_OFFSET
  val index = pagePreviews.value.indexOfFirst { it.matches(key) }
  val target = pagePreviews.value.getOrNull(index + pageOffset)
  if (fragment != null && target != null) {
    viewModel.rememberPreviousPage(fragment.currentLocator.value)
    fragment.go(target.locator)
  }
}

internal fun EpubReaderActivity.updateCarouselPeeks(previews: List<EpubPagePreview>) {
  setPageEdgePeeks(false)
  syncLiveCarouselNeighbors(previews, visiblePageKey.value)
}

internal fun EpubReaderActivity.refreshCarouselPreviews() {
  val key = visiblePageKey.value ?: return
  if (!shouldRefreshCarouselPreviews(key)) return
  previewRefreshJob?.cancel()
  previewRefreshJob = lifecycleScope.launch {
    delay(EpubReaderActivity.PREVIEW_REFRESH_DEBOUNCE_MS)
    if (pageMode.value != EpubPageMode.CAROUSEL) return@launch
    val generation = ++previewGeneration
    previewJob?.cancel()
    previewJob = lifecycleScope.launch { refreshCarouselPreviewWindow(generation) }
  }
}

private fun EpubReaderActivity.shouldRefreshCarouselPreviews(key: PageKey): Boolean {
  val pages = pagePreviews.value
  val index = pages.indexOfFirst { it.matches(key) }
  return index < 0 || index <= 1 || index >= pages.lastIndex - 1
}

private suspend fun EpubReaderActivity.refreshCarouselPreviewWindow(generation: Long) {
  previewMutex.withLock {
    if (!isCurrentCarouselPreviewGeneration(generation)) return@withLock
    val key = visiblePageKey.value ?: return@withLock
    val active = navigator ?: return@withLock
    val preview = previewNavigator ?: ensurePreviewNavigator(active.currentLocator.value) ?: return@withLock
    val window = pagePreviews.value
    val index = window.indexOfFirst { it.matches(key) }
    val updated = if (index < 0) {
      recoverCarouselPreviewWindow(preview, active, key, generation)
    } else {
      extendCarouselPreviewWindow(preview, window, index, generation)
    }
    if (updated != null && isCurrentCarouselPreviewGeneration(generation)) {
      pagePreviews.value = updated
      updateCarouselPeeks(updated)
    }
  }
}

private suspend fun EpubReaderActivity.recoverCarouselPreviewWindow(
  preview: EpubNavigatorFragment,
  active: EpubNavigatorFragment,
  key: PageKey,
  generation: Long
): List<EpubPagePreview>? {
  val locator = active.currentLocator.value
  if (navigatePreviewTo(preview, locator, key, generation)) {
    val previous = discoverNeighborPages(
      preview,
      EpubReaderActivity.PAGE_CAROUSEL_RADIUS,
      generation,
      forward = false
    )
    if (navigatePreviewTo(preview, locator, key, generation)) {
      val next = discoverNeighborPages(
        preview,
        EpubReaderActivity.PAGE_CAROUSEL_RADIUS,
        generation,
        forward = true
      )
      val current = EpubPagePreview(
        locator,
        liveCarouselPageKey(key),
        key.pageIndex,
        key.resourceHref,
        key.totalPages,
        viewModel.chapterTitleFor(locator)
      )
      return previous.asReversed() + current + next
    }
  }
  return null
}

private suspend fun EpubReaderActivity.extendCarouselPreviewWindow(
  preview: EpubNavigatorFragment,
  initialWindow: List<EpubPagePreview>,
  currentIndex: Int,
  generation: Long
): List<EpubPagePreview> {
  var window = initialWindow
  val needsPrevious = currentIndex <= 1
  val needsNext = currentIndex >= initialWindow.lastIndex - 1
  if (needsPrevious) window = extendCarouselPreviewEdge(preview, window, false, generation)
  if (needsNext) window = extendCarouselPreviewEdge(preview, window, true, generation)
  return window
}

private suspend fun EpubReaderActivity.extendCarouselPreviewEdge(
  preview: EpubNavigatorFragment,
  window: List<EpubPagePreview>,
  forward: Boolean,
  generation: Long
): List<EpubPagePreview> {
  val edge = if (forward) window.lastOrNull() else window.firstOrNull()
  val neighboringPages = if (edge != null && isCurrentCarouselPreviewGeneration(generation)) {
    discoverCarouselEdgeNeighbors(preview, edge, forward, generation)
  } else {
    emptyList()
  }
  return when {
    neighboringPages.isEmpty() -> window
    forward -> (window + neighboringPages).takeLast(MAX_CAROUSEL_WINDOW_PAGES)
    else -> (neighboringPages + window).take(MAX_CAROUSEL_WINDOW_PAGES)
  }
}

private suspend fun EpubReaderActivity.discoverCarouselEdgeNeighbors(
  preview: EpubNavigatorFragment,
  edge: EpubPagePreview,
  forward: Boolean,
  generation: Long
): List<EpubPagePreview> {
  val edgeKey = PageKey(edge.resourceHref, edge.pageIndex, edge.totalPages)
  val canNavigate = !isBookBoundary(edgeKey, edge.locator, forward) &&
    navigatePreviewTo(preview, edge.locator, edgeKey, generation)
  return if (canNavigate) discoverNeighborPages(preview, 1, generation, forward) else emptyList()
}

private fun EpubReaderActivity.isCurrentCarouselPreviewGeneration(generation: Long) =
  generation == previewGeneration && pageMode.value == EpubPageMode.CAROUSEL

internal fun EpubReaderActivity.invalidatePreviewCache() {
  if (navigator == null) return
  previewGeneration++
  resourcePageCounts.clear()
  previewJob?.cancel()
  previewRefreshJob?.cancel()
  overviewWorkerJob?.cancel()
  pagePreviews.value = emptyList()
  overviewPages.value = emptyList()
  if (pageMode.value == EpubPageMode.SINGLE_PAGE) return
  previewInvalidationJob?.cancel()
  previewInvalidationJob = lifecycleScope.launch {
    delay(EpubReaderActivity.PREVIEW_INVALIDATION_DEBOUNCE_MS)
    if (pageMode.value != EpubPageMode.SINGLE_PAGE) selectPageMode(pageMode.value)
  }
}

internal fun EpubReaderActivity.createPreviewContainer(context: Context) =
  FragmentContainerView(context).apply {
    id = View.generateViewId()
    isClickable = false
    isFocusable = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    val cutout = ViewCompat.getRootWindowInsets(navigatorContainer)
      ?.getInsets(WindowInsetsCompat.Type.displayCutout())
    val margin = (EpubReaderActivity.READING_MARGIN_DP * resources.displayMetrics.density).toInt()
    setPadding(
      cutout?.left ?: 0,
      (cutout?.top ?: 0) + margin,
      cutout?.right ?: 0,
      (cutout?.bottom ?: 0) + margin
    )
  }

internal fun EpubReaderActivity.ensurePreviewPresentation(): FragmentContainerView? =
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
    null
  } else {
    previewNavigatorContainer ?: previewCapture?.display?.let(::showPreviewPresentation)
  }

internal fun EpubReaderActivity.attachPreviewNavigator(
  container: FragmentContainerView,
  fragment: EpubNavigatorFragment
) {
  // The presentation container is outside the activity's view lookup, so attach the fragment view
  // manually after the activity fragment manager creates it without a container ID.
  supportFragmentManager.commitNow { add(fragment, EpubReaderActivity.PREVIEW_NAVIGATOR_TAG) }
  container.addView(fragment.requireView(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
}

private fun EpubReaderActivity.showPreviewPresentation(display: Display): FragmentContainerView? {
  val presentation = previewPresentation
    ?: runCatching { Presentation(this, display) }.getOrNull()
  return presentation?.let { shownPresentation ->
    val container = createPreviewContainer(shownPresentation.context)
    shownPresentation.setContentView(container, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    val isShowing = shownPresentation.isShowing || runCatching { shownPresentation.show() }.isSuccess
    if (isShowing) {
      previewPresentation = shownPresentation
      previewNavigatorContainer = container
      container
    } else {
      null
    }
  }
}

private suspend fun EpubReaderActivity.discoverNeighborPages(
  fragment: EpubNavigatorFragment,
  count: Int,
  generation: Long,
  forward: Boolean
): List<EpubPagePreview> {
  val discovered = mutableListOf<EpubPagePreview>()
  var done = false
  while (discovered.size < count && generation == previewGeneration && !done) {
    val before = previewPageKey.value
    val locator = fragment.currentLocator.value
    if (before == null || isBookBoundary(before, locator, forward)) {
      done = true
    } else {
      if (forward) fragment.goForward(animated = false) else fragment.goBackward(animated = false)
      val after = withTimeoutOrNull(EpubReaderActivity.PAGE_PREVIEW_TURN_TIMEOUT_MS) {
        previewPageKey.first { it != before }
      }
      if (after == null || generation != previewGeneration) {
        done = true
      } else {
        val pageLocator = fragment.currentLocator.value
        discovered += EpubPagePreview(
          pageLocator,
          cacheKey = liveCarouselPageKey(after),
          pageIndex = after.pageIndex,
          resourceHref = after.resourceHref,
          totalPages = after.totalPages,
          chapterTitle = viewModel.chapterTitleFor(pageLocator)
        )
      }
    }
  }
  return discovered
}

private fun liveCarouselPageKey(key: PageKey) =
  "live:${key.resourceHref}:${key.pageIndex}:${key.totalPages}"

internal suspend fun EpubReaderActivity.navigatePreviewTo(
  fragment: EpubNavigatorFragment,
  locator: Locator,
  key: PageKey,
  generation: Long
): Boolean {
  val initialKey = if (generation == previewGeneration) {
    previewPageKey.value ?: withTimeoutOrNull(EpubReaderActivity.PAGE_PREVIEW_INITIAL_READY_TIMEOUT_MS) {
      previewPageKey.first { it != null }
    }
  } else {
    null
  }
  return initialKey?.let { currentKey ->
    if (currentKey.samePage(key)) {
      true
    } else {
      fragment.go(locator)
      withTimeoutOrNull(EpubReaderActivity.PAGE_PREVIEW_TURN_TIMEOUT_MS) {
        previewPageKey.first { it?.samePage(key) == true }
        generation == previewGeneration
      } ?: false
    }
  } == true
}

internal fun PageKey.samePage(other: PageKey) =
  resourceHref == other.resourceHref && pageIndex == other.pageIndex

/**
 * Captures the page shown by [fragment] as [key]. A [locator] names the page when the navigator's
 * own locator is not trustworthy yet, which is right after a direct scroll.
 */
internal suspend fun EpubReaderActivity.capturePreview(
  fragment: EpubNavigatorFragment,
  key: PageKey? = previewPageKey.value,
  frameWatermark: Long? = null,
  locator: Locator? = null
): EpubPagePreview? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
  null
} else {
  val view = fragment.publicationView
  val capture = previewCapture
  val previewKey = key
  if (!canCaptureFromView(view) || capture == null || previewKey == null) {
    val reason = "invalid capture source view=${view.width}x${view.height} " +
      "attached=${view.isAttachedToWindow} capture=${capture?.isAvailable} key=$previewKey"
    Log.e(EpubReaderActivity.PREVIEW_LOG_TAG, reason)
    capture?.let { disablePreviewModes(it, reason) }
    null
  } else {
    capturePreviewForKey(fragment, view, capture, previewKey, frameWatermark, locator)
  }
}

private fun EpubReaderActivity.canCaptureFromView(view: View) =
  pageModesAvailable.value && view.width > 0 && view.height > 0

@RequiresApi(Build.VERSION_CODES.R)
private suspend fun EpubReaderActivity.capturePreviewForKey(
  fragment: EpubNavigatorFragment,
  view: View,
  capture: VulkanPreviewCapture,
  key: PageKey,
  frameWatermark: Long?,
  pageLocator: Locator?
): EpubPagePreview? {
  val locator = pageLocator ?: fragment.currentLocator.value
  val cacheKey = previewCacheKey(key)
  // A thumbnail stored by an earlier visit or session needs no new frame.
  val stored = withContext(Dispatchers.IO) { previewCache.contains(cacheKey) } ||
    capturePreviewToCache(view, capture, cacheKey, frameWatermark)
  return if (stored) {
    EpubPagePreview(
      locator,
      cacheKey,
      key.pageIndex,
      key.resourceHref,
      key.totalPages,
      viewModel.chapterTitleFor(locator)
    )
  } else {
    null
  }
}

@RequiresApi(Build.VERSION_CODES.R)
private suspend fun EpubReaderActivity.capturePreviewToCache(
  view: View,
  capture: VulkanPreviewCapture,
  cacheKey: String,
  frameWatermark: Long?
): Boolean {
  val width = (view.width * EpubReaderActivity.PAGE_PREVIEW_SCALE).toInt().coerceAtLeast(1)
  val height = (view.height * EpubReaderActivity.PAGE_PREVIEW_SCALE).toInt().coerceAtLeast(1)
  val bitmap = capturePreviewBitmap(view, capture, width, height, frameWatermark) ?: return false
  withContext(Dispatchers.IO) { previewCache.put(cacheKey, bitmap) }
  return true
}

@RequiresApi(Build.VERSION_CODES.R)
private suspend fun EpubReaderActivity.capturePreviewBitmap(
  view: View,
  capture: VulkanPreviewCapture,
  width: Int,
  height: Int,
  watermark: Long?
): Bitmap? {
  val frameWatermark = watermark ?: -1L
  view.invalidate()
  val visualStateReady = view !is WebView || awaitVisualState(view)
  if (!visualStateReady) {
    Log.e(EpubReaderActivity.PREVIEW_LOG_TAG, "WebView visual state timed out")
  }
  val presentationWindow = previewPresentation?.window
  val copiedBitmap = if (visualStateReady && presentationWindow != null) {
    val drew = awaitPresentationDraw(presentationWindow.decorView)
    if (drew) capturePreviewWindow(presentationWindow, width, height) else null
  } else {
    null
  }
  val bitmap = copiedBitmap ?: if (visualStateReady) {
    capture.captureAfter(
      frameWatermark,
      width,
      height,
      EpubReaderActivity.PREVIEW_FRAME_TIMEOUT_MS
    )
  } else {
    null
  }
  if (bitmap == null) {
    disablePreviewModes(
      capture,
      "frame capture failed watermark=$frameWatermark current=${capture.frameSequence()} " +
        "view=${view.width}x${view.height} attached=${view.isAttachedToWindow}"
    )
  }
  return bitmap
}

@RequiresApi(Build.VERSION_CODES.R)
private suspend fun EpubReaderActivity.awaitPresentationDraw(view: View): Boolean =
  withTimeoutOrNull(EpubReaderActivity.PREVIEW_FRAME_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
      val observer = view.viewTreeObserver
      lateinit var listener: ViewTreeObserver.OnDrawListener
      listener = ViewTreeObserver.OnDrawListener {
        view.post {
          if (observer.isAlive) observer.removeOnDrawListener(listener)
          if (continuation.isActive) continuation.resume(Unit)
        }
      }
      observer.addOnDrawListener(listener)
      continuation.invokeOnCancellation {
        view.post {
          if (observer.isAlive) observer.removeOnDrawListener(listener)
        }
      }
      view.postInvalidateOnAnimation()
    }
    true
  } ?: false

@RequiresApi(Build.VERSION_CODES.R)
private suspend fun EpubReaderActivity.capturePreviewWindow(
  window: Window,
  width: Int,
  height: Int
): Bitmap? = withTimeoutOrNull(EpubReaderActivity.PREVIEW_FRAME_TIMEOUT_MS) {
  suspendCancellableCoroutine { continuation ->
    val bitmap = createBitmap(width, height, Bitmap.Config.RGB_565)
    PixelCopy.request(window, bitmap, { result ->
      if (!continuation.isActive) {
        bitmap.recycle()
      } else if (result == PixelCopy.SUCCESS) {
        continuation.resume(bitmap)
      } else {
        Log.w(EpubReaderActivity.PREVIEW_LOG_TAG, "PixelCopy preview failed: $result")
        bitmap.recycle()
        continuation.resume(null)
      }
    }, Handler(Looper.getMainLooper()))
  }
}

internal suspend fun EpubReaderActivity.awaitVisualState(webView: WebView): Boolean =
  withTimeoutOrNull(EpubReaderActivity.PREVIEW_FRAME_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
      webView.postVisualStateCallback(
        SystemClock.uptimeMillis(),
        object : WebView.VisualStateCallback() {
          override fun onComplete(requestId: Long) {
            if (continuation.isActive) continuation.resume(Unit)
          }
        }
      )
    }
    true
  } ?: false

internal fun EpubPagePreview.matches(key: PageKey?): Boolean =
  key != null && pageIndex == key.pageIndex && resourceHref == key.resourceHref

internal fun EpubReaderActivity.pageKey(locator: Locator, pageIndex: Int, totalPages: Int): PageKey {
  val href = locator.href.toString().substringBefore('#')
  // Every page report teaches the page count that neighborPage needs for unvisited chapters.
  if (totalPages > 0) resourcePageCounts[href] = totalPages
  return PageKey(href, pageIndex, totalPages)
}

/**
 * What fixes the page layout: the book and every setting that reflows it. It is stable across
 * launches (enum hash codes are not) and names both the thumbnails and the stored page counts.
 */
internal fun EpubReaderActivity.previewLayoutScope(): String {
  val book = (viewModel.state.value as? EpubReaderUiState.Ready)?.book
  val settings = viewModel.settings.value
  return listOf(
    "v2",
    book?.bookId.orEmpty(),
    book?.publication?.metadata?.identifier.toString(),
    settings.toJson(),
    settings.effectivePreset(darkTheme.value).name,
    book?.rtl
  ).joinToString("|")
}

/** A function from page to cache key that reads the settings and highlights only once. */
internal fun EpubReaderActivity.previewKeyFactory(): (PageKey) -> String {
  val scope = previewLayoutScope()
  val highlightsByHref = viewModel.annotations.value
    .filter { it.kind == EpubAnnotationKind.HIGHLIGHT }
    .groupBy { it.locator.href.toString().substringBefore('#') }
    .mapValues { (_, list) -> list.joinToString(",") { "${it.id}:${it.color?.name}" } }
  // Only the highlights drawn on a page change how it looks.
  return { key ->
    "$scope|${key.resourceHref}|${key.pageIndex}|${key.totalPages}|${highlightsByHref[key.resourceHref].orEmpty()}"
  }
}

internal fun EpubReaderActivity.previewCacheKey(key: PageKey): String = previewKeyFactory()(key)

internal fun EpubReaderActivity.isBookBoundary(key: PageKey, locator: Locator, forward: Boolean): Boolean {
  val book = (viewModel.state.value as? EpubReaderUiState.Ready)?.book
  return book?.let { hasReadingOrderBoundary(it, key, locator, forward) } ?: false
}

private fun hasReadingOrderBoundary(
  book: OpenEpub,
  key: PageKey,
  locator: Locator,
  forward: Boolean
): Boolean {
  val readingOrder = book.publication.readingOrder
  val resourceIndex = readingOrder.indexOfFirst {
    it.href.toString().substringBefore('#') == key.resourceHref
  }
  val pageExists = resourceIndex >= 0 && key.totalPages > 0
  return pageExists && boundaryMatchesDirection(book, readingOrder.lastIndex, resourceIndex, key, locator, forward)
}

private fun boundaryMatchesDirection(
  book: OpenEpub,
  lastResourceIndex: Int,
  resourceIndex: Int,
  key: PageKey,
  locator: Locator,
  forward: Boolean
): Boolean {
  val progression = locator.locations.progression
  val atFirstPage = progression?.let { it <= EpubReaderActivity.PAGE_EDGE_PROGRESS }
    ?: (!book.rtl && key.pageIndex == 0)
  val atLastPage = progression?.let { it >= 1.0 - EpubReaderActivity.PAGE_EDGE_PROGRESS }
    ?: (!book.rtl && key.pageIndex == key.totalPages - 1)
  return if (forward) {
    resourceIndex == lastResourceIndex && atLastPage
  } else {
    resourceIndex == 0 && atFirstPage
  }
}
