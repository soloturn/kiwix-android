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

package org.kiwix.kiwixmobile.zimManager.fileselectView.effects

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.epub.reader.EpubReaderActivity
import org.kiwix.kiwixmobile.core.epub.reader.EpubReaderViewModel
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [Build.VERSION_CODES.R],
  manifest = Config.NONE,
  application = TestApplication::class
)
class OpenEpubInReaderTest {
  @Test
  fun `starts the reader screen for the file`() {
    val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
    val file = File("/storage/emulated/0/Books/book.epub")

    OpenEpubInReader(file).invokeWith(activity)

    val started = shadowOf(activity).nextStartedActivity
    assertEquals(EpubReaderActivity::class.java.name, started.component?.className)
    assertEquals(file.path, started.getStringExtra(EpubReaderViewModel.EXTRA_PATH))
    // The data URI makes each book its own document task.
    assertTrue(started.filterEquals(EpubReaderActivity.intent(activity, file)))
  }
}
