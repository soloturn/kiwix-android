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

@file:OptIn(ExperimentalReadiumApi::class)

package org.kiwix.kiwixmobile.core.epub.reader

import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

/** User-adjustable reading settings; the theme is not stored, it follows the app's night mode. */
data class EpubReaderSettings(
  val fontScale: Double = DEFAULT_FONT_SCALE,
  val pageMargins: Double = DEFAULT_PAGE_MARGINS
) {
  fun withFontScale(value: Double) =
    copy(fontScale = value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE).rounded())

  fun withPageMargins(value: Double) =
    copy(pageMargins = value.coerceIn(MIN_PAGE_MARGINS, MAX_PAGE_MARGINS).rounded())

  fun largerFont() = withFontScale(fontScale + FONT_STEP)

  fun smallerFont() = withFontScale(fontScale - FONT_STEP)

  fun widerMargins() = withPageMargins(pageMargins + MARGIN_STEP)

  fun narrowerMargins() = withPageMargins(pageMargins - MARGIN_STEP)

  val canIncreaseFont get() = fontScale < MAX_FONT_SCALE
  val canDecreaseFont get() = fontScale > MIN_FONT_SCALE
  val canWidenMargins get() = pageMargins < MAX_PAGE_MARGINS
  val canNarrowMargins get() = pageMargins > MIN_PAGE_MARGINS

  /** Paginated (never scrolling) preferences for the Readium navigator. */
  fun toPreferences(darkTheme: Boolean) = EpubPreferences(
    fontSize = fontScale,
    pageMargins = pageMargins,
    theme = if (darkTheme) Theme.DARK else Theme.LIGHT,
    scroll = false
  )

  private fun Double.rounded() = Math.round(this * ROUNDING) / ROUNDING

  companion object {
    private const val ROUNDING = 100.0
    const val DEFAULT_FONT_SCALE = 1.0
    const val MIN_FONT_SCALE = 0.5
    const val MAX_FONT_SCALE = 3.0
    const val FONT_STEP = 0.1
    const val DEFAULT_PAGE_MARGINS = 1.0
    const val MIN_PAGE_MARGINS = 0.0
    const val MAX_PAGE_MARGINS = 3.0
    const val MARGIN_STEP = 0.25

    /** Clamps stored values, which may come from an older or hand-edited store. */
    fun of(fontScale: Double?, pageMargins: Double?) = EpubReaderSettings()
      .withFontScale(fontScale ?: DEFAULT_FONT_SCALE)
      .withPageMargins(pageMargins ?: DEFAULT_PAGE_MARGINS)
  }
}
