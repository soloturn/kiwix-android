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

package org.kiwix.kiwixmobile.core.utils.files

import android.system.ErrnoException
import android.system.OsConstants

/**
 * Whether a write failed because the filesystem cannot hold a file that large (EFBIG, e.g.
 * FAT32 and its 4 GB limit). Folders picked through SAF cannot be inspected upfront, so this
 * is how the 4 GB limit surfaces for them.
 */
fun Throwable.isFileTooLarge(): Boolean =
  generateSequence(this) { it.cause }.any { error ->
    (error is ErrnoException && error.errno == OsConstants.EFBIG) ||
      error.message?.let { "EFBIG" in it || "File too large" in it } == true
  }
