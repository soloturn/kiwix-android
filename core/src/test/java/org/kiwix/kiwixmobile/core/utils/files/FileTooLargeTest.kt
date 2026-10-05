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

package org.kiwix.kiwixmobile.core.utils.files

import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kiwix.sharedFunctions.TestApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R], application = TestApplication::class)
class FileTooLargeTest {
  @Test
  fun recognisesEfbigInTheCauseChain() {
    val errno = ErrnoException("write", OsConstants.EFBIG)
    assertTrue(IOException("write failed", errno).isFileTooLarge())
    assertTrue(IOException("write failed: EFBIG (File too large)").isFileTooLarge())
  }

  @Test
  fun otherWriteErrorsAreNotTooLarge() {
    assertFalse(ErrnoException("write", OsConstants.ENOSPC).isFileTooLarge())
    assertFalse(IOException("No space left on device").isFileTooLarge())
  }
}
