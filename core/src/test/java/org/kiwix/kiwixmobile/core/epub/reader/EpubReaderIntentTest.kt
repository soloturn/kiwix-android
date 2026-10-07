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

import android.content.pm.ActivityInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubReaderIntentTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun `the intent names the reader and carries the book path`() {
    val intent = EpubReaderActivity.intent(context, File("/books/a b.epub"))

    assertEquals(EpubReaderActivity::class.java.name, intent.component?.className)
    assertEquals("/books/a b.epub", intent.getStringExtra(EpubReaderViewModel.EXTRA_PATH))
  }

  @Test
  fun `the same book is the same document, another book is another`() {
    val first = EpubReaderActivity.intent(context, File("/books/a.epub"))

    assertTrue(first.filterEquals(EpubReaderActivity.intent(context, File("/books/a.epub"))))
    assertFalse(first.filterEquals(EpubReaderActivity.intent(context, File("/books/b.epub"))))
  }

  @Test
  fun `the manifest makes each book its own document task`() {
    val info = context.packageManager.getActivityInfo(
      android.content.ComponentName(context, EpubReaderActivity::class.java),
      0
    )

    assertEquals(ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING, info.documentLaunchMode)
  }
}
