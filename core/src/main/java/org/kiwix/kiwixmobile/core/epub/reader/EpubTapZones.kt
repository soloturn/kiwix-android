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

/** Where on the page a tap landed, in reading order (so the "previous" edge flips for RTL). */
enum class TapZone { PREVIOUS_PAGE, NEXT_PAGE, CENTER }

enum class TapAction { PREVIOUS_PAGE, NEXT_PAGE, SHOW_OVERLAY, HIDE_OVERLAY }

object EpubTapZones {
  /** Each side zone covers this share of the width. */
  const val EDGE_FRACTION = 0.25f

  fun classify(x: Float, width: Float, rightToLeft: Boolean): TapZone {
    if (width <= 0f) return TapZone.CENTER
    val fromLeft = when {
      x < width * EDGE_FRACTION -> TapZone.PREVIOUS_PAGE
      x > width * (1f - EDGE_FRACTION) -> TapZone.NEXT_PAGE
      else -> TapZone.CENTER
    }
    return when {
      !rightToLeft || fromLeft == TapZone.CENTER -> fromLeft
      fromLeft == TapZone.PREVIOUS_PAGE -> TapZone.NEXT_PAGE
      else -> TapZone.PREVIOUS_PAGE
    }
  }

  /** Edges always turn the page; the middle toggles the overlay. */
  fun actionFor(zone: TapZone, overlayVisible: Boolean) = when {
    zone == TapZone.PREVIOUS_PAGE -> TapAction.PREVIOUS_PAGE
    zone == TapZone.NEXT_PAGE -> TapAction.NEXT_PAGE
    overlayVisible -> TapAction.HIDE_OVERLAY
    else -> TapAction.SHOW_OVERLAY
  }

  /** Any tap on the page dismisses a visible overlay, so an edge tap turns the page and hides it. */
  fun dismissesOverlay(action: TapAction, overlayVisible: Boolean) =
    overlayVisible && action != TapAction.SHOW_OVERLAY
}
