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

/**
 * Builds the script that makes ZIM pages fit the screen width. Idempotent, adds only a
 * style/viewport meta and table wrappers (no text changes), and sets no colours (night mode).
 */
object ReflowScript {
  const val FLAG = "__kiwixReflow"
  const val STYLE_ID = "kiwix-reflow-style"
  const val WRAP_CLASS = "kiwix-reflow-table"
  const val VIEWPORT_CONTENT = "width=device-width, initial-scale=1"

  val css: String = listOf(
    "img,video,svg,canvas,iframe{max-width:100%;height:auto}",
    "pre{white-space:pre-wrap;overflow-wrap:anywhere}",
    "body{overflow-wrap:break-word}",
    ".$WRAP_CLASS{display:block;max-width:100%;overflow-x:auto}"
  ).joinToString("")

  fun build(): String = """
    (function(){
      if(window.$FLAG)return;
      window.$FLAG=1;
      var d=document;
      var s=d.createElement('style');
      s.id='$STYLE_ID';
      s.textContent='$css';
      (d.head||d.documentElement).appendChild(s);
      function run(){
        if(!d.querySelector('meta[name=viewport]')){
          var m=d.createElement('meta');
          m.name='viewport';
          m.content='$VIEWPORT_CONTENT';
          (d.head||d.documentElement).appendChild(m);
        }
        var w=d.documentElement.clientWidth;
        var t=d.getElementsByTagName('table');
        for(var i=0;i<t.length;i++){
          var e=t[i],p=e.parentNode;
          if(e.offsetWidth>w&&p&&p.className!=='$WRAP_CLASS'){
            var c=d.createElement('div');
            c.className='$WRAP_CLASS';
            p.insertBefore(c,e);
            c.appendChild(e);
          }
        }
      }
      if(d.readyState==='loading')d.addEventListener('DOMContentLoaded',run);
      else run();
    })();
    """.trimIndent()
}
