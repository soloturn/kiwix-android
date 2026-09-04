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

package org.kiwix.kiwixmobile.webserver

import android.app.Application
import org.kiwix.kiwixmobile.webserver.wifi_hotspot.HotspotService.Companion.ACTION_START_SERVER
import org.kiwix.kiwixmobile.webserver.wifi_hotspot.HotspotService.Companion.ACTION_STOP_SERVER
import javax.inject.Inject

/**
 * Talks to [org.kiwix.kiwixmobile.webserver.wifi_hotspot.HotspotService] directly via
 * `Context.startService()`, instead of through [ZimHostViewModel]'s `events` `SharedFlow`.
 *
 * `events` only has a collector while the ZimHost screen is `STARTED` (see
 * `ZimHostRoute.kt`), which is fine for user-driven actions (button taps only happen
 * while that screen is visible) but wrong for a server restart triggered by a ZIM being
 * deleted from the *Library* screen: at that moment nothing is collecting `events`, so an
 * emission into it is silently dropped and the running server never learns a hosted book
 * disappeared (#5081 review round 2 - the server kept serving deleted books after the
 * first fix). `Application.startService()` has no such UI-lifecycle dependency, so this
 * reaches the service regardless of which screen is currently visible.
 */
class HotspotServiceCommander @Inject constructor(
  private val context: Application
) {
  fun startServer(paths: ArrayList<String>, restart: Boolean) {
    startHotspotService(context, ACTION_START_SERVER) {
      putStringArrayListExtra(SELECTED_ZIM_PATHS_KEY, paths)
      putExtra(RESTART_SERVER, restart)
    }
  }

  fun stopServer() {
    startHotspotService(context, ACTION_STOP_SERVER)
  }
}
