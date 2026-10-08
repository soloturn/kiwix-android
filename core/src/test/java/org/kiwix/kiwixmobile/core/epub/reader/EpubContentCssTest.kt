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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubContentCssTest {
  @Test
  fun `css is injected as the last head child`() {
    val out = EpubContentCss.inject("<html><head><title>t</title></HEAD><body/></html>")

    assertTrue(out.contains("<title>t</title><style id=\"kiwix-epub-fit\">"))
    assertTrue(out.contains("${EpubContentCss.CSS}</style></HEAD>"))
  }

  @Test
  fun `css goes after the head start when there is no head end`() {
    val out = EpubContentCss.inject("<html><head lang=\"en\"><body/></html>")

    assertTrue(out.startsWith("<html><head lang=\"en\"><style"))
  }

  @Test
  fun `html without a head is unchanged`() {
    assertEquals("<p>x</p>", EpubContentCss.inject("<p>x</p>"))
  }

  @Test
  fun `css covers media, pre and tables`() {
    listOf("img", "pre{", "table{", "overflow-wrap:anywhere").forEach {
      assertTrue(EpubContentCss.CSS.contains(it), it)
    }
  }
}
