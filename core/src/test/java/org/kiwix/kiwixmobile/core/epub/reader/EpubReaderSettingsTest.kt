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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

class EpubReaderSettingsTest {
  @Test
  fun `font steps are clamped and free of float drift`() {
    var settings = EpubReaderSettings()
    repeat(3) { settings = settings.largerFont() }
    assertEquals(1.3, settings.fontScale, 0.0)
    repeat(100) { settings = settings.largerFont() }
    assertEquals(EpubReaderSettings.MAX_FONT_SCALE, settings.fontScale, 0.0)
    assertFalse(settings.canIncreaseFont)
    repeat(100) { settings = settings.smallerFont() }
    assertEquals(EpubReaderSettings.MIN_FONT_SCALE, settings.fontScale, 0.0)
    assertFalse(settings.canDecreaseFont)
    assertTrue(settings.canIncreaseFont)
  }

  @Test
  fun `margin steps are clamped`() {
    var settings = EpubReaderSettings()
    repeat(100) { settings = settings.widerMargins() }
    assertEquals(EpubReaderSettings.MAX_PAGE_MARGINS, settings.pageMargins, 0.0)
    assertFalse(settings.canWidenMargins)
    repeat(100) { settings = settings.narrowerMargins() }
    assertEquals(EpubReaderSettings.MIN_PAGE_MARGINS, settings.pageMargins, 0.0)
    assertFalse(settings.canNarrowMargins)
  }

  @Test
  fun `stored values are clamped and defaulted`() {
    assertEquals(EpubReaderSettings(), EpubReaderSettings.of(null, null))
    val wild = EpubReaderSettings.of(fontScale = 99.0, pageMargins = -4.0)
    assertEquals(EpubReaderSettings.MAX_FONT_SCALE, wild.fontScale, 0.0)
    assertEquals(EpubReaderSettings.MIN_PAGE_MARGINS, wild.pageMargins, 0.0)
  }

  @Test
  fun `preferences are paginated and follow the night mode`() {
    val settings = EpubReaderSettings(fontScale = 1.4, pageMargins = 0.5)
    val light = settings.toPreferences(darkTheme = false)
    val dark = settings.toPreferences(darkTheme = true)
    assertEquals(Theme.LIGHT, light.theme)
    assertEquals(Theme.DARK, dark.theme)
    assertEquals(false, light.scroll)
    assertEquals(1.4, light.fontSize)
    assertEquals(0.5, light.pageMargins)
  }
}
