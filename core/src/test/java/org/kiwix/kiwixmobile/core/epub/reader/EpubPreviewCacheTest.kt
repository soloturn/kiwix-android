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
 */

package org.kiwix.kiwixmobile.core.epub.reader

import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubPreviewCacheTest {
  @Test
  fun `stores rgb565 webp and reloads it from disk`() {
    val root = File(
      ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
      "epub-preview-cache-test-${System.nanoTime()}"
    )
    val original = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888)
    original.eraseColor(android.graphics.Color.WHITE)

    try {
      EpubPreviewCache(root).put("book/page/1", original)

      assertTrue(original.isRecycled)
      val files = File(root, "epub-previews-v1").listFiles().orEmpty()
      assertEquals(1, files.size)
      assertEquals("webp", files.single().extension)
      assertEquals(Bitmap.Config.RGB_565, EpubPreviewCache(root).get("book/page/1")?.config)
    } finally {
      root.deleteRecursively()
    }
  }
}
