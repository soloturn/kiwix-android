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
import androidx.core.view.WindowInsetsCompat

/**
 * Pads [view] by the display cutout plus a fixed [marginPx] and keeps the bar insets from the
 * navigator, so the text doesn't re-paginate when the bars or overlay toggle. Before API 30 a
 * consumed result also starves the sibling Compose chrome of insets, so it is passed through.
 */
internal fun applyReadingInsets(
  view: View,
  insets: WindowInsetsCompat,
  marginPx: Int,
  sdk: Int = Build.VERSION.SDK_INT
): WindowInsetsCompat {
  val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
  view.setPadding(cutout.left, cutout.top + marginPx, cutout.right, cutout.bottom + marginPx)
  return if (sdk >= Build.VERSION_CODES.R) WindowInsetsCompat.CONSUMED else insets
}
