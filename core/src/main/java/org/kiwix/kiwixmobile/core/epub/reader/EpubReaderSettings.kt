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

import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

/** Reading colour schemes; [BLACK] is [Theme.DARK] on a pure black page. */
enum class EpubThemePreset(val readiumTheme: Theme, val backgroundColor: Int) {
  WHITE(Theme.LIGHT, Theme.LIGHT.backgroundColor),
  SEPIA(Theme.SEPIA, Theme.SEPIA.backgroundColor),
  DARK(Theme.DARK, Theme.DARK.backgroundColor),
  BLACK(Theme.DARK, PURE_BLACK)
}

/** Only fonts that need no bundled files: the book's own, or the system's generic families. */
enum class EpubFontChoice { PUBLISHER, SERIF, SANS_SERIF, MONOSPACE }

enum class EpubAlignment { LEFT, JUSTIFY }

private const val PURE_BLACK = 0xFF000000.toInt()

/**
 * User-adjustable reading settings. A null [themePreset] follows the app's night mode;
 * line spacing and alignment only apply when [publisherStyles] is off.
 */
data class EpubReaderSettings(
  val fontScale: Double = DEFAULT_FONT_SCALE,
  val pageMargins: Double = DEFAULT_PAGE_MARGINS,
  val lineSpacing: Double = DEFAULT_LINE_SPACING,
  val fontChoice: EpubFontChoice = EpubFontChoice.PUBLISHER,
  val alignment: EpubAlignment = EpubAlignment.LEFT,
  val themePreset: EpubThemePreset? = null,
  val scroll: Boolean = false,
  val publisherStyles: Boolean = true
) {
  fun withFontScale(value: Double) =
    copy(fontScale = value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE).rounded())

  fun withPageMargins(value: Double) =
    copy(pageMargins = value.coerceIn(MIN_PAGE_MARGINS, MAX_PAGE_MARGINS).rounded())

  fun withLineSpacing(value: Double) =
    copy(lineSpacing = value.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING).rounded())

  fun largerFont() = withFontScale(fontScale + FONT_STEP)

  fun smallerFont() = withFontScale(fontScale - FONT_STEP)

  fun widerMargins() = withPageMargins(pageMargins + MARGIN_STEP)

  fun narrowerMargins() = withPageMargins(pageMargins - MARGIN_STEP)

  val canIncreaseFont get() = fontScale < MAX_FONT_SCALE
  val canDecreaseFont get() = fontScale > MIN_FONT_SCALE
  val canWidenMargins get() = pageMargins < MAX_PAGE_MARGINS
  val canNarrowMargins get() = pageMargins > MIN_PAGE_MARGINS

  /** The preset actually shown: the chosen one, else white or dark by [nightMode]. */
  fun effectivePreset(nightMode: Boolean) =
    themePreset ?: if (nightMode) EpubThemePreset.DARK else EpubThemePreset.WHITE

  fun backgroundColor(nightMode: Boolean) = effectivePreset(nightMode).backgroundColor

  fun toPreferences(nightMode: Boolean): EpubPreferences {
    val preset = effectivePreset(nightMode)
    return EpubPreferences(
      fontSize = fontScale,
      pageMargins = pageMargins,
      theme = preset.readiumTheme,
      backgroundColor = if (preset == EpubThemePreset.BLACK) Color(PURE_BLACK) else null,
      fontFamily = fontChoice.readiumFamily(),
      publisherStyles = publisherStyles,
      lineHeight = lineSpacing.takeUnless { publisherStyles },
      textAlign = alignment.readiumAlign().takeUnless { publisherStyles },
      scroll = scroll
    )
  }

  fun toJson(): String = JSONObject()
    .put(FONT_SCALE, fontScale)
    .put(PAGE_MARGINS, pageMargins)
    .put(LINE_SPACING, lineSpacing)
    .put(FONT_CHOICE, fontChoice.name)
    .put(ALIGNMENT, alignment.name)
    .put(THEME, themePreset?.name)
    .put(SCROLL, scroll)
    .put(PUBLISHER_STYLES, publisherStyles)
    .toString()

  private fun Double.rounded() = Math.round(this * ROUNDING) / ROUNDING

  private fun EpubFontChoice.readiumFamily(): FontFamily? = when (this) {
    EpubFontChoice.PUBLISHER -> null
    EpubFontChoice.SERIF -> FontFamily.SERIF
    EpubFontChoice.SANS_SERIF -> FontFamily.SANS_SERIF
    EpubFontChoice.MONOSPACE -> FontFamily.MONOSPACE
  }

  private fun EpubAlignment.readiumAlign() = when (this) {
    EpubAlignment.LEFT -> TextAlign.LEFT
    EpubAlignment.JUSTIFY -> TextAlign.JUSTIFY
  }

  companion object {
    private const val ROUNDING = 100.0
    const val DEFAULT_FONT_SCALE = 1.0
    const val MIN_FONT_SCALE = 0.5
    const val MAX_FONT_SCALE = 3.0
    const val DEFAULT_PAGE_MARGINS = 1.0
    const val FONT_STEP = 0.1
    const val MARGIN_STEP = 0.25
    const val MIN_PAGE_MARGINS = 0.0
    const val MAX_PAGE_MARGINS = 3.0
    const val DEFAULT_LINE_SPACING = 1.4
    const val MIN_LINE_SPACING = 1.0
    const val MAX_LINE_SPACING = 2.0
    private const val FONT_SCALE = "fontScale"
    private const val PAGE_MARGINS = "pageMargins"
    private const val LINE_SPACING = "lineSpacing"
    private const val FONT_CHOICE = "fontChoice"
    private const val ALIGNMENT = "alignment"
    private const val THEME = "theme"
    private const val SCROLL = "scroll"
    private const val PUBLISHER_STYLES = "publisherStyles"

    /** Defaults for missing, clamped for out-of-range, and all-defaults for corrupt input. */
    fun fromJson(json: String?): EpubReaderSettings {
      val obj = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return EpubReaderSettings()
      val defaults = EpubReaderSettings()
      return defaults
        .withFontScale(obj.optDouble(FONT_SCALE, DEFAULT_FONT_SCALE))
        .withPageMargins(obj.optDouble(PAGE_MARGINS, DEFAULT_PAGE_MARGINS))
        .withLineSpacing(obj.optDouble(LINE_SPACING, DEFAULT_LINE_SPACING))
        .copy(
          fontChoice = enumOrNull<EpubFontChoice>(obj, FONT_CHOICE) ?: defaults.fontChoice,
          alignment = enumOrNull<EpubAlignment>(obj, ALIGNMENT) ?: defaults.alignment,
          themePreset = enumOrNull<EpubThemePreset>(obj, THEME),
          scroll = obj.optBoolean(SCROLL, defaults.scroll),
          publisherStyles = obj.optBoolean(PUBLISHER_STYLES, defaults.publisherStyles)
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(obj: JSONObject, key: String): T? =
      enumValues<T>().firstOrNull { it.name == obj.optString(key) }
  }
}
