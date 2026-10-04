/*
 * Kiwix Android
 * Copyright (c) 2025 Kiwix <android.kiwix.org>
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

package org.kiwix.kiwixmobile.core.main

import androidx.annotation.DrawableRes

data class DrawerMenuItem(
  val title: String,
  @param:DrawableRes val iconRes: Int,
  val visible: Boolean = true,
  val onClick: () -> Unit,
  val testingTag: String
)

/**
 * The secondary (non-primary-navigation) entry points a child activity provides.
 * Replaces four separate abstract properties on [CoreMainActivity] with one: the main
 * Kiwix app and custom/branded apps each decide which of these exist (and, for support/about,
 * what they say) by returning them here instead of overriding four members individually.
 */
data class SecondaryMenuItems(
  val zimHost: DrawerMenuItem? = null,
  val help: DrawerMenuItem? = null,
  val support: DrawerMenuItem? = null,
  val about: DrawerMenuItem? = null
)
