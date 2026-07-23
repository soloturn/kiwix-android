/*
 * Kiwix Android
 * Copyright (c) 2023 Kiwix <android.kiwix.org>
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

package org.kiwix.kiwixmobile.core.extensions

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.kiwix.kiwixmobile.core.utils.ZERO
import java.io.File

suspend fun File.isFileExist(ioDispatcher: CoroutineDispatcher): Boolean =
  withContext(ioDispatcher) { exists() }

suspend fun File.freeSpace(ioDispatcher: CoroutineDispatcher): Long =
  withContext(ioDispatcher) { freeSpace }

suspend fun File.totalSpace(ioDispatcher: CoroutineDispatcher): Long =
  withContext(ioDispatcher) { totalSpace }

suspend fun File.canReadFile(ioDispatcher: CoroutineDispatcher): Boolean =
  withContext(ioDispatcher) { canRead() }

suspend fun File.deleteFile(ioDispatcher: CoroutineDispatcher): Boolean =
  withContext(ioDispatcher) { delete() }

suspend fun File.hasContent(ioDispatcher: CoroutineDispatcher): Boolean =
  withContext(ioDispatcher) { exists() && length() > ZERO }
