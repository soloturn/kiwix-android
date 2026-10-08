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
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
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
import org.readium.r2.navigator.preferences.ReadingProgression
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
  private val pageReady = MutableStateFlow(false)
  private var navigator: EpubNavigatorFragment? = null
  private lateinit var root: FrameLayout
  private lateinit var navigatorContainer: FragmentContainerView

  override fun onCreate(savedInstanceState: Bundle?) {
    // The navigator fragment needs a factory built from the opened book, so it can't be restored.
    super.onCreate(null)
    darkTheme.value = isNightMode(resources.configuration)
    navigatorContainer = FragmentContainerView(this).apply { id = View.generateViewId() }
    // Immersive: the book fills the screen and the bars and overlay float above it. Only the
    // (constant) display cutout and fixed margins are reserved, never the system bars, so the
    // text doesn't re-paginate when the bars or overlay toggle.
    ViewCompat.setOnApplyWindowInsetsListener(navigatorContainer) { view, insets ->
      val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
      val margin = (READING_MARGIN_DP * resources.displayMetrics.density).toInt()
      view.setPadding(cutout.left, cutout.top + margin, cutout.right, cutout.bottom + margin)
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
    setUpImmersiveMode()
    lifecycleScope.launch {
      val ready = viewModel.state.filterIsInstance<EpubReaderUiState.Ready>().first()
      attachNavigator(ready.book)
    }
    lifecycleScope.launch {
      combine(viewModel.settings, darkTheme, ::Pair).collect { (settings, dark) ->
        root.setBackgroundColor(settings.backgroundColor(dark))
        navigator?.submitPreferences(settings.toPreferences(dark))
      }
    }
  }

  /** System bars show with the overlay (and while loading), otherwise a swipe reveals them. */
  private fun setUpImmersiveMode() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }
    val controller = WindowCompat.getInsetsController(window, root)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    lifecycleScope.launch {
      combine(viewModel.state, viewModel.chromeVisible) { state, chrome ->
        chrome || state !is EpubReaderUiState.Ready
      }.collect { showBars ->
        if (showBars) {
          controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
          controller.hide(WindowInsetsCompat.Type.systemBars())
        }
      }
    }
  }

  @Composable
  private fun Chrome() {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val locator by viewModel.currentLocator.collectAsState()
    val chromeVisible by viewModel.chromeVisible.collectAsState()
    val positions by viewModel.positions.collectAsState()
    val bookShown by pageReady.collectAsState()
    val readingOrder = (state as? EpubReaderUiState.Ready)?.book?.publication?.readingOrder.orEmpty()
    EpubReaderScreen(
      state = state,
      settings = settings,
      reading = EpubReadingState(
        locator = locator,
        positionCount = positions.size,
        hasPreviousChapter = locator?.let { adjacentChapter(readingOrder, it, next = false) } != null,
        hasNextChapter = locator?.let { adjacentChapter(readingOrder, it, next = true) } != null
      ),
      chromeVisible = chromeVisible,
      pageReady = bookShown,
      actions = EpubReaderActions(
        onBack = { onBackPressedDispatcher.onBackPressed() },
        onTocItem = { navigator?.go(it.link) },
        onPreviousChapter = { goToAdjacentChapter(next = false) },
        onNextChapter = { goToAdjacentChapter(next = true) },
        onSeek = { fraction -> seekTarget(positions, fraction)?.let { navigator?.go(it) } },
        onSettings = viewModel::changeSettings
      )
    )
  }

  private fun attachNavigator(book: OpenEpub) {
    val factory = EpubNavigatorFactory(book.publication).createFragmentFactory(
      initialLocator = book.initialLocator,
      initialPreferences = viewModel.settings.value.toPreferences(darkTheme.value),
      listener = linkListener,
      paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageLoaded() = revealBook()
      },
      // The container's padding already keeps the book clear of the cutout.
      configuration = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false)
    )
    supportFragmentManager.fragmentFactory = factory
    supportFragmentManager.commitNow {
      replace(navigatorContainer.id, EpubNavigatorFragment::class.java, null, NAVIGATOR_TAG)
    }
    val fragment = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
      ?: return
    navigator = fragment
    // Hidden, not gone, so it still lays out; a failed load must not leave the screen stuck.
    navigatorContainer.alpha = 0f
    lifecycleScope.launch {
      delay(REVEAL_TIMEOUT_MS)
      revealBook()
    }
    fragment.addInputListener(
      object : InputListener {
        override fun onTap(event: TapEvent) = handleTap(fragment, event)
      }
    )
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        fragment.currentLocator.collect(viewModel::onLocatorChanged)
      }
    }
  }

  private fun revealBook() {
    navigatorContainer.alpha = 1f
    pageReady.value = true
  }

  /** Links never reach here; Readium follows them itself. Scrolling books have no edge zones. */
  private fun handleTap(fragment: EpubNavigatorFragment, event: TapEvent): Boolean {
    val overflow = fragment.overflow.value
    val zone = if (overflow.scroll) {
      TapZone.CENTER
    } else {
      EpubTapZones.classify(
        event.point.x,
        fragment.publicationView.width.toFloat(),
        overflow.readingProgression == ReadingProgression.RTL
      )
    }
    when (EpubTapZones.actionFor(zone, viewModel.chromeVisible.value)) {
      TapAction.PREVIOUS_PAGE -> fragment.goBackward(animated = true)
      TapAction.NEXT_PAGE -> fragment.goForward(animated = true)
      TapAction.SHOW_OVERLAY -> viewModel.setChromeVisible(true)
      TapAction.HIDE_OVERLAY -> viewModel.setChromeVisible(false)
    }
    return true
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
    private const val REVEAL_TIMEOUT_MS = 5_000L

    // Constant top and bottom margin around the page, so the overlay toggling never reflows it.
    private const val READING_MARGIN_DP = 32

    fun intent(context: Context, file: File) =
      Intent(context, EpubReaderActivity::class.java)
        .putExtra(EpubReaderViewModel.EXTRA_PATH, file.path)
  }
}
