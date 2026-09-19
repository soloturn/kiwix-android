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

package org.kiwix.kiwixmobile.core.main.reader.helper

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.kiwix.kiwixmobile.core.main.MainRepositoryActions
import org.kiwix.kiwixmobile.core.page.history.models.HistoryListItem
import org.kiwix.kiwixmobile.core.reader.ZimReaderSource
import org.kiwix.kiwixmobile.core.utils.LanguageUtils.Companion.getCurrentLocale
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

class ReaderHistoryManager @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val mainRepositoryActions: MainRepositoryActions
) {
  suspend fun saveHistory(
    url: String?,
    title: String?,
    zimId: String?,
    zimName: String?,
    zimReaderSource: ZimReaderSource?,
    favicon: String?
  ) {
    if (url == null || title == null) return
    if (zimId == null || zimName == null) return

    val timestamp = System.currentTimeMillis()
    val history = HistoryListItem.HistoryItem(
      databaseId = 0L,
      zimId = zimId,
      zimName = zimName,
      zimReaderSource = zimReaderSource,
      favicon = favicon,
      historyUrl = url,
      title = title,
      dateString = formatDate(timestamp),
      timeStamp = timestamp
    )

    mainRepositoryActions.saveHistory(history)
  }

  private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy", getCurrentLocale(context)).format(Date(timestamp))
}
