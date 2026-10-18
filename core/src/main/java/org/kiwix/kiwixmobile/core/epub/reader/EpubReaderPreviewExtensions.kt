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

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.shared.publication.Locator
import kotlin.coroutines.resume

private typealias PageKey = EpubReaderActivity.PageKey

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

internal fun EpubReaderActivity.selectPageMode(mode: EpubPageMode) {
  if (mode != EpubPageMode.SINGLE_PAGE && !pageModesAvailable.value) return
  pageMode.value = mode
  setNavigatorSize(mode == EpubPageMode.CAROUSEL)
  if (mode == EpubPageMode.SINGLE_PAGE) {
    previewGeneration++
    cancelPreviewJobs()
    return
  }
  val generation = ++previewGeneration
  cancelPreviewJobs()
  previewJob = lifecycleScope.launch {
    generatePreviews(mode, generation)
  }
}

private fun EpubReaderActivity.cancelPreviewJobs() {
  previewJob?.cancel()
  previewRefreshJob?.cancel()
}

private suspend fun EpubReaderActivity.generatePreviews(mode: EpubPageMode, generation: Long) {
  previewMutex.withLock {
    val fragment = navigator
    if (generation == previewGeneration && fragment != null) {
      val current = fragment.currentLocator.value
      val key = visiblePageKey.value ?: withTimeoutOrNull(
        EpubReaderActivity.PAGE_PREVIEW_TURN_TIMEOUT_MS
      ) {
        visiblePageKey.first { it != null }
      }
      val previewFragment = key?.let { ensurePreviewNavigator(current) }
      if (key != null && previewFragment != null) {
        preparePreviewWindow(mode, generation, current, key, previewFragment)
      }
    }
  }
}

private suspend fun EpubReaderActivity.preparePreviewWindow(
  mode: EpubPageMode,
  generation: Long,
  current: Locator,
  key: PageKey,
  previewFragment: EpubNavigatorFragment
) {
  val radius = if (mode == EpubPageMode.OVERVIEW) {
    EpubReaderActivity.PAGE_OVERVIEW_PAGE_COUNT - 1
  } else {
    EpubReaderActivity.PAGE_CAROUSEL_RADIUS
  }
  if (showCachedPreviewWindow(mode, key, radius)) return

  setPageEdgePeeks(false)
  val startedAt = SystemClock.elapsedRealtime()
  val previews = capturePagePreviews(
    previewFragment,
    current,
    key,
    radius,
    generation,
    includePrevious = mode != EpubPageMode.OVERVIEW
  ) { progress ->
    if (generation == previewGeneration) publishPreviewProgress(mode, progress)
  }
  logPreviewWindow(mode, startedAt, radius, previews)
  if (generation == previewGeneration) publishPreviewWindow(mode, key, previews)
}

private fun EpubReaderActivity.showCachedPreviewWindow(
  mode: EpubPageMode,
  key: PageKey,
  radius: Int
): Boolean {
  val existing = pagePreviews.value
  val index = existing.indexOfFirst { it.matches(key) }
  val hasEnoughPages = if (mode == EpubPageMode.OVERVIEW) {
    index >= 0 && (index + radius < existing.size || !overviewHasNext.value)
  } else {
    index >= radius && index + radius < existing.size
  }
  if (!hasEnoughPages) return false

  lastVisiblePageKey = key
  if (mode == EpubPageMode.OVERVIEW) {
    if (overviewPages.value.isEmpty()) overviewPages.value = existing
    updateOverviewEdges(overviewPages.value)
    setPageEdgePeeks(false)
  } else {
    updateCarouselPeeks(existing)
  }
  return true
}

private fun EpubReaderActivity.publishPreviewProgress(
  mode: EpubPageMode,
  previews: List<EpubPagePreview>
) {
  pagePreviews.value = previews
  if (mode == EpubPageMode.OVERVIEW) {
    overviewPages.value = previews
    updateOverviewEdges(previews)
  }
  if (mode == EpubPageMode.CAROUSEL) updateCarouselPeeks(previews)
}

private fun EpubReaderActivity.publishPreviewWindow(
  mode: EpubPageMode,
  key: PageKey,
  previews: List<EpubPagePreview>
) {
  publishPreviewProgress(mode, previews)
  lastVisiblePageKey = key
  if (mode == EpubPageMode.OVERVIEW) setPageEdgePeeks(false) else updateCarouselPeeks(previews)
}

private fun logPreviewWindow(
  mode: EpubPageMode,
  startedAt: Long,
  radius: Int,
  previews: List<EpubPagePreview>
) {
  if (Log.isLoggable(EpubReaderActivity.PREVIEW_LOG_TAG, Log.DEBUG)) {
    val elapsed = SystemClock.elapsedRealtime() - startedAt
    Log.d(
      EpubReaderActivity.PREVIEW_LOG_TAG,
      "window ready mode=$mode ms=$elapsed pages=${previews.size} radius=$radius"
    )
  }
}

internal fun EpubReaderActivity.setNavigatorSize(carousel: Boolean) {
  // Keep Readium's WebView laid out for the full display so its EPUB pagination stays stable.
  // Android applies this transform to the rendered View; it does not trigger a smaller reflow.
  navigatorContainer.pivotX = navigatorContainer.width / 2f
  navigatorContainer.pivotY = navigatorContainer.height / 2f
  navigatorContainer.scaleX = if (carousel) EpubReaderActivity.CAROUSEL_PAGE_WIDTH else 1f
  navigatorContainer.scaleY = if (carousel) EpubReaderActivity.CAROUSEL_PAGE_HEIGHT else 1f
  if (carousel) {
    val peekWidth = (root.width * EpubReaderActivity.PAGE_PEEK_WIDTH).toInt()
    val peekHeight = (root.height * EpubReaderActivity.CAROUSEL_PEEK_HEIGHT).toInt()
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
  val index = previews.indexOfFirst { it.matches(visiblePageKey.value) }
  setPagePeekImage(previousPagePeek, previews.getOrNull(index - 1)?.bitmap, previous = true)
  setPagePeekImage(nextPagePeek, previews.getOrNull(index + 1)?.bitmap, previous = false)
  previousPagePeek.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
  nextPagePeek.visibility = if (index in 0 until previews.lastIndex) View.VISIBLE else View.INVISIBLE
}

internal fun EpubReaderActivity.setPagePeekImage(view: ImageView, bitmap: Bitmap?, previous: Boolean) {
  view.setImageBitmap(bitmap)
  if (bitmap == null) return
  view.post {
    if (view.width == 0 || view.height == 0) return@post
    val scale = view.height.toFloat() * EpubReaderActivity.CAROUSEL_PEEK_SCALE / bitmap.height
    val x = if (previous) view.width - bitmap.width * scale else 0f
    val y = (view.height - bitmap.height * scale) / 2f
    view.imageMatrix = Matrix().apply {
      setValues(floatArrayOf(scale, 0f, x, 0f, scale, y, 0f, 0f, 1f))
    }
  }
}

internal fun EpubReaderActivity.updateOverviewEdges(pages: List<EpubPagePreview>) {
  val first = pages.firstOrNull()
  val last = pages.lastOrNull()
  overviewHasPrevious.value = first?.let {
    !isBookBoundary(PageKey(it.resourceHref, it.pageIndex, it.totalPages), it.locator, forward = false)
  } ?: true
  overviewHasNext.value = last?.let {
    !isBookBoundary(PageKey(it.resourceHref, it.pageIndex, it.totalPages), it.locator, forward = true)
  } ?: true
}

internal fun EpubReaderActivity.loadMoreOverviewPages(before: Boolean) {
  val currentPages = overviewPages.value
  val edge = overviewEdge(currentPages, before)
  if (canLoadOverviewEdge(before) && !overviewPagesLoading.value && edge != null) {
    overviewPagesLoading.value = true
    overviewLoadJob = lifecycleScope.launch {
      try {
        loadOverviewBatch(edge, currentPages, before)
      } finally {
        overviewPagesLoading.value = false
      }
    }
  }
}

private fun EpubReaderActivity.canLoadOverviewEdge(before: Boolean) =
  if (before) overviewHasPrevious.value else overviewHasNext.value

private fun overviewEdge(
  pages: List<EpubPagePreview>,
  before: Boolean
) = (if (before) pages.firstOrNull() else pages.lastOrNull())?.takeIf { it.totalPages > 0 }

private suspend fun EpubReaderActivity.loadOverviewBatch(
  edge: EpubPagePreview,
  currentPages: List<EpubPagePreview>,
  before: Boolean
) {
  previewMutex.withLock {
    val generation = previewGeneration
    val fragment = ensurePreviewNavigator(edge.locator)
    if (fragment != null) {
      val key = PageKey(edge.resourceHref, edge.pageIndex, edge.totalPages)
      if (navigatePreviewTo(fragment, edge.locator, key, generation)) {
        captureOverviewBatch(fragment, currentPages, before, generation)
      }
    }
  }
}

private suspend fun EpubReaderActivity.captureOverviewBatch(
  fragment: EpubNavigatorFragment,
  currentPages: List<EpubPagePreview>,
  before: Boolean,
  generation: Long
) {
  val added = mutableListOf<EpubPagePreview>()
  var loadedCount = 0
  while (loadedCount < EpubReaderActivity.OVERVIEW_LOAD_BATCH) {
    val page = turnAndCapture(fragment, forward = !before, generation) ?: break
    added += page
    loadedCount++
    val updated = if (before) added.asReversed() + currentPages else currentPages + added
    overviewPages.value = updated
    updateOverviewEdges(updated)
  }
  if (added.isEmpty()) setOverviewEdgeUnavailable(before)
}

private fun EpubReaderActivity.setOverviewEdgeUnavailable(before: Boolean) {
  if (before) overviewHasPrevious.value = false else overviewHasNext.value = false
}

internal fun EpubReaderActivity.refreshCarouselPreviews(fragment: EpubNavigatorFragment) {
  val key = visiblePageKey.value ?: return
  val previousKey = lastVisiblePageKey
  lastVisiblePageKey = key
  val index = pagePreviews.value.indexOfFirst { it.matches(key) }
  if (index in 1 until pagePreviews.value.lastIndex) return
  previewRefreshJob?.cancel()
  previewRefreshJob = lifecycleScope.launch {
    delay(EpubReaderActivity.PREVIEW_REFRESH_DEBOUNCE_MS)
    if (pageMode.value != EpubPageMode.CAROUSEL) return@launch
    val currentKey = visiblePageKey.value ?: return@launch
    val generation = ++previewGeneration
    previewJob?.cancel()
    previewJob = lifecycleScope.launch {
      previewMutex.withLock {
        if (generation != previewGeneration || pageMode.value != EpubPageMode.CAROUSEL) {
          return@withLock
        }
        val previewFragment = previewNavigator ?: return@withLock
        val previews = extendPreviewWindow(
          previewFragment,
          pagePreviews.value,
          previousKey,
          currentKey,
          generation
        ) ?: run {
          val radius = EpubReaderActivity.PAGE_CAROUSEL_RADIUS
          capturePagePreviews(
            previewFragment,
            fragment.currentLocator.value,
            currentKey,
            radius,
            generation
          ) { currentPreviews ->
            if (generation == previewGeneration && pageMode.value == EpubPageMode.CAROUSEL) {
              pagePreviews.value = currentPreviews
              updateCarouselPeeks(currentPreviews)
            }
          }
        }
        if (generation == previewGeneration && pageMode.value == EpubPageMode.CAROUSEL) {
          pagePreviews.value = previews
          updateCarouselPeeks(previews)
        }
      }
    }
  }
}

internal fun EpubReaderActivity.invalidatePreviewCache() {
  if (navigator == null) return
  previewGeneration++
  previewJob?.cancel()
  previewRefreshJob?.cancel()
  overviewLoadJob?.cancel()
  pagePreviews.value = emptyList()
  overviewPages.value = emptyList()
  overviewHasPrevious.value = true
  overviewHasNext.value = true
  lastVisiblePageKey = null
  if (pageMode.value == EpubPageMode.SINGLE_PAGE) return
  previewInvalidationJob?.cancel()
  previewInvalidationJob = lifecycleScope.launch {
    delay(EpubReaderActivity.PREVIEW_INVALIDATION_DEBOUNCE_MS)
    if (pageMode.value != EpubPageMode.SINGLE_PAGE) selectPageMode(pageMode.value)
  }
}

internal suspend fun EpubReaderActivity.extendPreviewWindow(
  fragment: EpubNavigatorFragment,
  previews: List<EpubPagePreview>,
  previousKey: PageKey?,
  currentKey: PageKey,
  generation: Long
): List<EpubPagePreview>? = if (previews.size < 2) {
  null
} else {
  val currentIndex = previews.indexOfFirst { it.matches(currentKey) }
  if (currentIndex in 1 until previews.lastIndex) {
    previews
  } else {
    previewWindowPlan(previews, currentIndex, previousKey, currentKey)?.let { plan ->
      capturePreviewWindowExtension(fragment, previews, plan, generation)
    }
  }
}

private data class PreviewWindowPlan(
  val forward: Boolean,
  val currentIsCached: Boolean,
  val targetKey: PageKey
)

private fun EpubReaderActivity.previewWindowPlan(
  previews: List<EpubPagePreview>,
  currentIndex: Int,
  previousKey: PageKey?,
  currentKey: PageKey
): PreviewWindowPlan? {
  val currentIsCached = currentIndex >= 0
  val previous = previousKey
  val direction = if (currentIsCached) {
    currentIndex == previews.lastIndex
  } else if (previous != null) {
    adjacentPreviewDirection(previews, previous, currentKey)
  } else {
    null
  }
  val targetKey = if (currentIsCached) currentKey else previous
  return if (direction != null && targetKey != null) {
    PreviewWindowPlan(direction, currentIsCached, targetKey)
  } else {
    null
  }
}

private fun EpubReaderActivity.adjacentPreviewDirection(
  previews: List<EpubPagePreview>,
  previousKey: PageKey,
  currentKey: PageKey
): Boolean? {
  val previousIndex = previews.indexOfFirst { it.matches(previousKey) }
  val pageDelta = if (bookIsRtl()) -1 else 1
  return when {
    previousIndex == previews.lastIndex &&
      sameResource(previousKey, currentKey) &&
      currentKey.pageIndex == previousKey.pageIndex + pageDelta -> true
    previousIndex == 0 &&
      sameResource(previousKey, currentKey) &&
      currentKey.pageIndex == previousKey.pageIndex - pageDelta -> false
    else -> null
  }
}

private suspend fun EpubReaderActivity.capturePreviewWindowExtension(
  fragment: EpubNavigatorFragment,
  previews: List<EpubPagePreview>,
  plan: PreviewWindowPlan,
  generation: Long
): List<EpubPagePreview>? {
  val target = previews.firstOrNull { it.matches(plan.targetKey) }
  val positioned = target != null &&
    navigatePreviewTo(fragment, target.locator, plan.targetKey, generation)
  return if (positioned) {
    val current = if (plan.currentIsCached) null else turnAndCapture(fragment, plan.forward, generation)
    val added = if (plan.currentIsCached || current != null) {
      turnAndCapture(fragment, plan.forward, generation)
    } else {
      null
    }
    when {
      plan.currentIsCached && added == null -> previews
      !plan.currentIsCached && current == null -> null
      else -> replacePreviewWindow(previews, plan, current, added)
    }
  } else {
    null
  }
}

private fun replacePreviewWindow(
  previews: List<EpubPagePreview>,
  plan: PreviewWindowPlan,
  current: EpubPagePreview?,
  added: EpubPagePreview?
) = if (plan.forward) {
  if (plan.currentIsCached) {
    previews.drop(1) + listOfNotNull(added)
  } else {
    previews.drop(2) + listOfNotNull(current, added)
  }
} else if (plan.currentIsCached) {
  listOfNotNull(added) + previews.dropLast(1)
} else {
  listOfNotNull(added, current) + previews.dropLast(2)
}

internal fun EpubReaderActivity.createPreviewContainer() = FragmentContainerView(this).apply {
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

internal fun EpubReaderActivity.sameResource(first: PageKey, second: PageKey) =
  first.resourceHref == second.resourceHref

internal fun EpubReaderActivity.bookIsRtl() =
  (viewModel.state.value as? EpubReaderUiState.Ready)?.book?.rtl == true

internal suspend fun EpubReaderActivity.capturePagePreviews(
  fragment: EpubNavigatorFragment,
  targetLocator: Locator,
  targetKey: PageKey,
  radius: Int,
  generation: Long,
  includePrevious: Boolean = true,
  onProgress: (List<EpubPagePreview>) -> Unit
): List<EpubPagePreview> = if (!navigatePreviewTo(fragment, targetLocator, targetKey, generation)) {
  emptyList()
} else {
  val before = mutableListOf<EpubPagePreview>()
  val after = mutableListOf<EpubPagePreview>()
  val current = capturePreview(fragment)
  onProgress(listOfNotNull(current))
  if (includePrevious) {
    var capturedBefore = 0
    while (capturedBefore < radius && generation == previewGeneration) {
      val preview = turnAndCapture(fragment, forward = false, generation) ?: break
      before += preview
      capturedBefore++
      onProgress(before.asReversed() + listOfNotNull(current))
    }
  }
  // Overview begins at the current page and extends forward; this avoids a return jump before
  // any more thumbnails are available. Earlier pages load only when requested.
  val restoredToCurrent = !includePrevious ||
    navigatePreviewTo(fragment, targetLocator, targetKey, generation)
  if (restoredToCurrent) {
    var capturedAfter = 0
    while (capturedAfter < radius && generation == previewGeneration) {
      val preview = turnAndCapture(fragment, forward = true, generation) ?: break
      after += preview
      capturedAfter++
      onProgress(before.asReversed() + listOfNotNull(current) + after)
    }
  }
  before.asReversed() + listOfNotNull(current) + after
}

internal suspend fun EpubReaderActivity.navigatePreviewTo(
  fragment: EpubNavigatorFragment,
  locator: Locator,
  key: PageKey,
  generation: Long
): Boolean {
  val initialKey = if (generation == previewGeneration) {
    previewPageKey.value ?: withTimeoutOrNull(EpubReaderActivity.PAGE_PREVIEW_TURN_TIMEOUT_MS) {
      previewPageKey.first { it != null }
    }
  } else {
    null
  }
  return initialKey?.let { currentKey ->
    if (currentKey == key) {
      true
    } else {
      fragment.go(locator)
      withTimeoutOrNull(EpubReaderActivity.PAGE_PREVIEW_TURN_TIMEOUT_MS) {
        previewPageKey.first { it == key }
        generation == previewGeneration
      } ?: false
    }
  } == true
}

internal suspend fun EpubReaderActivity.turnAndCapture(
  fragment: EpubNavigatorFragment,
  forward: Boolean,
  generation: Long
): EpubPagePreview? = previewPageKey.value
  ?.takeIf { generation == previewGeneration }
  ?.let { before -> capturePageTurn(fragment, before, forward, generation) }

private suspend fun EpubReaderActivity.capturePageTurn(
  fragment: EpubNavigatorFragment,
  before: PageKey,
  forward: Boolean,
  generation: Long
): EpubPagePreview? = if (isBookBoundary(before, fragment.currentLocator.value, forward)) {
  Log.d(
    EpubReaderActivity.PREVIEW_LOG_TAG,
    "book boundary page=${before.pageIndex} forward=$forward"
  )
  null
} else {
  if (forward) fragment.goForward(animated = false) else fragment.goBackward(animated = false)
  val changed = withTimeoutOrNull(EpubReaderActivity.PAGE_PREVIEW_TURN_TIMEOUT_MS) {
    previewPageKey.first { it != before }
  }
  if (changed == null && Log.isLoggable(EpubReaderActivity.PREVIEW_LOG_TAG, Log.DEBUG)) {
    Log.d(
      EpubReaderActivity.PREVIEW_LOG_TAG,
      "page turn timeout page=${before.pageIndex} forward=$forward"
    )
  }
  changed?.takeIf { generation == previewGeneration }?.let { capturePreview(fragment, it) }
}

internal suspend fun EpubReaderActivity.capturePreview(
  fragment: EpubNavigatorFragment,
  key: PageKey? = previewPageKey.value
): EpubPagePreview? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
  val view = fragment.publicationView
  val capture = previewCapture
  val previewKey = key
  return if (canCaptureFromView(view) && capture != null && previewKey != null) {
    capturePreviewForKey(fragment, view, capture, previewKey)
  } else {
    null
  }
}

private fun EpubReaderActivity.canCaptureFromView(view: View) =
  pageModesAvailable.value && view.width > 0 && view.height > 0

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun EpubReaderActivity.capturePreviewForKey(
  fragment: EpubNavigatorFragment,
  view: View,
  capture: VulkanPreviewCapture,
  key: PageKey
): EpubPagePreview? {
  val locator = fragment.currentLocator.value
  val width = (view.width * EpubReaderActivity.PAGE_PREVIEW_SCALE).toInt().coerceAtLeast(1)
  val height = (view.height * EpubReaderActivity.PAGE_PREVIEW_SCALE).toInt().coerceAtLeast(1)
  val bitmap = capturePreviewBitmap(view, capture, width, height)
  return bitmap?.let {
    EpubPagePreview(
      locator,
      it,
      key.pageIndex,
      key.resourceHref,
      key.totalPages,
      viewModel.chapterTitleFor(locator)
    )
  }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun EpubReaderActivity.capturePreviewBitmap(
  view: View,
  capture: VulkanPreviewCapture,
  width: Int,
  height: Int
): Bitmap? {
  val frameBeforeVisualUpdate = capture.frameSequence()
  if (view is WebView) awaitVisualState(view)
  val bitmap = capture.captureAfter(
    frameBeforeVisualUpdate,
    width,
    height,
    EpubReaderActivity.PREVIEW_FRAME_TIMEOUT_MS
  )
  if (bitmap == null) disablePreviewModes(capture)
  return bitmap
}

internal suspend fun EpubReaderActivity.awaitVisualState(webView: WebView) {
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
  }
}

private fun EpubPagePreview.matches(key: PageKey?): Boolean =
  key != null && pageIndex == key.pageIndex && resourceHref == key.resourceHref

internal fun EpubReaderActivity.pageKey(locator: Locator, pageIndex: Int, totalPages: Int) =
  PageKey(locator.href.toString().substringBefore('#'), pageIndex, totalPages)

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
