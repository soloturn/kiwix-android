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

package org.kiwix.kiwixmobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.epub.EpubOnDisk
import org.kiwix.kiwixmobile.core.ui.theme.KiwixTheme
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.BOOK_ICON_SIZE
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.EIGHT_DP
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.FIVE_DP
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.FOUR_DP
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.SIXTEEN_DP
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.TWO_DP
import org.kiwix.kiwixmobile.core.zim_manager.Byte
import org.kiwix.kiwixmobile.core.zim_manager.fileselect_view.SelectionMode
import org.kiwix.kiwixmobile.ui.EpubBookItemScreen.EPUB_BADGE_TESTING_TAG
import org.kiwix.kiwixmobile.ui.EpubBookItemScreen.EPUB_ITEM_CHECKBOX_TESTING_TAG
import org.kiwix.kiwixmobile.ui.EpubBookItemScreen.EPUB_ITEM_TESTING_TAG
import java.io.File

/** A local library row for an EPUB: cover, title, author, size and an "EPUB" badge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpubBookItem(
  index: Int,
  epub: EpubOnDisk,
  selectionMode: SelectionMode = SelectionMode.NORMAL,
  onClick: ((EpubOnDisk) -> Unit)? = null,
  onLongClick: ((EpubOnDisk) -> Unit)? = null,
  onMultiSelect: ((EpubOnDisk) -> Unit)? = null
) {
  KiwixTheme {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(FIVE_DP)
        .combinedClickable(
          onClick = {
            when (selectionMode) {
              SelectionMode.MULTI -> onMultiSelect?.invoke(epub)
              SelectionMode.NORMAL -> onClick?.invoke(epub)
            }
          },
          onLongClick = {
            if (selectionMode == SelectionMode.NORMAL) onLongClick?.invoke(epub)
          }
        ).testTag(EPUB_ITEM_TESTING_TAG),
      shape = MaterialTheme.shapes.extraSmall,
      elevation = CardDefaults.elevatedCardElevation(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
      Row(
        modifier = Modifier
          .padding(top = SIXTEEN_DP, start = SIXTEEN_DP, bottom = SIXTEEN_DP)
          .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        if (selectionMode == SelectionMode.MULTI) {
          Checkbox(
            checked = epub.isSelected,
            onCheckedChange = { onMultiSelect?.invoke(epub) },
            modifier = Modifier
              .testTag("$EPUB_ITEM_CHECKBOX_TESTING_TAG$index")
              .semantics { contentDescription = "${epub.isSelected}$index" }
          )
        }
        EpubCover(epub)
        EpubDetails(Modifier.weight(1f), epub, index)
      }
    }
  }
}

@Composable
private fun EpubCover(epub: EpubOnDisk) {
  AsyncImage(
    model = epub.coverPath?.let { File(it) },
    contentDescription = epub.title,
    modifier = Modifier.size(BOOK_ICON_SIZE),
    contentScale = ContentScale.Fit,
    placeholder = painterResource(R.drawable.default_zim_file_icon),
    error = painterResource(R.drawable.default_zim_file_icon),
    fallback = painterResource(R.drawable.default_zim_file_icon)
  )
}

@Composable
private fun EpubDetails(modifier: Modifier, epub: EpubOnDisk, index: Int) {
  Column(modifier = modifier.padding(start = SIXTEEN_DP, end = SIXTEEN_DP)) {
    Text(
      text = epub.title,
      style = MaterialTheme.typography.titleSmall,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.semantics { contentDescription = "${epub.title}$index" }
    )
    if (epub.authors.isNotBlank()) {
      Spacer(modifier = Modifier.padding(top = TWO_DP))
      Text(
        text = epub.authors,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(top = FIVE_DP)
    ) {
      EpubBadge()
      if (epub.size > 0) {
        Spacer(modifier = Modifier.width(EIGHT_DP))
        Text(
          text = Byte(epub.size.toString()).humanReadable,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onTertiary
        )
      }
      if (epub.language.isNotBlank()) {
        Spacer(modifier = Modifier.width(EIGHT_DP))
        Text(
          text = epub.language,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onTertiary
        )
      }
    }
  }
}

@Composable
private fun EpubBadge() {
  Surface(
    shape = RoundedCornerShape(FOUR_DP),
    color = MaterialTheme.colorScheme.primaryContainer
  ) {
    Text(
      text = stringResource(R.string.epub_badge),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier
        .padding(horizontal = FIVE_DP, vertical = TWO_DP)
        .testTag(EPUB_BADGE_TESTING_TAG)
    )
  }
}

object EpubBookItemScreen {
  const val EPUB_ITEM_TESTING_TAG = "epubItemTestingTag"
  const val EPUB_ITEM_CHECKBOX_TESTING_TAG = "epubItemCheckboxTestingTag"
  const val EPUB_BADGE_TESTING_TAG = "epubBadgeTestingTag"
}
