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
import androidx.core.net.toUri

/** Which links of a book may leave the app. A book is untrusted content. */
object EpubExternalLinks {
  private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")

  /** A view intent for [url], or null unless it is a web or mail link. */
  fun intentFor(url: String): Intent? {
    val uri = url.trim().toUri()
    if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) return null
    return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
  }
}
