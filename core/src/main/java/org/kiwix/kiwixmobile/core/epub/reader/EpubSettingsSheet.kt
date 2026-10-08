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

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.kiwix.kiwixmobile.core.R
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.EIGHT_DP
import org.kiwix.kiwixmobile.core.utils.ComposeDimens.SIXTEEN_DP
import kotlin.math.roundToInt

const val EPUB_SETTINGS_FONT_SIZE_TESTING_TAG = "epubSettingsFontSizeTestingTag"
const val EPUB_SETTINGS_MARGINS_TESTING_TAG = "epubSettingsMarginsTestingTag"
const val EPUB_SETTINGS_LINE_SPACING_TESTING_TAG = "epubSettingsLineSpacingTestingTag"
const val EPUB_SETTINGS_SCROLL_TESTING_TAG = "epubSettingsScrollTestingTag"
const val EPUB_SETTINGS_PAGED_TESTING_TAG = "epubSettingsPagedTestingTag"
const val EPUB_SETTINGS_BOOK_STYLES_TESTING_TAG = "epubSettingsBookStylesTestingTag"
const val EPUB_SETTINGS_THEME_TESTING_TAG_PREFIX = "epubSettingsTheme"
const val EPUB_SETTINGS_FONT_TESTING_TAG_PREFIX = "epubSettingsFont"
const val EPUB_SETTINGS_ALIGN_TESTING_TAG_PREFIX = "epubSettingsAlign"

private const val PERCENT = 100
private const val SWATCH_SIZE_DP = 52
private const val FONT_STEPS = 24
private const val MARGIN_STEPS = 11
private const val LINE_SPACING_STEPS = 9
private const val SELECTED_BORDER_DP = 3
private const val IDLE_BORDER_DP = 1

/**
 * The "Aa" sheet. It has no scrim so the page behind it shows each change as it is made;
 * every control calls [onSettings] with a transform of the current settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EpubSettingsSheet(
  settings: EpubReaderSettings,
  onSettings: ((EpubReaderSettings) -> EpubReaderSettings) -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    scrimColor = Color.Transparent
  ) {
    Column(
      Modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = SIXTEEN_DP)
        .padding(bottom = SIXTEEN_DP),
      verticalArrangement = Arrangement.spacedBy(EIGHT_DP)
    ) {
      ThemeRow(settings, onSettings)
      SliderRow(
        label = R.string.epub_text_size,
        valueText = "${(settings.fontScale * PERCENT).roundToInt()}%",
        value = settings.fontScale,
        range = EpubReaderSettings.MIN_FONT_SCALE..EpubReaderSettings.MAX_FONT_SCALE,
        steps = FONT_STEPS,
        tag = EPUB_SETTINGS_FONT_SIZE_TESTING_TAG
      ) { value -> onSettings { it.withFontScale(value) } }
      ChoiceRow(
        label = R.string.epub_font,
        choices = EpubFontChoice.entries.map { it to it.labelRes() },
        selected = settings.fontChoice,
        tagPrefix = EPUB_SETTINGS_FONT_TESTING_TAG_PREFIX
      ) { choice -> onSettings { it.copy(fontChoice = choice) } }
      LayoutRow(settings, onSettings)
      SliderRow(
        label = R.string.epub_page_margins,
        valueText = "${(settings.pageMargins * PERCENT).roundToInt()}%",
        value = settings.pageMargins,
        range = EpubReaderSettings.MIN_PAGE_MARGINS..EpubReaderSettings.MAX_PAGE_MARGINS,
        steps = MARGIN_STEPS,
        tag = EPUB_SETTINGS_MARGINS_TESTING_TAG
      ) { value -> onSettings { it.withPageMargins(value) } }
      BookStyleSettings(settings, onSettings)
    }
  }
}

/** The switch for the book's own styles and the controls it disables. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookStyleSettings(
  settings: EpubReaderSettings,
  onSettings: ((EpubReaderSettings) -> EpubReaderSettings) -> Unit
) {
  Column(verticalArrangement = Arrangement.spacedBy(EIGHT_DP)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        stringResource(R.string.epub_book_formatting),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.weight(1f)
      )
      Switch(
        checked = settings.publisherStyles,
        onCheckedChange = { checked -> onSettings { it.copy(publisherStyles = checked) } },
        modifier = Modifier.testTag(EPUB_SETTINGS_BOOK_STYLES_TESTING_TAG)
      )
    }
    SliderRow(
      label = R.string.epub_line_spacing,
      valueText = "%.1f".format(settings.lineSpacing),
      value = settings.lineSpacing,
      range = EpubReaderSettings.MIN_LINE_SPACING..EpubReaderSettings.MAX_LINE_SPACING,
      steps = LINE_SPACING_STEPS,
      tag = EPUB_SETTINGS_LINE_SPACING_TESTING_TAG,
      enabled = !settings.publisherStyles
    ) { value -> onSettings { it.withLineSpacing(value) } }
    ChoiceRow(
      label = R.string.epub_alignment,
      choices = EpubAlignment.entries.map { it to it.labelRes() },
      selected = settings.alignment,
      tagPrefix = EPUB_SETTINGS_ALIGN_TESTING_TAG_PREFIX,
      enabled = !settings.publisherStyles
    ) { choice -> onSettings { it.copy(alignment = choice) } }
  }
}

@Composable
private fun ThemeRow(
  settings: EpubReaderSettings,
  onSettings: ((EpubReaderSettings) -> EpubReaderSettings) -> Unit
) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically
  ) {
    ThemeSwatch(
      label = stringResource(R.string.epub_theme_auto),
      background = MaterialTheme.colorScheme.surfaceVariant,
      content = MaterialTheme.colorScheme.onSurfaceVariant,
      selected = settings.themePreset == null,
      tag = "${EPUB_SETTINGS_THEME_TESTING_TAG_PREFIX}AUTO"
    ) { onSettings { it.copy(themePreset = null) } }
    EpubThemePreset.entries.forEach { preset ->
      ThemeSwatch(
        label = stringResource(preset.labelRes()),
        background = Color(preset.backgroundColor),
        content = Color(preset.readiumTheme.contentColor),
        selected = settings.themePreset == preset,
        tag = "$EPUB_SETTINGS_THEME_TESTING_TAG_PREFIX${preset.name}"
      ) { onSettings { it.copy(themePreset = preset) } }
    }
  }
}

@Composable
private fun ThemeSwatch(
  label: String,
  background: Color,
  content: Color,
  selected: Boolean,
  tag: String,
  onClick: () -> Unit
) {
  val border = if (selected) {
    BorderStroke(SELECTED_BORDER_DP.dp, MaterialTheme.colorScheme.primary)
  } else {
    BorderStroke(IDLE_BORDER_DP.dp, MaterialTheme.colorScheme.outline)
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier
        .size(SWATCH_SIZE_DP.dp)
        .clip(CircleShape)
        .background(background)
        .border(border, CircleShape)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label }
        .testTag(tag),
      contentAlignment = Alignment.Center
    ) {
      Text("Aa", color = content, style = MaterialTheme.typography.titleMedium)
    }
    Text(label, style = MaterialTheme.typography.labelSmall)
  }
}

@Composable
private fun SliderRow(
  @StringRes label: Int,
  valueText: String,
  value: Double,
  range: ClosedFloatingPointRange<Double>,
  steps: Int,
  tag: String,
  enabled: Boolean = true,
  onValue: (Double) -> Unit
) {
  Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
      Text(valueText, style = MaterialTheme.typography.labelLarge)
    }
    Slider(
      value = value.toFloat(),
      onValueChange = { onValue(it.toDouble()) },
      valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
      steps = steps,
      enabled = enabled,
      modifier = Modifier.testTag(tag)
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
  @StringRes label: Int,
  choices: List<Pair<T, Int>>,
  selected: T,
  tagPrefix: String,
  enabled: Boolean = true,
  onChoice: (T) -> Unit
) {
  Column {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(EIGHT_DP)) {
      choices.forEach { (choice, name) ->
        FilterChip(
          selected = choice == selected,
          onClick = { onChoice(choice) },
          enabled = enabled,
          label = { Text(stringResource(name)) },
          modifier = Modifier.testTag("$tagPrefix$choice")
        )
      }
    }
  }
}

@Composable
private fun LayoutRow(
  settings: EpubReaderSettings,
  onSettings: ((EpubReaderSettings) -> EpubReaderSettings) -> Unit
) {
  Column {
    Text(stringResource(R.string.epub_layout), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(EIGHT_DP)) {
      FilterChip(
        selected = !settings.scroll,
        onClick = { onSettings { it.copy(scroll = false) } },
        label = { Text(stringResource(R.string.epub_paginated)) },
        modifier = Modifier.testTag(EPUB_SETTINGS_PAGED_TESTING_TAG)
      )
      FilterChip(
        selected = settings.scroll,
        onClick = { onSettings { it.copy(scroll = true) } },
        label = { Text(stringResource(R.string.epub_scroll)) },
        modifier = Modifier.testTag(EPUB_SETTINGS_SCROLL_TESTING_TAG)
      )
    }
  }
}

@StringRes
private fun EpubThemePreset.labelRes() = when (this) {
  EpubThemePreset.WHITE -> R.string.epub_theme_white
  EpubThemePreset.SEPIA -> R.string.epub_theme_sepia
  EpubThemePreset.DARK -> R.string.epub_theme_dark
  EpubThemePreset.BLACK -> R.string.epub_theme_black
}

@StringRes
private fun EpubFontChoice.labelRes() = when (this) {
  EpubFontChoice.PUBLISHER -> R.string.epub_font_publisher
  EpubFontChoice.SERIF -> R.string.epub_font_serif
  EpubFontChoice.SANS_SERIF -> R.string.epub_font_sans_serif
  EpubFontChoice.MONOSPACE -> R.string.epub_font_monospace
}

@StringRes
private fun EpubAlignment.labelRes() = when (this) {
  EpubAlignment.LEFT -> R.string.epub_align_left
  EpubAlignment.JUSTIFY -> R.string.epub_align_justify
}
