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

import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
internal fun EpubReaderActivity.handleTap(
  fragment: EpubNavigatorFragment,
  event: TapEvent
): Boolean {
  // Taps reach the book through the loading screen, which must not act on them.
  if (!pageReady.value) return true
  if (pageMode.value == EpubPageMode.CAROUSEL) {
    handleCarouselTap()
  } else {
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
      TapAction.SHOW_OVERLAY -> {
        viewModel.setChromeVisible(true)
        selectPageMode(EpubPageMode.CAROUSEL)
      }
      TapAction.HIDE_OVERLAY -> selectPageMode(EpubPageMode.SINGLE_PAGE)
    }
  }
  return true
}

internal fun EpubReaderActivity.handleCarouselTap() {
  if (carouselTapDetector.registerTap(SystemClock.uptimeMillis())) {
    cancelCarouselTap()
    viewModel.setChromeVisible(false)
    selectPageMode(EpubPageMode.OVERVIEW)
  } else {
    carouselTapJob?.cancel()
    carouselTapJob = lifecycleScope.launch {
      delay(carouselDoubleTapTimeout)
      carouselTapJob = null
      carouselTapDetector.reset()
      if (pageMode.value == EpubPageMode.CAROUSEL) {
        viewModel.setChromeVisible(false)
        selectPageMode(EpubPageMode.SINGLE_PAGE)
      }
    }
  }
}

internal fun EpubReaderActivity.cancelCarouselTap() {
  carouselTapJob?.cancel()
  carouselTapJob = null
  carouselTapDetector.reset()
}
