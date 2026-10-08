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

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubReaderSettingsTest {
  @Test
  fun `sliders are clamped and free of float drift`() {
    val settings = EpubReaderSettings()
    assertEquals(1.3, settings.withFontScale(1.0 + 0.1 + 0.1 + 0.1).fontScale, 0.0)
    assertEquals(EpubReaderSettings.MAX_FONT_SCALE, settings.withFontScale(99.0).fontScale, 0.0)
    assertEquals(EpubReaderSettings.MIN_FONT_SCALE, settings.withFontScale(-1.0).fontScale, 0.0)
    assertEquals(EpubReaderSettings.MAX_PAGE_MARGINS, settings.withPageMargins(9.0).pageMargins, 0.0)
    assertEquals(EpubReaderSettings.MIN_PAGE_MARGINS, settings.withPageMargins(-9.0).pageMargins, 0.0)
    assertEquals(EpubReaderSettings.MAX_LINE_SPACING, settings.withLineSpacing(9.0).lineSpacing, 0.0)
    assertEquals(EpubReaderSettings.MIN_LINE_SPACING, settings.withLineSpacing(0.0).lineSpacing, 0.0)
  }

  @Test
  fun `stored values are clamped and defaulted`() {
    assertEquals(EpubReaderSettings(), EpubReaderSettings.fromJson(null))
    assertEquals(EpubReaderSettings(), EpubReaderSettings.fromJson("{garbage"))
    val wild = EpubReaderSettings.fromJson(
      """{"fontScale":99,"pageMargins":-4,"lineSpacing":9,"fontChoice":"COMIC","theme":"NEON"}"""
    )
    assertEquals(EpubReaderSettings.MAX_FONT_SCALE, wild.fontScale, 0.0)
    assertEquals(EpubReaderSettings.MIN_PAGE_MARGINS, wild.pageMargins, 0.0)
    assertEquals(EpubReaderSettings.MAX_LINE_SPACING, wild.lineSpacing, 0.0)
    assertEquals(EpubFontChoice.PUBLISHER, wild.fontChoice)
    assertNull(wild.themePreset)
  }

  @Test
  fun `settings round-trip through json`() {
    val settings = EpubReaderSettings(
      fontScale = 1.4,
      pageMargins = 0.5,
      lineSpacing = 1.8,
      fontChoice = EpubFontChoice.SERIF,
      alignment = EpubAlignment.JUSTIFY,
      themePreset = EpubThemePreset.SEPIA,
      scroll = true,
      publisherStyles = false
    )
    assertEquals(settings, EpubReaderSettings.fromJson(settings.toJson()))
    assertEquals(EpubReaderSettings(), EpubReaderSettings.fromJson(EpubReaderSettings().toJson()))
  }

  @Test
  fun `the theme follows the night mode until a preset is chosen`() {
    val auto = EpubReaderSettings()
    assertEquals(Theme.LIGHT, auto.toPreferences(nightMode = false).theme)
    assertEquals(Theme.DARK, auto.toPreferences(nightMode = true).theme)
    assertEquals(EpubThemePreset.DARK, auto.effectivePreset(nightMode = true))
    val sepia = auto.copy(themePreset = EpubThemePreset.SEPIA)
    assertEquals(Theme.SEPIA, sepia.toPreferences(nightMode = true).theme)
    val white = auto.copy(themePreset = EpubThemePreset.WHITE)
    assertEquals(Theme.LIGHT, white.toPreferences(nightMode = true).theme)
  }

  @Test
  fun `black is dark on a pure black page and others keep the theme background`() {
    val black = EpubReaderSettings(themePreset = EpubThemePreset.BLACK)
    val prefs = black.toPreferences(nightMode = false)
    assertEquals(Theme.DARK, prefs.theme)
    assertEquals(Color(0xFF000000.toInt()), prefs.backgroundColor)
    assertEquals(0xFF000000.toInt(), black.backgroundColor(nightMode = false))
    val dark = EpubReaderSettings(themePreset = EpubThemePreset.DARK)
    assertEquals(Color(0xFF1C1C1E.toInt()), dark.toPreferences(true).backgroundColor)
    assertNull(EpubReaderSettings(themePreset = EpubThemePreset.WHITE).toPreferences(true).backgroundColor)
    assertEquals(Theme.SEPIA.backgroundColor, EpubReaderSettings(themePreset = EpubThemePreset.SEPIA).backgroundColor(false))
  }

  @Test
  fun `preferences carry size, margins, scroll and font`() {
    val prefs = EpubReaderSettings(
      fontScale = 1.4,
      pageMargins = 0.5,
      fontChoice = EpubFontChoice.SANS_SERIF,
      scroll = true
    ).toPreferences(nightMode = false)
    assertEquals(1.4, prefs.fontSize)
    assertEquals(0.5, prefs.pageMargins)
    assertEquals(true, prefs.scroll)
    assertEquals(FontFamily.SANS_SERIF, prefs.fontFamily)
    assertNull(EpubReaderSettings().toPreferences(false).fontFamily)
    assertEquals(false, EpubReaderSettings().toPreferences(false).scroll)
  }

  @Test
  fun `line spacing and alignment only apply when the book styles are off`() {
    val on = EpubReaderSettings(lineSpacing = 1.8, alignment = EpubAlignment.JUSTIFY)
    assertEquals(true, on.toPreferences(false).publisherStyles)
    assertNull(on.toPreferences(false).lineHeight)
    assertNull(on.toPreferences(false).textAlign)
    val off = on.copy(publisherStyles = false).toPreferences(false)
    assertEquals(false, off.publisherStyles)
    assertEquals(1.8, off.lineHeight)
    assertEquals(TextAlign.JUSTIFY, off.textAlign)
  }
}
