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

package org.kiwix.kiwixmobile.core.data

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.kiwixmobile.core.R
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class BackupRulesTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val expectedIncludes = setOf("file", "database", "sharedpref")

  @Test
  fun legacyBackupIncludesInternalUserDataOnly() {
    assertEquals(expectedIncludes, includedDomains(R.xml.backup_rules, null))
  }

  @Test
  fun cloudBackupIncludesInternalUserDataOnly() {
    assertEquals(expectedIncludes, includedDomains(R.xml.data_extraction_rules, "cloud-backup"))
  }

  private fun includedDomains(xmlRes: Int, section: String?): Set<String> {
    val parser = context.resources.getXml(xmlRes)
    val domains = mutableSetOf<String>()
    var inSection = section == null
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      when {
        parser.eventType == XmlPullParser.START_TAG && parser.name == section -> inSection = true
        parser.eventType == XmlPullParser.END_TAG && parser.name == section -> inSection = false
        parser.eventType == XmlPullParser.START_TAG && parser.name == "include" && inSection ->
          domains += parser.getAttributeValue(null, "domain")
      }
    }
    return domains
  }
}
