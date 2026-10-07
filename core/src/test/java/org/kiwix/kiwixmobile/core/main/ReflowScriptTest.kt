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
package org.kiwix.kiwixmobile.core.main

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReflowScriptTest {
  @Test
  fun `css fits media, wraps pre and body text`() {
    assertThat(ReflowScript.css)
      .contains("img,video,svg,canvas,iframe{max-width:100%;height:auto}")
      .contains("pre{white-space:pre-wrap;overflow-wrap:anywhere}")
      .contains("body{overflow-wrap:break-word}")
      .contains("overflow-x:auto")
  }

  @Test
  fun `css sets no colours so night mode is respected`() {
    assertThat(ReflowScript.css).doesNotContain("color").doesNotContain("background")
  }

  @Test
  fun `script is guarded by an idempotence flag`() {
    val js = ReflowScript.build()
    assertThat(js).contains("if(window.${ReflowScript.FLAG})return;")
    assertThat(js).contains(ReflowScript.css)
  }

  @Test
  fun `script adds viewport meta only when none exists`() {
    val js = ReflowScript.build()
    assertThat(js).contains("if(!d.querySelector('meta[name=viewport]'))")
    assertThat(js).contains(ReflowScript.VIEWPORT_CONTENT)
  }

  @Test
  fun `script does not touch text nodes`() {
    assertThat(ReflowScript.build())
      .doesNotContain("innerText")
      .doesNotContain("textContent=d")
      .doesNotContain("innerHTML")
  }

  @Test
  fun `script has no unresolved template placeholders and single-quote safe css`() {
    assertThat(ReflowScript.build()).doesNotContain("$")
    assertThat(ReflowScript.css).doesNotContain("'")
  }
}
