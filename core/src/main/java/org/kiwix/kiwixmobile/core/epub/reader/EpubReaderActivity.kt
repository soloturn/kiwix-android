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

package org.kiwix.kiwixmobile.core.epub.reader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.kiwix.kiwixmobile.core.base.BaseActivity
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File

/**
 * Reads one EPUB with Readium's paginated navigator. Launch it with [intent]; the book's
 * position and the reading settings are restored from, and saved to, the app's data store.
 */
@AndroidEntryPoint
@OptIn(ExperimentalReadiumApi::class)
class EpubReaderActivity : BaseActivity() {
  private val viewModel: EpubReaderViewModel by viewModels()
  private val darkTheme = MutableStateFlow(false)
  private var navigator: EpubNavigatorFragment? = null
  private lateinit var root: FrameLayout
  private lateinit var navigatorContainer: FragmentContainerView

  override fun onCreate(savedInstanceState: Bundle?) {
    // The navigator fragment needs a factory built from the opened book, so it can't be restored.
    super.onCreate(null)
    darkTheme.value = isNightMode(resources.configuration)
    navigatorContainer = FragmentContainerView(this).apply { id = View.generateViewId() }
    // Edge-to-edge: keep the book clear of the system bars.
    ViewCompat.setOnApplyWindowInsetsListener(navigatorContainer) { view, insets ->
      val bars = insets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
      )
      // Reserve the top bar's height so text never sits under it; a fixed band avoids
      // re-paginating when the bar toggles.
      val barHeight = (APP_BAR_HEIGHT_DP * resources.displayMetrics.density).toInt()
      view.setPadding(bars.left, bars.top + barHeight, bars.right, bars.bottom)
      insets
    }
    root = FrameLayout(this).apply {
      addView(navigatorContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
      addView(
        ComposeView(context).apply { setContent { Chrome() } },
        FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
      )
    }
    setContentView(root)
    lifecycleScope.launch {
      val ready = viewModel.state.filterIsInstance<EpubReaderUiState.Ready>().first()
      attachNavigator(ready.book)
    }
    lifecycleScope.launch {
      combine(viewModel.settings, darkTheme, ::Pair).collect { (settings, dark) ->
        root.setBackgroundColor(if (dark) Theme.DARK.backgroundColor else Theme.LIGHT.backgroundColor)
        navigator?.submitPreferences(settings.toPreferences(dark))
      }
    }
  }

  @Composable
  private fun Chrome() {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val locator by viewModel.currentLocator.collectAsState()
    val chromeVisible by viewModel.chromeVisible.collectAsState()
    val readingOrder = (state as? EpubReaderUiState.Ready)?.book?.publication?.readingOrder.orEmpty()
    EpubReaderScreen(
      state = state,
      settings = settings,
      currentLocator = locator,
      chromeVisible = chromeVisible,
      hasPreviousChapter = locator?.let { adjacentChapter(readingOrder, it, next = false) } != null,
      hasNextChapter = locator?.let { adjacentChapter(readingOrder, it, next = true) } != null,
      actions = EpubReaderActions(
        onBack = { onBackPressedDispatcher.onBackPressed() },
        onTocItem = { navigator?.go(it.link) },
        onPreviousChapter = { goToAdjacentChapter(next = false) },
        onNextChapter = { goToAdjacentChapter(next = true) },
        onSettings = viewModel::changeSettings
      )
    )
  }

  private fun attachNavigator(book: OpenEpub) {
    val factory = EpubNavigatorFactory(book.publication).createFragmentFactory(
      initialLocator = book.initialLocator,
      initialPreferences = viewModel.settings.value.toPreferences(darkTheme.value),
      listener = linkListener
    )
    supportFragmentManager.fragmentFactory = factory
    supportFragmentManager.commitNow {
      replace(navigatorContainer.id, EpubNavigatorFragment::class.java, null, NAVIGATOR_TAG)
    }
    val fragment = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
      ?: return
    navigator = fragment
    fragment.addInputListener(DirectionalNavigationAdapter(fragment))
    // Taps the page-turn edges don't take show or hide the top bar.
    fragment.addInputListener(
      object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
          viewModel.toggleChrome()
          return true
        }
      }
    )
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        fragment.currentLocator.collect(viewModel::onLocatorChanged)
      }
    }
  }

  private fun goToAdjacentChapter(next: Boolean) {
    val book = (viewModel.state.value as? EpubReaderUiState.Ready)?.book ?: return
    val current = navigator?.currentLocator?.value ?: return
    adjacentChapter(book.publication.readingOrder, current, next)?.let { navigator?.go(it) }
  }

  private val linkListener = object : EpubNavigatorFragment.Listener {
    override fun onExternalLinkActivated(url: AbsoluteUrl) {
      runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri())) }
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    darkTheme.value = isNightMode(newConfig)
  }

  override fun onStop() {
    viewModel.persistPosition()
    super.onStop()
  }

  private fun isNightMode(configuration: Configuration) =
    configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

  companion object {
    private const val NAVIGATOR_TAG = "epubNavigator"

    // Material 3 small top app bar height.
    private const val APP_BAR_HEIGHT_DP = 64

    fun intent(context: Context, file: File) =
      Intent(context, EpubReaderActivity::class.java)
        .putExtra(EpubReaderViewModel.EXTRA_PATH, file.path)
  }
}
