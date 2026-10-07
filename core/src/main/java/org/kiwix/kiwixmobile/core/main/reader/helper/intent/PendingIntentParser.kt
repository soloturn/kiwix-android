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

package org.kiwix.kiwixmobile.core.main.reader.helper.intent

import android.content.Intent
import org.kiwix.kiwixmobile.core.main.CoreSearchWidget
import org.kiwix.kiwixmobile.core.main.ZIM_FILE_URI_KEY
import org.kiwix.kiwixmobile.core.main.ZIM_HOST_DEEP_LINK_SCHEME
import org.kiwix.kiwixmobile.core.main.reader.helper.intent.PendingIntentParser.ReaderIntentAction.None
import org.kiwix.kiwixmobile.core.main.reader.helper.intent.PendingIntentParser.ReaderIntentAction.OpenBookmarks
import org.kiwix.kiwixmobile.core.main.reader.helper.intent.PendingIntentParser.ReaderIntentAction.OpenEpub
import org.kiwix.kiwixmobile.core.main.reader.helper.intent.PendingIntentParser.ReaderIntentAction.OpenEpubContent
import org.kiwix.kiwixmobile.core.main.reader.helper.intent.PendingIntentParser.ReaderIntentAction.OpenSearch
import org.kiwix.kiwixmobile.core.utils.files.EPUB_MIME_TYPE
import org.kiwix.kiwixmobile.core.utils.files.isEpubFile
import javax.inject.Inject

class PendingIntentParser @Inject constructor() {
  sealed interface ReaderIntentAction {
    data class OpenSearch(
      val query: String,
      val isVoice: Boolean,
      val isOpenedFromTabView: Boolean
    ) : ReaderIntentAction

    data class OpenZim(val zimFileUri: String, val pageUrl: String) : ReaderIntentAction
    data class OpenEpub(val epubFilePath: String) : ReaderIntentAction

    /** A `content://` EPUB; must be copied to app-private storage before opening. */
    data class OpenEpubContent(val uri: String) : ReaderIntentAction
    data object OpenBookmarks : ReaderIntentAction
    data object None : ReaderIntentAction
  }

  fun parse(intent: Intent): ReaderIntentAction = when (intent.action) {
    Intent.ACTION_PROCESS_TEXT ->
      OpenSearch(
        query = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT).orEmpty(),
        isVoice = false,
        isOpenedFromTabView = false
      )

    CoreSearchWidget.TEXT_CLICKED -> OpenSearch("", isVoice = false, isOpenedFromTabView = false)

    CoreSearchWidget.MIC_CLICKED -> OpenSearch("", true, isOpenedFromTabView = false)

    CoreSearchWidget.STAR_CLICKED -> OpenBookmarks

    Intent.ACTION_VIEW -> parseActionViewIntent(intent)

    else -> None
  }

  /** True for an ACTION_VIEW of an EPUB, by MIME type or a `.epub` path. */
  fun isEpubViewIntent(intent: Intent): Boolean =
    intent.action == Intent.ACTION_VIEW &&
      (intent.type == EPUB_MIME_TYPE || intent.data?.path?.let(::isEpubFile) == true)

  @Suppress("ReturnCount")
  private fun parseActionViewIntent(intent: Intent): ReaderIntentAction {
    if (intent.hasExtra(ZIM_FILE_URI_KEY)) return None
    val scheme = intent.scheme
    if ((scheme == "file" || scheme == "content") && isEpubViewIntent(intent)) {
      if (scheme == "file") {
        intent.data?.path?.let { return OpenEpub(it) }
      } else {
        intent.data?.let { return OpenEpubContent(it.toString()) }
      }
    }
    val hasValidScheme =
      intent.scheme in listOf("file", "content", "zim", ZIM_HOST_DEEP_LINK_SCHEME)
    // Added condition to handle ZIM files. When opening from storage, the intent may
    // return null for the type, triggering the search unintentionally. This condition
    // prevents such occurrences.
    val isOctetStream = intent.type == null || intent.type == "application/octet-stream"

    if (isOctetStream || hasValidScheme) return None

    // An EPUB type must never fall through to search.
    if (intent.type == EPUB_MIME_TYPE) return None

    val searchString = if (intent.data == null) "" else intent.data?.lastPathSegment
    return OpenSearch(searchString.orEmpty(), false, isOpenedFromTabView = false)
  }
}
