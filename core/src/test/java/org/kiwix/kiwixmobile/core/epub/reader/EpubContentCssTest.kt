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

import org.junit.jupiter.api.Assertions.assertArrayEquals
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

  private fun transform(bytes: ByteArray) = String(EpubContentCss.transform(bytes), Charsets.UTF_8)

  @Test
  fun `utf-8 content, with or without a declaration or BOM, gets the rules and keeps its text`() {
    val html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><head></head><body>caf\u00e9 \u4e2d</body></html>"
    assertTrue(transform(html.toByteArray()).contains(EpubContentCss.CSS))
    assertTrue(transform(html.toByteArray()).contains("caf\u00e9 \u4e2d"))
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + html.toByteArray()
    assertTrue(transform(bom).contains(EpubContentCss.CSS))
  }

  @Test
  fun `content in another encoding passes through byte for byte`() {
    val latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html><head></head><body>caf\u00e9</body></html>"
      .toByteArray(Charsets.ISO_8859_1)
    assertArrayEquals(latin1, EpubContentCss.transform(latin1))

    val meta = "<html><head><meta charset=\"windows-1252\"></head><body>caf\u00e9</body></html>"
      .toByteArray(Charsets.ISO_8859_1)
    assertArrayEquals(meta, EpubContentCss.transform(meta))
  }

  @Test
  fun `utf-16 and invalid utf-8 pass through untouched`() {
    val utf16 = "<html><head></head><body>x</body></html>".toByteArray(Charsets.UTF_16)
    assertArrayEquals(utf16, EpubContentCss.transform(utf16))
    val utf16le = "<html><head></head></html>".toByteArray(Charsets.UTF_16LE)
    assertArrayEquals(utf16le, EpubContentCss.transform(utf16le))

    val invalid = "<html><head></head><body>".toByteArray() + byteArrayOf(0xE9.toByte()) + "</body></html>".toByteArray()
    assertArrayEquals(invalid, EpubContentCss.transform(invalid))
  }

  @Test
  fun `css covers media, pre and tables`() {
    listOf("img", "pre{", "table{", "overflow-wrap:anywhere").forEach {
      assertTrue(EpubContentCss.CSS.contains(it), it)
    }
  }
}
