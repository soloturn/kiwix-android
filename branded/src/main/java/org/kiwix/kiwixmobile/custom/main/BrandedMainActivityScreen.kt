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

package org.kiwix.kiwixmobile.custom.main

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import org.kiwix.kiwixmobile.core.base.BackPressActivityExtensions
import org.kiwix.kiwixmobile.core.ui.theme.KiwixTheme

@Composable
fun BrandedMainActivityScreen(
  navController: NavHostController,
  customBackHandler: MutableState<(() -> BackPressActivityExtensions.Super)?>
) {
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route
  OnUserBackPressed(
    currentRoute,
    navController,
    customBackHandler
  )
  KiwixTheme {
    Scaffold(
      modifier = Modifier
        .fillMaxSize()
        .systemBarsPadding()
    ) { paddingValues ->
      Box(modifier = Modifier.padding(paddingValues)) {
        BrandedNavGraph(
          navController = navController,
          modifier = Modifier.fillMaxSize()
        )
      }
    }
  }
}

@Composable
private fun OnUserBackPressed(
  currentRoute: String?,
  navController: NavHostController,
  customBackHandler: MutableState<(() -> BackPressActivityExtensions.Super)?>,
) {
  val activity = LocalActivity.current
  PredictiveBackHandler(enabled = true) { progress ->
    try {
      progress.collect { }
      when {
        customBackHandler.value?.invoke() == BackPressActivityExtensions.Super.ShouldNotCall -> {
          // do nothing since compose screen handles the back press.
        }

        currentRoute == CustomDestination.Reader.route &&
          navController.previousBackStackEntry?.destination?.route != CustomDestination.Search.route -> {
          activity?.finish()
        }

        else -> {
          val popped = navController.popBackStack()
          if (!popped) {
            activity?.finish()
          }
        }
      }
    } catch (e: CancellationException) {
      // Gesture was cancelled mid-swipe; nothing to do.
    }
  }
}
