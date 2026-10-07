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

import android.os.Build
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubReaderInsetsTest {
  private val view = View(ApplicationProvider.getApplicationContext())
  private val insets = WindowInsetsCompat.Builder()
    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 100, 0, 50))
    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(10, 20, 30, 40))
    .build()

  @Test
  fun `padding is the cutout plus the margin, never the bars`() {
    applyReadingInsets(view, insets, marginPx = 8)

    val padding = listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    assertEquals(listOf(10, 28, 30, 48), padding)
  }

  @Test
  fun `the navigator container consumes insets where siblings still get their own`() {
    val result = applyReadingInsets(view, insets, 8, sdk = Build.VERSION_CODES.R)
    assertSame(WindowInsetsCompat.CONSUMED, result)
  }

  @Test
  fun `older systems pass the insets on so the Compose chrome still receives them`() {
    val result = applyReadingInsets(view, insets, 8, sdk = Build.VERSION_CODES.Q)
    assertSame(insets, result)
  }
}
