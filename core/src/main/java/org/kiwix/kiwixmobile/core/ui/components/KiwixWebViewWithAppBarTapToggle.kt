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
 */

package org.kiwix.kiwixmobile.core.ui.components

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.kiwix.kiwixmobile.core.main.KiwixWebView
import kotlin.math.abs

@Composable
fun KiwixWebViewWithAppBarTapToggle(
  kiwixWebView: KiwixWebView,
  onTap: () -> Unit
) {
  key(kiwixWebView) {
    DisposableEffect(kiwixWebView, onTap) {
      var downX = 0f
      var downY = 0f
      var downTime = 0L
      var touchStarted = false
      val touchSlop = ViewConfiguration.get(kiwixWebView.context).scaledTouchSlop
      val touchListener = View.OnTouchListener { view, event ->
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            downX = event.x
            downY = event.y
            downTime = event.eventTime
            touchStarted = true
          }
          MotionEvent.ACTION_UP -> {
            if (touchStarted && isTap(event, downX, downY, downTime, touchSlop)) {
              view.performClick()
              onTap()
            }
            touchStarted = false
          }
          MotionEvent.ACTION_CANCEL -> touchStarted = false
        }
        false
      }
      kiwixWebView.setOnTouchListener(touchListener)
      onDispose { kiwixWebView.setOnTouchListener(null) }
    }

    AndroidView(
      factory = { context -> FrameLayout(context) },
      update = { frameLayout ->
        if (kiwixWebView.parent != frameLayout) {
          (kiwixWebView.parent as? ViewGroup)?.removeView(kiwixWebView)
          frameLayout.removeAllViews()
          frameLayout.addView(
            kiwixWebView,
            FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            )
          )
        }
      },
      modifier = Modifier.fillMaxSize()
    )
  }
}

private fun isTap(
  event: MotionEvent,
  downX: Float,
  downY: Float,
  downTime: Long,
  touchSlop: Int
): Boolean {
  val isSingleFinger = event.pointerCount == 1
  val isShortTap = event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
  val stayedWithinTouchSlop =
    abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop
  return isSingleFinger && isShortTap && stayedWithinTouchSlop
}
