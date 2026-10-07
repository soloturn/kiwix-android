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
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.kiwix.kiwixmobile.core.epub.reader.EpubReaderActivity.PageKey
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/** The pages the overview grid currently shows, and whether the grid is being scrolled. */
internal data class OverviewViewport(
  val pages: List<EpubPagePreview> = emptyList(),
  val scrolling: Boolean = false
)

/** Bookkeeping for the overview worker that is not part of what the grid shows. */
internal class OverviewState {
  /** The page last reached by scrolling the preview WebView directly; Readium reports it late. */
  var directKey: PageKey? = null
  var startedAt = 0L
  var firstThumbnailLogged = false
}

internal fun EpubReaderActivity.onOverviewViewportChanged(pages: List<EpubPagePreview>, scrolling: Boolean) {
  overviewViewport.value = OverviewViewport(pages, scrolling)
}

/**
 * Shows the whole book as a grid at once, from the page counts already known, and then fills in
 * thumbnails only for the pages on screen. Cached thumbnails appear without any capture.
 */
internal fun EpubReaderActivity.startOverview(generation: Long) {
  setPageEdgePeeks(false)
  overviewState.startedAt = SystemClock.elapsedRealtime()
  overviewState.firstThumbnailLogged = false
  overviewViewport.value = OverviewViewport()
  overviewPages.value = buildOverviewSkeleton()
  val scope = previewLayoutScope()
  overviewWorkerJob = lifecycleScope.launch {
    val stored = withContext(Dispatchers.IO) { previewCache.loadPageCounts(scope) }
    val learned = stored.count { (href, count) -> resourcePageCounts.putIfAbsent(href, count) == null }
    if (learned > 0) overviewPages.value = buildOverviewSkeleton()
    overviewViewport.collectLatest { viewport ->
      if (viewport.pages.isNotEmpty() && !viewport.scrolling && generation == previewGeneration) {
        previewMutex.withLock { fillOverviewPages(viewport.pages, generation) }
      }
    }
  }
}

/** One entry per page of every chapter whose page count is known, else one per chapter. */
internal fun EpubReaderActivity.buildOverviewSkeleton(): List<EpubPagePreview> {
  val publication = (viewModel.state.value as? EpubReaderUiState.Ready)?.book?.publication
  val keyOf = previewKeyFactory()
  return publication?.readingOrder.orEmpty().flatMap { link ->
    val href = link.href.toString().substringBefore('#')
    val chapter = publication?.locatorFromLink(link)
    val count = resourcePageCounts[href] ?: 0
    when {
      chapter == null -> emptyList()
      count <= 0 -> listOf(overviewPage(chapter, href, 0, 0, UNMEASURED_KEY_PREFIX + href))
      else -> List(count) { index ->
        val locator = chapter.atProgression(index.toDouble() / count)
        overviewPage(locator, href, index, count, keyOf(PageKey(href, index, count)))
      }
    }
  }
}

private fun EpubReaderActivity.overviewPage(
  locator: Locator,
  href: String,
  index: Int,
  count: Int,
  cacheKey: String
) = EpubPagePreview(locator, cacheKey, index, href, count, viewModel.chapterTitleFor(locator))

private fun Locator.atProgression(progression: Double) =
  copy(locations = locations.copy(progression = progression))

private suspend fun EpubReaderActivity.fillOverviewPages(pages: List<EpubPagePreview>, generation: Long) {
  val missing = withContext(Dispatchers.IO) {
    pages.filterNot { it.totalPages > 0 && previewCache.contains(it.cacheKey) }
  }
  val fragment = missing.firstOrNull()?.let { ensurePreviewNavigator(it.locator) }
  if (fragment != null) {
    val here = previewPageKey.value?.resourceHref
    // Finish the chapter the preview navigator is in before paying for a chapter change.
    for (page in missing.sortedBy { it.resourceHref != here }) {
      if (generation != previewGeneration) return
      if (page.totalPages <= 0) {
        measureOverviewChapter(fragment, page)
      } else {
        captureOverviewPage(fragment, page, generation)
      }
    }
  }
}

/** Opens a chapter once so its page count is known, which then turns its placeholder into pages. */
private suspend fun EpubReaderActivity.measureOverviewChapter(
  fragment: EpubNavigatorFragment,
  chapter: EpubPagePreview
) {
  settlePreviewPageKey()
  fragment.go(chapter.locator)
  withTimeoutOrNull(EpubReaderActivity.PAGE_PREVIEW_TURN_TIMEOUT_MS) {
    previewPageKey.first { it?.resourceHref == chapter.resourceHref && it.totalPages > 0 }
  }
  overviewPages.value = buildOverviewSkeleton()
  val scope = previewLayoutScope()
  val counts = resourcePageCounts.toMap()
  withContext(Dispatchers.IO) { previewCache.savePageCounts(scope, counts) }
}

private suspend fun EpubReaderActivity.captureOverviewPage(
  fragment: EpubNavigatorFragment,
  page: EpubPagePreview,
  generation: Long
) {
  val key = PageKey(page.resourceHref, page.pageIndex, page.totalPages)
  val startedAt = SystemClock.elapsedRealtime()
  val direct = captureInChapter(fragment, page, key)
  val captured = direct ?: captureByNavigation(fragment, page, key, generation)
  if (captured != null) {
    overviewCaptured.tryEmit(page.cacheKey)
    logOverviewCapture(page, direct != null, startedAt)
  }
}

/**
 * Readium reports a page change 100 ms after it happens, so turning the page and waiting for the
 * report costs that wait on every thumbnail. Within one chapter the page is scrolled directly
 * instead, which is only safe once the WebView has painted it.
 */
private suspend fun EpubReaderActivity.captureInChapter(
  fragment: EpubNavigatorFragment,
  page: EpubPagePreview,
  key: PageKey
): EpubPagePreview? {
  val shown = previewPageKey.value
  val sameChapter = shown != null && shown.resourceHref == key.resourceHref && shown.totalPages == key.totalPages
  val book = (viewModel.state.value as? EpubReaderUiState.Ready)?.book
  val webView = if (sameChapter && book?.rtl == false) fragment.visibleWebView() else null
  return webView?.let { captureAfterDirectScroll(fragment, it, page, key) }
}

private suspend fun EpubReaderActivity.captureAfterDirectScroll(
  fragment: EpubNavigatorFragment,
  webView: WebView,
  page: EpubPagePreview,
  key: PageKey
): EpubPagePreview? {
  val scrolled = scrollToPage(webView, key.pageIndex)
  if (scrolled) overviewState.directKey = key
  val painted = scrolled && awaitVisualState(webView)
  return if (painted) capturePreview(fragment, key, locator = page.locator) else null
}

/**
 * Readium's paginated WebView class is internal to its module, but `setCurrentItem(page, smooth)`
 * is public in the bytecode. A failure here only means the page is reached by normal navigation.
 */
private fun scrollToPage(webView: WebView, pageIndex: Int): Boolean = runCatching {
  webView.javaClass.getMethod("setCurrentItem", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    .invoke(webView, pageIndex, false)
}.isSuccess

private suspend fun EpubReaderActivity.captureByNavigation(
  fragment: EpubNavigatorFragment,
  page: EpubPagePreview,
  key: PageKey,
  generation: Long
): EpubPagePreview? {
  settlePreviewPageKey()
  return if (navigatePreviewTo(fragment, page.locator, key, generation)) {
    capturePreview(fragment, key, locator = page.locator)
  } else {
    null
  }
}

/** Lets the delayed report of a direct scroll arrive, so the reported page can be trusted again. */
private suspend fun EpubReaderActivity.settlePreviewPageKey() {
  val direct = overviewState.directKey ?: return
  overviewState.directKey = null
  withTimeoutOrNull(EpubReaderActivity.OVERVIEW_SETTLE_TIMEOUT_MS) {
    previewPageKey.first { it?.samePage(direct) == true }
  }
}

/** The WebView showing the current page; the pager keeps its neighbours attached but off screen. */
private fun EpubNavigatorFragment.visibleWebView(): WebView? {
  val webViews = mutableListOf<WebView>()
  publicationView.collectPagedWebViews(webViews)
  return webViews.firstOrNull { it.isShown && it.width > 0 && it.getGlobalVisibleRect(Rect()) }
}

private fun View.collectPagedWebViews(into: MutableList<WebView>) {
  if (this is WebView && javaClass.name == PAGED_WEB_VIEW_CLASS) {
    into += this
  } else if (this is ViewGroup) {
    for (index in 0 until childCount) getChildAt(index).collectPagedWebViews(into)
  }
}

/** Waits for a thumbnail the worker has not produced yet, reading it from the cache when it has. */
internal suspend fun EpubReaderActivity.loadPreviewBitmap(preview: EpubPagePreview): Bitmap? =
  if (preview.totalPages <= 0) {
    null
  } else {
    coroutineScope {
      // Subscribe before reading the cache, so a capture that lands in between is not missed.
      val arrived = async(start = CoroutineStart.UNDISPATCHED) {
        overviewCaptured.first { it == preview.cacheKey }
      }
      val cached = withContext(Dispatchers.IO) { previewCache.get(preview.cacheKey) }
      if (cached != null) {
        arrived.cancel()
        cached
      } else {
        arrived.await()
        withContext(Dispatchers.IO) { previewCache.get(preview.cacheKey) }
      }
    }
  }

private fun EpubReaderActivity.logOverviewCapture(page: EpubPagePreview, direct: Boolean, startedAt: Long) {
  if (Log.isLoggable(EpubReaderActivity.PREVIEW_LOG_TAG, Log.DEBUG)) {
    val now = SystemClock.elapsedRealtime()
    Log.d(
      EpubReaderActivity.PREVIEW_LOG_TAG,
      "overview captured page=${page.pageIndex + 1}/${page.totalPages} direct=$direct ms=${now - startedAt}"
    )
    if (!overviewState.firstThumbnailLogged) {
      overviewState.firstThumbnailLogged = true
      Log.d(EpubReaderActivity.PREVIEW_LOG_TAG, "overview first thumbnail ms=${now - overviewState.startedAt}")
    }
  }
}

private const val UNMEASURED_KEY_PREFIX = "overview:unmeasured:"
private const val PAGED_WEB_VIEW_CLASS = "org.readium.r2.navigator.R2WebView"
