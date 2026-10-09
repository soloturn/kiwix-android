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
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.data.EmptyContainer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class EpubInjectLayoutTest {
  private fun builder(layout: Layout?) = Publication.Builder(
    Manifest(metadata = Metadata(localizedTitle = LocalizedString("t"), layout = layout)),
    EmptyContainer()
  )

  @Test
  fun `every layout, fixed included, gets the connection policy`() {
    listOf(Layout.FIXED, Layout.REFLOWABLE, Layout.SCROLLED, null).forEach { layout ->
      val builder = builder(layout)
      val original = builder.container

      builder.injectContentCss()

      assertNotSame("$layout", original, builder.container)
    }
  }
}
