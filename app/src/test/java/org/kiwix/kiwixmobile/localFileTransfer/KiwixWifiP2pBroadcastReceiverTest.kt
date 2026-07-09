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

package org.kiwix.kiwixmobile.localFileTransfer

import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.localFileTransfer.KiwixWifiP2pBroadcastReceiver.P2pEventListener
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], application = TestApplication::class)
class KiwixWifiP2pBroadcastReceiverTest {
  private val p2pEventListener: P2pEventListener = mockk(relaxed = true)
  private lateinit var receiver: KiwixWifiP2pBroadcastReceiver
  private val context = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    receiver = KiwixWifiP2pBroadcastReceiver(p2pEventListener)
  }

  @Test
  fun onReceive_whenWifiP2pEnabled_notifiesListener() {
    val intent = Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION).apply {
      putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_ENABLED)
    }
    receiver.onReceive(context, intent)
    verify(exactly = 1) { p2pEventListener.onWifiP2pStateChanged(true) }
  }

  @Test
  fun onReceive_whenWifiP2pDisabled_notifiesListener() {
    val intent = Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION).apply {
      putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED)
    }

    receiver.onReceive(context, intent)

    verify(exactly = 1) {
      p2pEventListener.onWifiP2pStateChanged(false)
    }
  }

  @Test
  fun onReceive_whenPeersChanged_notifiesListener() {
    val intent = Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
    receiver.onReceive(context, intent)
    verify(exactly = 1) { p2pEventListener.onPeersChanged() }
  }

  @Test
  fun onReceive_whenConnected_notifiesListener() {
    val networkInfo = mockk<NetworkInfo>()
    every { networkInfo.isConnected } returns true

    val intent = Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply {
      putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, networkInfo)
    }

    receiver.onReceive(context, intent)

    verify(exactly = 1) {
      p2pEventListener.onConnectionChanged(true)
    }
  }

  @Test
  fun onReceive_whenDisconnected_notifiesListener() {
    val networkInfo = mockk<NetworkInfo>()
    every { networkInfo.isConnected } returns false

    val intent = Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply {
      putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, networkInfo)
    }

    receiver.onReceive(context, intent)

    verify(exactly = 1) {
      p2pEventListener.onConnectionChanged(false)
    }
  }

  @Test
  fun onReceive_whenNetworkInfoMissing_doesNotNotifyListener() {
    val intent = Intent(WIFI_P2P_CONNECTION_CHANGED_ACTION)

    // Here we don't pass the Network Info intent i.e- null
    receiver.onReceive(context, intent)

    verify(exactly = 0) {
      p2pEventListener.onConnectionChanged(any())
    }
  }

  @Test
  fun onReceive_whenDeviceChanged_notifiesListener() {
    val device = WifiP2pDevice()

    val intent = Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION).apply {
      putExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, device)
    }

    receiver.onReceive(context, intent)

    verify(exactly = 1) {
      p2pEventListener.onDeviceChanged(device)
    }
  }

  @Test
  fun onReceive_whenDeviceMissing_notifiesListenerWithNull() {
    val intent = Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

    // Here we don't pass the Device intent i.e- null
    receiver.onReceive(context, intent)

    verify(exactly = 1) {
      p2pEventListener.onDeviceChanged(null)
    }
  }
}
