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

import android.content.Intent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubExternalLinksTest {
  @Test
  fun `web and mail links become browsable view intents`() {
    listOf("http://a.org/x", "HTTPS://a.org/x?y=1", "mailto:me@a.org", " https://a.org ").forEach {
      val intent = EpubExternalLinks.intentFor(it)
      assertNotNull(it, intent)
      assertEquals(Intent.ACTION_VIEW, intent!!.action)
      assertEquals(true, intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
    assertEquals("https://a.org", EpubExternalLinks.intentFor(" https://a.org ")!!.data.toString())
  }

  @Test
  fun `every other scheme is refused`() {
    listOf(
      "javascript:alert(1)",
      "file:///sdcard/a.txt",
      "content://x/y",
      "intent://x#Intent;scheme=a;end",
      "tel:123",
      "sms:123",
      "market://details?id=a",
      "data:text/html,hi",
      "//a.org/x",
      "no-scheme"
    ).forEach { assertNull(it, EpubExternalLinks.intentFor(it)) }
  }
}
