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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubTapZonesTest {
  private fun zone(x: Float, rtl: Boolean = false) = EpubTapZones.classify(x, 1000f, rtl)

  @Test
  fun `left quarter goes back, right quarter goes forward, the middle is centre`() {
    assertEquals(TapZone.PREVIOUS_PAGE, zone(0f))
    assertEquals(TapZone.PREVIOUS_PAGE, zone(249f))
    assertEquals(TapZone.CENTER, zone(250f))
    assertEquals(TapZone.CENTER, zone(500f))
    assertEquals(TapZone.CENTER, zone(750f))
    assertEquals(TapZone.NEXT_PAGE, zone(751f))
    assertEquals(TapZone.NEXT_PAGE, zone(1000f))
  }

  @Test
  fun `right to left flips the edges but not the centre`() {
    assertEquals(TapZone.NEXT_PAGE, zone(100f, rtl = true))
    assertEquals(TapZone.PREVIOUS_PAGE, zone(900f, rtl = true))
    assertEquals(TapZone.CENTER, zone(500f, rtl = true))
  }

  @Test
  fun `an unmeasured view counts as centre`() {
    assertEquals(TapZone.CENTER, EpubTapZones.classify(10f, 0f, rightToLeft = false))
  }

  @Test
  fun `with the overlay up every tap only hides it`() {
    TapZone.entries.forEach {
      assertEquals(TapAction.HIDE_OVERLAY, EpubTapZones.actionFor(it, overlayVisible = true))
    }
  }

  @Test
  fun `with the overlay down edges turn pages and the centre shows it`() {
    assertEquals(TapAction.PREVIOUS_PAGE, EpubTapZones.actionFor(TapZone.PREVIOUS_PAGE, false))
    assertEquals(TapAction.NEXT_PAGE, EpubTapZones.actionFor(TapZone.NEXT_PAGE, false))
    assertEquals(TapAction.SHOW_OVERLAY, EpubTapZones.actionFor(TapZone.CENTER, false))
  }
}
