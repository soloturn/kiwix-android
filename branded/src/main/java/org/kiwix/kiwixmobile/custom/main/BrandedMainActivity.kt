/*
 * Kiwix Android
 * Copyright (c) 2019 Kiwix <android.kiwix.org>
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

package org.kiwix.kiwixmobile.custom.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.kiwix.kiwixmobile.core.R.drawable
import org.kiwix.kiwixmobile.core.R.string
import org.kiwix.kiwixmobile.core.data.ObjectBoxDataMigrationHandler
import org.kiwix.kiwixmobile.core.extensions.browserIntent
import org.kiwix.kiwixmobile.core.main.ACTION_NEW_TAB
import org.kiwix.kiwixmobile.core.main.CoreMainActivity
import org.kiwix.kiwixmobile.core.main.DrawerMenuItem
import org.kiwix.kiwixmobile.core.main.LEFT_DRAWER_ABOUT_APP_ITEM_TESTING_TAG
import org.kiwix.kiwixmobile.core.main.LEFT_DRAWER_HELP_ITEM_TESTING_TAG
import org.kiwix.kiwixmobile.core.main.LEFT_DRAWER_SUPPORT_ITEM_TESTING_TAG
import org.kiwix.kiwixmobile.core.main.NEW_TAB_SHORTCUT_ID
import org.kiwix.kiwixmobile.core.main.SecondaryMenuItems
import org.kiwix.kiwixmobile.core.utils.dialog.DialogHost
import org.kiwix.kiwixmobile.custom.BuildConfig
import org.kiwix.kiwixmobile.custom.R
import javax.inject.Inject

@AndroidEntryPoint
class BrandedMainActivity : CoreMainActivity() {
  @Inject lateinit var objectBoxDataMigrationHandler: ObjectBoxDataMigrationHandler

  override val appName: String by lazy { getString(R.string.app_name) }

  override val searchScreenRoute: String = CustomDestination.Search.route
  override val settingsScreenRoute: String = CustomDestination.Settings.route
  override val savedScreenRoute: String = CustomDestination.Saved.route
  override val readerScreenRoute: String = CustomDestination.Reader.route
  override val helpScreenRoute: String = CustomDestination.Help.route
  override val topLevelDestinationsRoute = setOf(CustomDestination.Reader.route)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      snackBarHostState = remember { SnackbarHostState() }
      navController = rememberNavController()
      BrandedMainActivityScreen(
        navController = navController,
        customBackHandler = customBackHandler
      )
      DialogHost(alertDialogShower)
    }
    // run the migration on background thread to avoid any UI related issues.
    CoroutineScope(ioDispatcher).launch {
      objectBoxDataMigrationHandler.migrate()
    }
  }

  /**
   * Custom apps have no Wi-Fi hotspot (they read the ZIM from a file descriptor, which
   * KiwixServer cannot host — see kiwix/kiwix-android#4026), so `zimHost` stays null.
   * Help, Support and About are each optional per build config; Help and About surface
   * inside Settings, Support in the reader's overflow.
   */
  override val secondaryMenuItems: SecondaryMenuItems by lazy {
    SecondaryMenuItems(
      zimHost = null,
      help = if (BuildConfig.DISABLE_HELP_MENU) {
        null
      } else {
        DrawerMenuItem(
          title = getString(string.menu_help),
          iconRes = drawable.ic_help_24px,
          visible = true,
          onClick = { openHelpScreen() },
          testingTag = LEFT_DRAWER_HELP_ITEM_TESTING_TAG
        )
      },
      support = if (BuildConfig.SUPPORT_URL.isEmpty()) {
        null
      } else {
        DrawerMenuItem(
          title = getString(
            string.menu_support_kiwix_for_custom_apps,
            getString(R.string.app_name)
          ),
          iconRes = drawable.ic_support_24px,
          visible = true,
          onClick = {
            lifecycleScope.launch {
              externalLinkOpener.openExternalLinkWithDialog(
                intent = BuildConfig.SUPPORT_URL.toUri().browserIntent(),
                destinationText = getString(string.support_donation_platform)
              )
            }
          },
          testingTag = LEFT_DRAWER_SUPPORT_ITEM_TESTING_TAG
        )
      },
      about = if (BuildConfig.ABOUT_APP_URL.isEmpty()) {
        null
      } else {
        DrawerMenuItem(
          title = getString(
            string.menu_about_app,
            getString(R.string.app_name)
          ),
          iconRes = drawable.ic_baseline_info,
          visible = true,
          onClick = {
            lifecycleScope.launch {
              externalLinkOpener.openExternalLinkWithDialog(
                intent = BuildConfig.ABOUT_APP_URL.toUri().browserIntent(),
                destinationText = getString(string.about_app_page),
              )
            }
          },
          testingTag = LEFT_DRAWER_ABOUT_APP_ITEM_TESTING_TAG
        )
      }
    )
  }

  override suspend fun createApplicationShortcuts() {
    // Remove previously added dynamic shortcuts for old ids if any found.
    removeOutdatedIdShortcuts()
    // Create a shortcut for opening the "New tab"
    val newTabShortcut = ShortcutInfoCompat.Builder(this, NEW_TAB_SHORTCUT_ID)
      .setShortLabel(getString(string.new_tab_shortcut_label))
      .setLongLabel(getString(string.new_tab_shortcut_label))
      .setIcon(createShortcutIcon(drawable.ic_add_blue_24dp))
      .setDisabledMessage(getString(string.shortcut_disabled_message))
      .setIntent(
        Intent(this, BrandedMainActivity::class.java).apply {
          action = ACTION_NEW_TAB
        }
      )
      .build()
    ShortcutManagerCompat.addDynamicShortcuts(this, listOf(newTabShortcut))
  }

  override fun openSearch(searchString: String, isOpenedFromTabView: Boolean, isVoice: Boolean) {
    navigate(
      CustomDestination.Search.createRoute(
        searchString = searchString,
        isOpenedFromTabView = isOpenedFromTabView,
        isVoice = isVoice
      ),
      NavOptions.Builder().setPopUpTo(searchScreenRoute, inclusive = true).build()
    )
  }

  override fun hideBottomAppBar() {
    // Do nothing since custom apps does not have the bottomAppBar.
  }

  override fun showBottomAppBar() {
    // Do nothing since custom apps does not have the bottomAppBar.
  }

  // Outdated shortcut id(new_tab)
  // Remove if the application has the outdated shortcut.
  private fun removeOutdatedIdShortcuts() {
    ShortcutManagerCompat.getDynamicShortcuts(this).forEach {
      if (it.id == "new_tab") {
        ShortcutManagerCompat.removeDynamicShortcuts(this, arrayListOf(it.id))
      }
    }
  }

  override fun setAppName() {
    lifecycleScope.launch {
      kiwixDataStore.setAppName(getString(R.string.app_name))
    }
  }

  override fun setIsBrandedApp() {
    lifecycleScope.launch {
      kiwixDataStore.setIsBrandedApp(true)
    }
  }
}
