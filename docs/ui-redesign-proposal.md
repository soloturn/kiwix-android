# UI redesign proposal

A from-scratch proposal for the app's navigation, content-selection, library and reading surfaces. Grounded in the current Compose code; file references are to this branch.

Priority order, as requested: **(1)** picking and downloading ZIMs with size made legible, **(2)** browsing what is already on the device, **(3)** the reading experience, **(4)** the navigation shell that ties them together. The shell is written first only because the other three inherit from it.

---

## 1. Problem statement

### The app already has two competing primary navigation surfaces

`KiwixMainActivityScreen.kt` builds a `ModalNavigationDrawer` wrapping a `Scaffold` whose `bottomBar` is a three-item `BottomAppBar`: Reader, Library, Downloads. So the bottom bar already carries the primary destinations, correctly, with `launchSingleTop`, `popUpTo(startDestination)` and per-route `saveState`/`restoreState` (including the `#4392` reader exception). **The drawer is not a navigation hierarchy. It is an overflow menu rendered as a drawer** — and every one of its seven entries is secondary.

That mismatch is the root complaint. A drawer is the Material pattern for *many* top-level destinations; this app has three, and they are already in the bottom bar.

### The drawer's grouping exists in the model and is erased in the view

`CoreMainActivity` defines three groups (lines 455–543): `[Bookmarks, History, Notes, Wifi hotspot]`, `[Settings]`, `[Help, Support, About]`. But `MainDrawerMenu.kt`'s `DrawerGroup` draws its separators under `if (visibleMenuItems.size == ONE)`. Only the single-item Settings group gets rules. The four-item and three-item groups render with no separator at all — so the semantic structure the code carries is invisible, and the user sees exactly what they described: a flat list of everything.

Above it, `DrawerTopBar` spends ~72dp on a 48dp launcher icon plus the app name. Zero navigational information, no state.

### The drawer is gesture-unreachable on the screen people use most

```kotlin
gesturesEnabled = enableLeftDrawer &&
  currentRoute in topLevelDestinationsRoute &&
  (currentRoute != KiwixDestination.Reader.route || leftDrawerState.isOpen)
```

The comment explains why: drawer gestures consume swipe events and make WebView scrolling lag. The consequence is that on the Reader — where a reading user spends nearly all their time — the hamburger is the *only* way in. A navigation surface that can only be tapped, on the one screen whose content fills the viewport, is a navigation surface that is in the way.

### Predictive back is declared but never happens

`core/src/main/AndroidManifest.xml` sets `android:enableOnBackInvokedCallback="true"`. But `OnUserBackPressed` uses a blanket `BackHandler(enabled = true)`, and the reader's `OnBackPressed` toggles a `shouldEnableBackPress` flag. Both are legacy-style callbacks, so the system never renders a predictive-back preview. The opt-in buys nothing today.

### There is no swipe anywhere

`HorizontalPager` appears in exactly one file in the whole app: `IntroScreen.kt`. The three bottom-nav destinations cannot be swiped between. The reader has no gestures at all except a drag-to-dismiss on the TTS overlay and a drag-to-move on the TTS floating button.

### Size is present but not emphasised, and not trustworthy

`OnlineBookItem.BookSizeAndDateRow` renders size via `BookSize(Byte(item.book.size).humanReadable, …)` in `bodyMedium` / `onTertiary` — the same type and the same muted colour as creator, language and date. The number a user needs most on that card is styled as the number they need least. Issue [#1989](https://github.com/kiwix/kiwix-android/issues/1989) asked for exactly this emphasis in 2020 and was closed unimplemented.

Worse, the arithmetic behind it does not agree with itself:

| Consumer | Treats `book.size` as | Formats with |
|---|---|---|
| `OnlineBookItem` / `BookItem` display | bytes | `core…zim_manager.Byte` — divides by **1000** |
| `AvailableSpaceCalculator.hasAvailableSpaceForBook` | bytes | compares to `StorageCalculator.availableBytes()` |
| `LibraryListItem.BookItem.isLessThan4GB()` | **kilobytes** | compares to `Fat32Checker.FOUR_GIGABYTES_IN_KILOBYTES` |
| free space, download totals, no-space snackbar | — | `eu.mhutti1.utils.storage.Bytes` — divides by **1024** |

Two things fall out. First, at most one of those three unit assumptions is correct; the FAT32 guard and the display cannot both be right. Second, even granting the unit, a catalogue row and the "space available" snackbar print **different bases under identical labels** — a 100 GB (decimal) ZIM against 93.1 GB (binary) free. That is precisely the comparison the feature exists to support, and the two halves are not commensurable.

### The metered-connection warning disarms itself permanently

`OnlineLibraryViewModel.showWifiOnlyDialog` and the `ShowWifiOnlyDialog` branch of `onBookItemClick` both answer "yes" with `kiwixDataStore.setWifiOnly(false)`. One tap through that dialog for a 20 MB ZIM silences the warning forever — including before the next 90 GB one. The only two things a user can express today are "never download on mobile" and "stop asking me, ever".

### Free space is computed two different ways on one screen

`AvailableSpaceCalculator` has `hasAvailableSpaceForBook(book)`, which compares against raw `availableBytes()`, and `hasAvailableSpaceFor(bookItem)`, which subtracts in-flight `bytesRemaining` first. `OnlineBookItem` uses the former to decide whether the card is even clickable; the click path uses the latter. Same question, two answers, same screen.

### Tapping a catalogue row starts the download immediately

`onBookItemClick` resolves straight to `StartDownload`. There is no selection model, no queue view, no "these four, 41 GB" step. For an action whose cost ranges over four orders of magnitude, a single tap with no confirmation of consequence is the wrong commitment ceremony.

Related: in-flight downloads are interleaved *into the catalogue list* (`observeOnlineLibraryItems` combines local books, downloads and network books into one `LibraryListItem` list), so progress lives on the browsing screen rather than where the user goes to read.

### The on-device library has no search, sort or filter

`LocalLibraryRoute.normalModeMenuItems` is `[file picker, nearby transfer]`. That is the entire toolbar. The list is `bookOnDiskListItems` grouped by `LanguageItem` headers with no alternative ordering, and the only gesture over it is swipe-to-rescan. Issue [#1642](https://github.com/kiwix/kiwix-android/issues/1642) ("Order of the books") has been open since 2023 with milestone 4.1.0. Issue [#2640](https://github.com/kiwix/kiwix-android/issues/2640) asked for one panel holding every library filter, reachable by swipe, and went stale.

In multi-select the app-bar title becomes `"${fileSelectListState.selectedBooks.size}"` — a bare integer with no noun.

### The reader has 13 affordances and no gestures

Top bar: hamburger, search, tab-count badge, overflow. Overflow (`ReaderMenuState`): Find in page, Share, Add note, Random page, Read aloud, Add to home screen, Close all tabs — all seven with `icon = null`, i.e. text-only rows. Bottom bar: bookmark, previous, home, next, TOC. The TOC itself is a hand-animated `ModalDrawerSheet` sliding in from the right, reachable only from its bottom-bar button, with `shouldUpdateTopAppBarAndBottomAppBarOnScrolling` flipped off while it is open.

Text zoom — the control a reader actually reaches for — lives in `SettingsScreen.kt` (~line 548) behind the drawer, four taps from the article.

### Prior art worth honouring

[#3943](https://github.com/kiwix/kiwix-android/issues/3943) (open, milestone 4.0.0) reports from Play Store reviews that a significant share of users never get through *online catalogue → download a book → read offline*. The proposal below treats that funnel as the primary design target rather than an afterthought.

---

## 2. Proposed navigation architecture

**Delete the drawer. Keep the bottom bar. Put the overflow in the app bar, where overflow goes.**

```
┌──────────────────────────────────────┐
│  Library            [search]  [⋮]    │   app bar: destination-scoped actions + ⋮
├──────────────────────────────────────┤
│  ▸ filter chips (lang · size · …)    │   persistent, tap → filter sheet
├──────────────────────────────────────┤
│                                      │
│   content                            │   ← swipe horizontally between Library ⇄ Catalog
│                                      │
├──────────────────────────────────────┤
│   [ Read ]   [ Library ]  [ Catalog ]│   three destinations, unchanged
└──────────────────────────────────────┘
```

### Where the seven drawer items go

| Today (drawer) | Proposed |
|---|---|
| Bookmarks | → **Saved** screen, segment 1 |
| History | → **Saved** screen, segment 2 |
| Notes | → **Saved** screen, segment 3 |
| Wifi hotspot | → Library app bar action (it shares what is *on the device*) |
| Settings | → `⋮` overflow |
| Help | → inside Settings |
| Support Kiwix | → `⋮` overflow (it is a call to action, it should stay one tap away) |
| About | → inside Settings |

**Saved** is one new screen replacing three drawer entries: a `SingleChoiceSegmentedButtonRow` of Bookmarks / History / Notes over a `HorizontalPager`, so the segments are swipeable. It is reached from the Reader's bookmark button (long-press already navigates to bookmarks today) and from the Library app bar. Three flat drawer rows become one entry point with a shape the user can learn once.

Net effect: **three bottom destinations, one overflow of two items, no drawer.** The hamburger disappears everywhere, which frees the left edge for the system back gesture on every screen — and makes the `gesturesEnabled` WebView-lag workaround unnecessary rather than merely tolerated.

### Gestures

| Gesture | Assigned to | Why this one |
|---|---|---|
| Left-edge swipe | System predictive back, everywhere | The edge is only free once the drawer is gone. Swap the blanket `BackHandler` for `PredictiveBackHandler` so the already-declared `enableOnBackInvokedCallback` finally renders previews — most valuable in the reader, whose stack is tab → article history → exit. |
| Horizontal swipe, Library ⇄ Catalog | Switch destination | Standard for tabbed primary navigation; pairs with the bottom bar rather than replacing it. |
| Horizontal swipe on the Reader tab | **Not** assigned | The same conflict that disabled the drawer gesture applies: a WebView owns horizontal pans for wide tables, galleries and horizontally-scrolled content. Reader is tap-only in the bottom bar. Honest asymmetry beats a laggy gesture. |
| Right-edge swipe, in the reader | Open the table of contents | The TOC sheet *already* animates in from the right. The gesture matches the motion the user has been watching, and the right edge is otherwise unclaimed. |
| **Two-finger horizontal swipe, in the reader** | Previous / next tab | One finger is spoken for — content pans and predictive back. Two fingers in parallel translation cannot be confused with a pinch, which is a *scale* (inter-pointer distance changes) rather than a translation, so `ScaleGestureDetector` and this gesture can coexist. Tab switching is the right payload: it is frequent for this app's multi-tab reading, it is currently buried behind a badge tap plus a grid selection, and it has an obvious directional mapping. |
| Two-finger **vertical** swipe | **Not** assigned | Considered for text zoom and rejected: a two-finger vertical drag is the opening frames of a pinch often enough that disambiguation would be fragile, and pinch already owns scale inside a WebView. Text size goes in a reader bottom sheet instead (§5). |
| Long-press a list row | Enter multi-select | Already how the local library works. Reuse it in the catalogue rather than inventing a second selection idiom. |
| Swipe down | Refresh / rescan | Already present on both libraries. Unchanged. |

### From cold start to reading

1. First launch → intro pager (unchanged) → **Catalog**, not Library. The current start destination lands on an empty Library whose only content is a "download" button — a dead end that [#3943](https://github.com/kiwix/kiwix-android/issues/3943) is about. Start where the content is.
2. Catalog → filter chips narrow by language/size → long-press to select, or tap a row to open its detail sheet → **Download (4.1 GB)**.
3. Download progresses in **Library**, pinned above the on-device list, plus the existing notification.
4. Library → tap a book → **Read**.
5. Read → right-edge swipe for TOC, two-finger swipe between tabs, `⋮`→ reader settings for size and theme.
6. Settings and Support are two taps from anywhere; Help and About are inside Settings.

---

## 3. Catalogue and download — the primary surface

This is the section the redesign exists for. A user choosing a ZIM on mobile is answering one question — *will this fit, and what will it cost me to get it?* — and today the app answers it in a muted grey subtitle computed in a different number base from the free-space figure it should be compared against.

### 3.0 Prerequisite: make size mean one thing

No size UI is worth building on the current foundation. Before anything in this section:

1. **Settle the unit of `LibkiwixBook.size`.** `isLessThan4GB()` reads it as kilobytes; the display and `AvailableSpaceCalculator` read it as bytes. One of those is a live bug — either FAT32 users are blocked from any ZIM over 4 MB, or large ZIMs are silently allowed onto filesystems that cannot hold them. Resolve it against libkiwix's `Book::getSize()` contract and add a test that pins it.
2. **One formatter.** `core…zim_manager.Byte` (base 1000) and `eu.mhutti1.utils.storage.Bytes` (base 1024) both label "KB/MB/GB". Pick one base, keep one class, label it correctly. Binary with `GiB` labels is most accurate; decimal with `GB` labels matches what the catalogue publishes and what users compare against their phone's storage screen. Either is defensible; *both at once* is not.
3. **One free-space function.** Collapse `hasAvailableSpaceForBook` and `hasAvailableSpaceFor` into one that always subtracts in-flight `bytesRemaining`, and use it for the card tint, the budget bar and the pre-download check alike.

### 3.1 The row

```
┌────────────────────────────────────────────────────┐
│ ▣  Wikipedia                               English │
│    All of Wikipedia, with images                   │
│                                                    │
│    ▰▰▰▰▱  102 GB      ⚠ 58 GB free                │
│                                                    │
│    maxi ▾  ·  6.4M articles  ·  2026-08            │
└────────────────────────────────────────────────────┘
```

Four changes to `OnlineBookItem`:

**Size is promoted.** From `bodyMedium`/`onTertiary` to `titleMedium`, on its own line, with the device comparison beside it. It becomes the second-most prominent element after the title. Creator, date and article count demote into one metadata line.

**A log-scale size meter.** Five filled pips: `<100 MB`, `<1 GB`, `<10 GB`, `<50 GB`, `≥50 GB`. Log scale, not linear, because catalogue sizes span four orders of magnitude — on a linear bar every ZIM under 10 GB is an indistinguishable sliver. The pips let a scrolling user triage without reading digits, which is what scanning a 3400-entry catalogue actually involves.

**Tint against the device, not a constant.** Issue [#1989](https://github.com/kiwix/kiwix-android/issues/1989) proposed colouring size by free space *or* by a fixed 1 GB threshold. Free space is the right half of that pair, because the threshold that matters is the user's, not ours:

- fits with >10% of the volume left over → default colour, no decoration
- fits but would leave <10% free → amber, `tight fit`
- does not fit → red, `⚠ 58 GB free`, row still tappable so the detail sheet can offer "change storage"

Today an unfittable book is covered by a grey `PureGrey` scrim and swallows pointer events — the user gets a toast if they fight it. A red number and a reachable row explain more and block less.

**Flavour collapsed into one row.** This is the highest-leverage size feature in the proposal. The catalogue publishes `mini` / `nopic` / `maxi` as *independent OPDS entries*, so today they arrive as unrelated `LibkiwixBook`s and render as three unexplained near-duplicate rows at wildly different sizes. Group entries sharing a title/name into one row with a flavour dropdown; the size, meter and tint update as the flavour changes. The user's real question is almost never "which book" — it is "which size of this book", and that selector is the direct answer. (Feasibility caveat in §7: grouping needs a reliable key, and `KiwixTag` may not be enough.)

### 3.2 Filter and sort — one sheet, and an honest answer about sorting

`#2640` is right: one panel, swipe-accessible, holding free-text search alongside language and category. Today those three live in three different places — search in a top-bar toggle that replaces the title, category in a full-screen dialog, language on a separate nav destination (`KiwixDestination.Language`) that leaves the catalogue entirely.

**A single filter bottom sheet**, dragged up from the persistent chip row under the app bar, carrying: query, language, category, **size range**, **flavour**, sort. Active filters stay visible as chips so the state is never hidden behind a sheet — the failure mode of the current design is a user who has forgotten they set a language filter and concludes the catalogue is empty.

**Sorting by size needs care, because the obvious implementation lies.** `OnlineLibraryManager.buildLibraryUrl` sends only `start`, `count`, `q`, `lang`, `category`. There is no sort parameter, and the list is infinite-scrolled 25 rows at a time (`ITEMS_PER_PAGE = 25`) over 3400+ entries. So:

- **Client-side sort is wrong.** Sorting the loaded window presents a global ordering the app does not have. The user sees "largest first" and believes the top row is the catalogue's largest ZIM; it is the largest of 25 arbitrary rows, and it changes as they scroll.
- **Fetch-all is not viable.** 3400 entries at 25 per page is ~137 sequential requests before the first sorted row renders.
- **So size is a filter, not a sort.** `size ≤ 2 GB` is expressible as a predicate on each page as it arrives, degrades honestly under pagination, and answers the actual user need ("show me things that fit"). Label it as what it is.
- **A true sort is an upstream ask.** A `sort=size` parameter on the OPDS endpoint reduces this to one line in `buildLibraryUrl`. Worth filing against `kiwix-serve`; until then, the filter is the correct local answer. Sorting *is* available honestly in the local library, where the whole list is in hand (§4).

### 3.3 Selection and the storage budget

Replace the tap-and-it-downloads model:

- **Tap a row** → detail sheet: full description, flavour comparison with all sizes side by side, publisher, date, and one primary `Download (4.1 GB)` button. The size is in the button label, so the commitment names its own cost.
- **Long-press a row** → multi-select, the same idiom the local library already teaches. The app bar becomes `3 selected` (with a noun — fix the bare-integer title in `LocalLibraryScreen.screenTitle` while touching this).
- **A budget bar** appears above the bottom nav whenever anything is selected or in flight:

```
┌────────────────────────────────────────────────────┐
│  ████████████████████░░░░░░░░  41.2 / 58.0 GB free │
│  3 selected · 2 downloading          [ Download ]  │
└────────────────────────────────────────────────────┘
```

The arithmetic already exists — `AvailableSpaceCalculator.hasAvailableSpaceFor` sums `bytesRemaining` across `DownloadRoomDao.allDownloads()` and subtracts it from `availableBytes()`. It is simply never shown; it only ever produces a snackbar *after* a failed attempt. Showing it continuously converts a post-hoc error into a pre-hoc decision. The bar turns amber crossing 90% and red on overrun, with the primary button disabled and a `Change storage` action routing into the existing `StorageSelectDialogConfig` flow.

### 3.4 Metered connections

Replace the permanent `setWifiOnly(false)` escape hatch with a per-download decision. On a metered network, confirming a download opens:

```
   Download over mobile data?

   Wikipedia (maxi) · 102 GB
   This is a large download on a metered connection.

   [ Wait for Wi-Fi ]   [ Download now ]
   ☐ Allow mobile data for downloads under 1 GB
```

Three changes:

1. **"Wait for Wi-Fi" is a real option.** Today the choices are "never download on mobile" and "disable the warning forever". Queueing until an unmetered network appears is the behaviour most users actually want, and the download machinery already supports pause/resume (`pauseResumeDownload`).
2. **The always-allow preference carries a size bound.** A blanket boolean cannot distinguish 20 MB from 100 GB, which is why it stops being useful the moment it is set. A threshold (default 1 GB, editable in Settings) lets small downloads flow and keeps the warning for the ones that matter.
3. **Above the threshold, always confirm.** Irreversible cost deserves confirmation proportional to the cost, regardless of what the user ticked months ago.

### 3.5 Downloads move to Library

Stop interleaving `LibraryDownloadItem` into the catalogue list. In-flight downloads pin above the on-device list in the **Library** tab, with the existing progress, ETA, pause and cancel controls unchanged. The catalogue becomes a browsing surface; Library becomes "everything that is or is becoming mine" — which is where someone waiting on a download goes to look.

---

## 4. On-device library

The device library gets the **same filter sheet** as the catalogue, minus category, plus sort — and here sorting is honest, because the whole list is local.

**Sort**, addressing [#1642](https://github.com/kiwix/kiwix-android/issues/1642): last opened (default), title, size, date added, article count. Language grouping becomes one of these orderings rather than the only possible shape — it is a good default for a multilingual user and a poor one for a user with eleven English ZIMs, which is the complaint in that issue.

**Filter**: free-text over title and description, language, flavour, size range.

**A storage header** above the list:

```
  Internal storage · 17.2 GB of 58.0 GB used by ZIM files     [ Manage ]
```

`Manage` enters multi-select with delete and share. Storage becomes visible at the point where deletions happen, instead of only in Settings and only as a number.

**Deliberately kept:** the `BookItem` card anatomy (icon, title, description, then date / size / article count / tags) is already well-judged and dense enough; long-press multi-select; swipe-down-to-rescan with its animated hint; `ZimFilesLanguageHeader` (repurposed as one grouping option). The only card change is adding "last opened" when present, so the default sort is explicable.

---

## 5. Reading experience

The reader's problem is not missing features — it is thirteen affordances with no gestures, and the one control people adjust constantly living in Settings.

**A reader settings bottom sheet** — one new affordance, promoted into the top bar:

```
   Aa  ─────●──────────   120%
   ◐   Light  ●Dark  System
   ☐   Keep screen on
```

Text zoom, theme and keep-awake, in reach of the thumb while reading. The zoom slider already exists in `SettingsScreen.kt`; it binds to the same `kiwixDataStore.textZoom` flow. Nothing new in the data layer — the control simply moves to where it is used.

**Right-edge swipe opens the TOC**, in addition to its bottom-bar button. The sheet already slides in from the right; the gesture matches the animation. Keep the button — a gesture with no visible affordance is undiscoverable, and the existing `ic_toc_24dp` is how people will learn the gesture exists.

**Two-finger horizontal swipe switches tabs** (justified in §2). Keep the badge and the grid: the badge communicates tab count, the grid handles "jump to the fifth tab", and the gesture handles "the next one", which is the common case.

**Trim the overflow from seven to four.** Find in page and Read aloud are the two frequent ones — promote both into the top bar as icons (the overflow entries currently pass `icon = null`, so they are text rows). Share, Random page, Add to home screen and Close all tabs stay in overflow. Add note stays as a bottom-bar long-press target, where it already is. Net top bar: `search · find-in-page · read-aloud · tabs · ⋮`.

**Bottom bar unchanged.** Bookmark, previous, home, next, TOC is a good five, each one-handed reachable, and the bookmark long-press shortcut into the bookmark list is a nice touch worth keeping.

**Replace the blanket `BackHandler` with `PredictiveBackHandler`** so the manifest's existing `enableOnBackInvokedCallback` produces real previews. The reader's back stack — tab close, article history, exit — is exactly where seeing the destination before committing pays off.

---

## 6. Deliberately not changed

Redesigning what works is how a redesign loses trust. Explicitly out of scope:

- **The three-destination bottom bar.** It is already the right pattern for three destinations, already Material 3, and its state-restoration logic already encodes a real bug fix (`#4392`, the `saveState`/`restoreState` reader exception). The proposal removes the drawer *so that* the bottom bar is the only primary surface — it does not touch the bar.
- **Article search** (`SearchScreenRoute`, `KiwixDestination.Search`). A dedicated full-screen search with its own route, recent-query handling and voice entry. The ask was about *content* selection, not in-ZIM search. No change.
- **The card anatomy** of `BookItem` and `OnlineBookItem`. Icon, title, two-line description, metadata row, tags is a good information design. Only emphasis changes, plus one new element (the flavour selector).
- **The tab switcher grid and the TTS controls card.** Both recent, both idiomatic, both well-built. The two-finger gesture adds a path to tab switching; it does not replace the grid.
- **Wifi hotspot and nearby-device transfer.** Genuinely useful offline-first features. Only their entry points move (hotspot from drawer to Library app bar; nearby transfer stays where it is).
- **The intro pager.** Already a `HorizontalPager`, already does its job. The `#3943` first-launch problem is in the *catalogue* step, which §3 addresses — not in the intro.
- **The download engine, chunking, notifications and pause/resume.** Everything in §3 is presentation and commitment ceremony over machinery that already works.

---

## 7. Migration and phasing

Nothing here requires a rewrite. Phases are ordered so each one ships independently and nothing blocks on the upstream dependency.

**Phase 0 — size correctness (no UI).** The three fixes in §3.0: settle the unit of `LibkiwixBook.size` with a pinning test, collapse `Byte`/`Bytes` into one base with correct labels, collapse the two free-space functions into one that always nets off in-flight downloads. Small diff, highest value in the whole document, and every size feature depends on it. Ship alone.

**Phase 1 — remove the drawer.** Delete `MainDrawerMenu.kt`, redistribute the seven items, add the **Saved** screen (segments + pager over the three existing routes, which already exist as `BookmarkScreenRoute` / `HistoryScreenRoute` / `NotesScreenRoute` — no new ViewModels), drop the `gesturesEnabled` workaround, swap in `PredictiveBackHandler`.
*Watch out:* `CoreMainActivity` declares `zimHostDrawerMenuItem`, `helpDrawerMenuItem`, `supportDrawerMenuItem` and `aboutAppDrawerMenuItem` as abstract, and the branded/custom apps under `branded/` override them. Removing the drawer is an API change across those variants, not just an app-module edit. Replace the four abstracts with a single `secondaryMenuItems` list so custom apps keep their one real need (hiding Help, renaming Support) with less surface.

**Phase 2 — size in the catalogue.** Promote the size line, add the log-scale meter, tint against free space, add long-press multi-select and the budget bar, replace the wifi-only boolean with the per-download sheet. No new screens, no navigation changes. Depends on Phase 0.

**Phase 3 — the shared filter sheet.** One component used by both libraries. Catalogue side: query, language, category, size filter, flavour. Local side: query, language, size filter, plus real sort. Retires the full-screen category dialog and the separate language destination from the catalogue flow. Closes `#2640` and `#1642`.

**Phase 4 — reader.** Settings bottom sheet, right-edge TOC swipe, two-finger tab swipe, overflow trim. Independent of 1–3.

**Phase 5 — swipe between destinations.** The one piece needing real care: a `HorizontalPager` over the three top-level destinations has to interact with `NavHost`, the pager's own state, and the existing per-route `saveState`/`restoreState` logic. Scope it to Library ⇄ Catalog only (§2 explains why the Reader stays tap-only) — that reduces it to two pages and sidesteps the WebView conflict entirely. A day of care, not a rewrite.

**Upstream, not blocking.** A `sort` parameter on the OPDS `v2/entries` endpoint, which would turn size-sort from impossible into one query parameter. And flavour grouping needs a dependable key for "same book, different size" — `KiwixTag` may carry enough (`_pictures`, `_videos`, `_details`), but if not, this becomes a second server-side ask for a group identifier. Phase 2's flavour row degrades gracefully meanwhile: without a reliable key the entries simply render as they do today.
