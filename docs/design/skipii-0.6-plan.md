# Skipii 0.6.0 — merged plan (Claude + Neon)

Derek approved sections 1, 2 (without DeArrow), 3 (five tabs kept), 5, 7, 8 of `skipii-2.html`, and asked that the
Settings › Downloads page match the real Settings design. Neon wrote the UI spec; Claude checked it against the
code and live YouTube. Where they differ, the decision and the reason are noted (**Changed**).

## 1. Downloads

**Storage.** `Downloads.kt`: its own `SimpleCache` in `filesDir/downloads` (never cleared by Android, unlike
`cacheDir`), `NoOpCacheEvictor`, one shared `StandaloneDatabaseProvider` with ShortsBank (one SQLite helper per
process). Index `filesDir/downloads.json`: per video the `VideoItem`, height (null = audio only), the two `Track`s
(URL = cache key, DASH manifest, bytes), state, auto/manual, added-at, a details snapshot (description, chapters,
likes, subscribers, aspect, caption files' srv3 text ≤ 6 tracks, SponsorBlock segments), local thumbnail file.
Downloaded in 1 MB ranged pieces like ShortsBank; a piece already cached is skipped, so a kill or a lost network
resumes where it stopped. A download is "ready" only when every byte is cached (checked again on start).

**Running.** WorkManager unique work `downloads` (CoroutineWorker, foreground `dataSync` with a progress
notification "Downloading 2 videos · 46%"), network constraint UNMETERED (Wi-Fi only, default) or CONNECTED.
Re-enqueued whenever something is added. Entries the constraint holds back show "Waiting for Wi-Fi".

**Playback.** `YouTubeMediaSourceFactory.createMediaSource` asks `Downloads.mediaSource(mediaItem)` first;
`onAddMediaItems` skips extraction for a ready download; `YouTube.details` returns the snapshot (when offline, or
when the network answer fails) merged as `quality = "720p"`, `qualities = [720]`; online it uses the live details
(More videos, comments) but keeps the downloaded stream. Captions read the stored srv3 by URL; SponsorBlock gets
the stored segments. Audio-only downloads show the thumbnail (no frame ever arrives).

**Rules.** Manual downloads stay until removed. Automatic ones (queue, first 5 of Watch later) respect the space
limit (oldest automatic first) and, with "Delete after watching", go once watched (ended or ≥ 90%).
**Changed:** Neon wanted the network rule to cover Shorts pre-loading too; Claude keeps Shorts as they are (Derek
didn't ask, and Shorts would get slower on 5G).

**UI** (Neon's numbers):
- Player action row: Download `ActionPill` after Playlist, before Share. Idle / Waiting (Wi-Fi icon) / progress fill
  + % / Downloaded (active, squish). Tap idle → `DownloadSheet`; tap otherwise → small sheet: Delete / Cancel.
- `DownloadSheet`: `ModalBottomSheet(surfaceContainerLow)`, `RoundedHeavy 21sp` title, subtitle "28:10 · plays with no
  connection", rows reuse `QualityRow` (1080/720/480 that exist ≤ 1080, Audio only) each with its size
  (contentLength, else bitrate × duration "≈"), 48 dp primary button "Download · 412 MB" (on mobile data with
  Wi-Fi only: "Download on Wi-Fi").
- Long-press sheet: "Download" row after Save to playlist.
- Library: full-width `DownloadsTile` (78 dp, 24 dp corners, secondaryContainer, cookie badge) under the Watch later /
  Liked tiles; signed out it shows above the sign-in empty state.
- `DownloadsScreen` (page over the tabs like History): back + "Downloads" + tune (→ Settings › Downloads); storage meter;
  `FilterPill` row All · Video · Audio only · From queue; `VideoRow`s in `SwipeVideo` (swipe left = delete, Undo 4 s);
  status pill (Downloading 62% / Waiting for Wi-Fi / Failed · tap to retry / 1080p · 412 MB / Audio · 38 MB),
  3 dp primary progress on the thumb, "Ready" badge. Empty state with "Download settings".
- Settings › Downloads (**the real Settings design**): new `SettingsPage.Downloads` in the Watching group after
  Playback, summary "1.4 GB of 4 GB · queue on". Blocks: `Joined(2)` storage `SettingBox` + `LinkSetting`
  "Manage downloads"; `GroupLabel` Automatic `Joined(3)` SwitchSettings (Download my queue · First 5 in Watch later
  (signed in, else dimmed with the reason) · Delete after watching); `GroupLabel` Limits `Joined(3)` ChipsSettings
  (Download on: Wi-Fi only / Any network · Space for automatic downloads: 2/4/8/16 GB · Quality for automatic
  downloads: 1080p/720p/480p/Audio only); `Note`. All rows searchable (`entries()`).
- Offline feeds: the feed error state gets a second action "Open Downloads" when something is downloaded.

## 2. Feed filters (no DeArrow)
- Tune `CircleAction` in Home's and Subscriptions' headers (signed in only), full primary while any filter is on.
- `FeedFiltersSheet`: "Your feed" + "Hiding N videos right now"; Hide videos I've watched; Hide videos shorter than
  Off/1/3/10 min; Muted words (Add first, then removable chips; inline field, IME Done adds); Muted channels
  (removable chips; added from a card's long-press "Mute this channel", which shows Undo).
- Applied in `HomeScreen` / `SubscriptionsScreen` only (never History, playlists, search, channels).
- **Changed:** "Shorts only in Subscriptions" as drawn was a no-op (Home never shows Shorts). It becomes GitHub #96's
  real ask: **"Shorts from subscriptions"** — a row of your subscriptions' Shorts on the Subscriptions page, shown even
  with the Shorts tab off; tap plays them in the Shorts pager (like a channel's Shorts). Default off. Signed-in only,
  can't be checked without Derek's account.
- **Watched** needs a new local list: `WatchedStore` (ids played to the end or past 90%, 2,000 kept). `ResumeStore`
  forgets finished videos, so it can't answer "watched".
- `FeedList` stops paging on its own after 3 loads in a row that add nothing visible (Neon's catch), then shows
  "Everything here is filtered out" + "Edit filters".

## 3. Search (five tabs unchanged)
- Results from InnerTube `search` (anonymous when signed out; probed live 2026-11-23): videos (`VideoCard`, grids on
  wide), channels, playlists, endless paging, inline "Try again" for a failed later page.
- **Changed:** filters are YouTube's real ones today (from search.json and a live probe), not Neon's older list:
  Type All/Videos/Channels/Playlists (chips), Upload date Any/Today/This week/This month/This year, Length Any/Under
  3 min/3–20 min/Over 20 min, Prioritize Relevance/Popularity. Encoded as YouTube's protobuf (`SearchFilters.params`),
  unit-tested against YouTube's own values. Date/length/sort dim for Channels/Playlists.
- Typing: history matches, then YouTube suggestions (NewPipe's suggestion list, 180 ms debounce), each with the
  fill-in arrow; a channel quick-hit card on top (a subscribed channel matched locally at once, else YouTube's top
  channel for the query after 400 ms).
- Results header: type chips + Filters chip (count when set) + removable active-filter chips.
- Channel result card: avatar 52, name, "@handle · 1.2M subscribers"; tap → channel page. **Changed:** no Subscribe
  button in the result (search results don't reliably carry the account's subscribed state; the channel page has it).
- Playlist result: `VideoRow` geometry with the count scrim; tap → playlist page.

## 5. Fullscreen
- Fullscreen top row: lock, title, PiP, **one gear tile** (dot when speed ≠ 1×, captions on or dubbed) replacing the
  speed / audio / captions / quality controls. Portrait "on the video" keeps its separate controls.
- Gear → the existing glass card (`OverlayDrop`), root rows Quality / Speed / Captions (if any) / Audio track (if > 1)
  with their values; a row drills in place (shared axis X, `animateContentSize`, width animates 240 ↔ 264) to the
  existing lists with a back header. Back: list → hub → closed → exit fullscreen.
- Swipe up in the middle 30% (Neon's gesture split: sides stay brightness/volume, down stays exit) → glass drawer
  (Queue · N / More videos / From <channel>), cards 160 dp, tap plays, long-press queues. Drawer follows the finger,
  settles with a spring; while it's open the fullscreen gestures are off; swipe down / tap above / Back closes it.

## 7. Choosing what to watch
- Subscription groups: chips All · groups · "+ Group" above the channel row (long-press a group = edit); the row and the
  feed follow the group (paging on up to 3 pages for a small group); "Unwatched only" chip under the row.
  **Changed:** Neon's "Hide Shorts" chip dropped (that feed has no Shorts). `GroupEditorSheet` as Neon specced.
- Watch later: length chips (Any / Under 10 / 10–30 / Over 30 min), Hide watched, "Pick one for me" (random among
  matches, not the current video); filters start `loadAll()` like the search.
- Comments: Top | Newest segmented control in the panel header (and wide inline header). **Changed:** switching keeps
  the current list dimmed until the new order lands (no jump to a spinner). Double-tap a comment's text to like it
  (signed in; links keep their own taps).

## 8. Widgets
- `RemoteViews` (no new dependency). Player widget 4×3 (resizable): now playing (thumb, title, channel · time,
  progress bitmap with SponsorBlock marks, −step · play · +step), Continue watching (3, "14 min left"). Up next 2×2,
  Search 2×2.
- Colours: the phone's wallpaper colours on Android 12+ (`system_accent1_*` / `system_neutral*`, day/night), YouTube
  red below.
- Continue watching from a new local `RecentVideos` store (title, channel, thumbnail, duration of opened videos), so it
  works signed out and offline. Bitmaps ≤ 320×180 RGB_565.
- Buttons: broadcast to the running service; with no service, open the app (which restores the session) and play.
- Updates on play/pause, seek, item change, download change, and every 15 s while playing with the screen on.

## Answers to Neon's questions
1. Own `SimpleCache` + index (resumable by piece), manual downloads never evicted.
2. InnerTube `search` + NewPipe's suggestion list.
3. Yes: comments.json (anonymous) carries both Top and Newest tokens.
4. Wallpaper colours on 12+, not the in-app theme.
5. New `RecentVideos` store.
