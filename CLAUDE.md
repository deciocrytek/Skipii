# Skipii — working rules for Claude Code

Native Android YouTube **player** (not a YouTube app): Kotlin, Jetpack Compose, Media3,
NewPipeExtractor. Optional Google sign-in with full sync, no ads, sideloaded. Single user (Derek), Pixel.
Design source of truth: `~/decio/castii/docs/DESIGN_LANGUAGE.md` (Material 3 Expressive,
motion standard) and the approved mockup `docs/design/mockup.html` (open in a browser; earlier rounds in `docs/design/`).

## Binding product decisions
- Pure player: no upload/Create button. Tabs: Home / Subscriptions / Shorts / Search / Library (Shorts since 0.5.50).
- Google sign-in (decided 2026-10-04, superseding "no account"): WebView on Google's own page,
  youtube.com cookies kept AES-GCM encrypted (Keystore). **Full sync** chosen by Derek: reads
  home/subscriptions/history/playlists AND writes likes, subscribes, Watch later, watch history.
  The account-suspension risk was explained; the in-app warning was REMOVED at Derek's request (2026-11-02, 0.5.83):
  the sign-in pitch and Settings › Account only say what syncs. Signed out, everything
  still works except the account feeds. Signed in, comments and replies can be posted.
- No inbox/notifications tab. New-video notifications for belled channels only (the bell, 2026-11-01, below).
- Portrait (default; 0.5.67 adds "on the video", see there): every playback control lives in the player's bar (0.5.34, PlayerBar.kt); the video keeps
  only double-tap seek, the spinner, SponsorBlock Ask/Skipped and errors. Chapters live on the bar's scrub
  track (never as a card under the video). Fullscreen keeps its overlay controls. The related list is
  "More videos", never "Up next".
- No watch-time stats in Library.
- Never add an ad SDK. SponsorBlock: per-category Skip / Ask / Show / Off (defaults: sponsor
  Skip; self-promo + reminder Ask; intro/outro/preview Show; music/filler Off). Its settings and the
  CC BY-NC-SA credit live in Settings › SponsorBlock (0.5.81; the account sheet before). No commercial use.
- No minimize button on the player (swipe down / Back). Tab bar = Castii's NavPill, but the
  selected tab uses full `primary` / `onPrimary` for contrast (not primaryContainer).
- Queue: playlist is always [current, ...queue]; long-press any video → Play next / Add to queue.
- 0.5.0 (2026-10-05): YouTube theme (`SEED_YOUTUBE`, Skipii's own, listed first); YouTube-style
  launcher icon (red rounded rect + double play, white background baked in, cut-out monochrome).
  Resume: `ResumeStore` (positions written by the service's `ResumeSaver`, read on open, thin
  bars on thumbnails). Background = audio only (video track disabled on ON_STOP, back on
  ON_START). Playful flourishes in `Playful.kt` (like Pop + sparks, dislike Droop, bell Ring,
  save Drop, rolling like count, squish). Play button = skip-button glass over a live blur
  (`VideoSurface`/`FrameSampler`/`frostedBackdrop`: own TextureView, tiny sampled frame drawn
  scaled up; `SAMPLE_WIDTH` = blur dial). Controls hide after 2 s. Channel page (`Channel.kt`,
  `LocalChannelOpener`) from the player's channel row and card avatars. Pull-to-refresh
  replaces refresh buttons; Home refresh shows only unseen videos (`freshOnRefresh`). Comments
  are a panel (`CommentsPanel`) opening just under the video, composer pinned at its bottom.
- 0.5.1 (2026-10-05): NO blur on player controls (Derek removed it; `VideoSurface` stays, the
  frame-sampling blur was deleted). Controls start hidden — opening (incl. from the mini-player)
  and rotating never show them; a lone spinner shows loading while hidden. Comments list only
  scrolls; ONLY the top handle resizes/closes the panel. Comment likes (signed in) via
  `CommentPosting.setLiked` → comment/perform_comment_action with the action from each
  toolbar's likeCommand/unlikeCommand; liked state from toolbarState likeState. Mini-player
  swipe down dismisses (56 dp or a flick; swipe up still expands). Collapsing headers
  (`Header.kt` `CollapsingHeaderLayout`) on every screen: follow the list's consumed scroll,
  settle shown/hidden on release, a soft top vignette fades in as they leave. Launcher icon
  = the in-app BrandMark exactly (4:3, corner 11/30, Material rounded fast-forward glyph).
- 0.5.2: player video is an edge-to-edge rectangle under the status bar (StageTopGap/SideGap/
  Radius = 0; shadow only mid-morph). Top vignette halved and softened (now `vignetteBrush` mirrored: 76% → 0, smoothstep). "YouTube
  refused the stream" = HTTP 403 on a stale/other-network stream URL: `PlayerState.recoverStream`
  re-extracts once per video and resumes at the same position; `StreamCache.invalidateAll()` on
  any default-network change. Errors log "playback error … (HTTP nnn)" under tag Skipii.
- 0.5.16: that recovery moved into the service (`StreamRecoverer` in PlaybackService.kt; works with
  no UI, e.g. background audio). Mobile data changes the phone's address mid-video without a
  network change, so a 403 can repeat: it recovers again once ≥30 s more has played; refused again
  at the same spot = give up ("stream refused again … giving up"). The UI follows the
  `StreamRecovery` object (spinner while `recovering`, `failed` → error panel). Try again
  invalidates the cached links (it used to replay the exact refused URLs).
- 0.5.16 bot check ("Sign in to confirm you're not a bot", seen on Derek's 5G): YouTube refuses the
  extractor's ANDROID reel request per address AND per client. ANDROID's formats are SABR-only (no
  URLs) — it only supplies details; every stream Skipii plays comes from the extractor's own VISIONOS
  request. `PlayerFallback` (hooked in `NewPipeDownloader`) answers a refused reel request with a
  VISIONOS answer minus its URLs, so streams still come from that same request. Do NOT hand the
  extractor VISIONOS URLs in the ANDROID slot: they 403'd on the first deep seek (3 of 4 videos);
  ANDROID_VR/IOS links 403 past the first chunk (need PO tokens). Logs "bot check on ANDROID for …".
  Test by forcing `isBotCheck` true and seeking deep on fresh videos.
- 0.5.4: members-only videos are never shown (`InnerTube.isMembersOnly`: badgeStyle
  BADGE_MEMBERS_ONLY / BADGE_STYLE_TYPE_MEMBERS_ONLY); channel uploads come from InnerTube
  (`channelVideos`, anonymous when signed out via `postAny`) because NewPipe drops the badge.
  Background audio-only state is remembered (`wantBackground`) and re-applied on every
  controller connect — a screen rebuilt while backgrounded used to leave video off forever.
  ONE video TextureView for the app (`LocalVideoSurface`, movableContentOf in SkipiiApp),
  moved between player / fullscreen / mini-player, never recreated — a new empty view at the
  handoff showed the thumbnail (minimize flicker). Mini takes the video only once the morph has
  finished (`progress.isRunning`); closing settles critically damped.
- 0.5.5: the real minimize-flash cause — moving a TextureView detaches it, which destroys its
  SurfaceTexture; the decoder lost its output and froze on a still for ~3 s until a keyframe.
  `SharedVideoTexture` keeps ONE SurfaceTexture/Surface for the process (destroy callback
  returns false, re-attached views get it back via setSurfaceTexture); the player gets
  setVideoSurface once and it is never cleared. Verify with a 10x-slow burst of screenshots
  of the mini-player during minimize: every frame must be live video.
- 0.5.6: the mini-player's video slot is EMPTY while the player is open/morphing (no thumbnail,
  no black box) — a waiting thumbnail read as a second copy the video flew towards. Check by
  holding a drag mid-minimize (adb `input motionevent DOWN/MOVE`) and screenshotting.
- 0.5.7: swipe-minimize "jumped into place" — springs stop at their visibilityThreshold
  (default 0.01 ≈ 15–20 px of travel here) and snap. Both morph springs and the Animatable use
  0.0005. Any new spring on `progress` must set it too.
- 0.5.8: the collapsing player never blocks the mini-player. `interactive = expanded || dragging`;
  when false the player root has NO pointer input (touches fall through — hit testing honours
  the graphicsLayer clip), the details list isn't composed below DetailsFadeStart, controls are
  off, and a tap on the shrinking video re-expands (`onInactiveTap`). Test mid-collapse with
  animator_duration_scale 5 (adb `input` itself takes ~0.3 s per command).
- 0.5.9: the page BECOMES the mini-player. Real mini-player is invisible (alpha 0, still
  touchable) whenever the player is composed; the morph frame is opaque mini color at the small
  end, its bottom holds at the card bottom (ramp from p 0.3) so it never covers the tab bar, and
  `MorphingMiniDetails` (MiniDetails look-alike, laid out per frame between the video's right
  edge and the frame's right edge, centred on the video) fades in over the last 30%.
- 0.5.10 lip sync (2026-10-06, `AvSync.kt`): ExoPlayer hands frames over up to 50 ms early stamped
  for SurfaceFlinger; a TextureView ignores the stamp and shows them at the next app frame
  (measured 17 ms early at 60 Hz on the emulator). `AvSync` measures every drawn frame
  (Choreographer expected-present vs the release stamp + 80% vsync, median of 61) and
  `SyncedVideoRenderer` shifts the video renderer's position by it. Logs "AV sync: frames show …".
  Audio/Bluetooth latency stays ExoPlayer's job. API 33+ only (no expected-present below).
- 0.5.14–0.5.15 double-tap seek (2026-10-09, Derek picked mockup B in `docs/design/double-tap-seek.html`, minus
  its landing-time label): left/right 40% of the video (portrait + fullscreen, never the mini-player);
  a double tap starts a burst of -/+10 s, every further tap on that side within 600 ms adds 10 s
  (`SeekBurstWindowMs`). `SeekBurstPill` (VideoView.kt): glass pill at 22%/78%, rewind/fast-forward
  glyph + rolling total ("+10", no unit), squish + glyph dash + sparks per tap, picture nudges 5 dp (3% scale, clipped).
  Thin white position line along the bottom during a burst. Middle 20% toggles controls at once; a lone
  side tap toggles them after the 300 ms double-tap timeout. Starting a burst hides the controls.
- 0.5.17 (2026-10-10, Derek's punch list): comments panel also closes by pulling the LIST down once
  it's at the top (nested scroll; scrolling stays first, flings never move the panel) — the handle
  still works. Avatars with a picture are circles everywhere (`ChannelAvatar`); only no-picture
  initials keep the cookie. Comments are read by `CommentFeed` (raw web `next` pages; NewPipe had
  no avatars) and show real names via `ChannelNames` (top ~4 KB of the channel's RSS feed, cached,
  4 at a time). Never upside down: open player uses SCREEN_ORIENTATION_USER (not FULL_USER).
  Swipe up on the open video → fullscreen (rubber-band swell, 64 dp or a flick). The portrait
  stage takes the video's aspect (`stageAspect`, 4:5..2.4:1; from the stream's size before the
  first frame); the morph's small end is the stage's centred 16:9 window (`videoFrame`), and the
  mini-player crops the same way. PiP button beside the quality pill (`PipController`; closing the
  window pauses, expanding returns). Swiping the app from Recents pauses and stops the service.
  Home quality: `WatchTimeReporter` (service) sends the web player's watch-time pings (10/20/30 s
  then every 40 s, plus pause/seek/move-on) — before, every video looked abandoned at 0 s. NOT
  verified (needs Derek's account): the pings being accepted and Home improving over days.
- 2026-10-12: `BottomGestureGuard` (UiKit.kt) — an invisible touch-eating strip the height of the
  bottom `mandatorySystemGestures` inset (32 dp on a Pixel) over the pages (MainActivity, below
  the mini-player/tab bar) and the open player (only while `interactive`). Content still draws
  under it, but a swipe home no longer nudges the list up first.
- 0.5.19 (2026-10-13): full movies/TV are never shown in browse feeds (`InnerTube.isMovie`, next to
  `isMembersOnly`): Primetime Channels (HBO Max, Shudder, Paramount+) and YouTube Movies carry a
  YPC/MEDIA badge (old renderers) or, in lockups, a content-rating badge (R, TV-MA…) or a
  BADGE_COMMERCE offer ("Try now"). Normal uploads use BADGE_COMMERCE only for "Fundraiser" — keep
  that exception (next.json fixture). Tested by `moviesDropped` (movies.json, HBO Max's channel page).
- 0.5.20 (2026-10-13, Derek approved mockup "Option B v2" in Drive): speed + audio-only buttons after
  Save (separate, 16 dp corners; signed out they're the whole row). Speed (`PlaybackSpeed`, PlaybackModes.kt):
  0.5–3× in 0.05 steps from a flickable ruler (`SpeedPanel.kt`, 12 dp per step, momentum then settles on a
  step, tap jumps, 1× reset); per video unless "Remember for every video", then saved, button filled in, and
  landing on 1× from another speed unticks it. The service's `SpeedKeeper` sets each NEW video's speed (same id
  = fresh links/quality switch, kept). Ruler haptics = `SpeedHaptics`: Vibrator primitives as USAGE_TOUCH —
  LOW_TICK 0.45 per step (steps <22 ms apart dropped), TICK 0.55 per 0.25×, CLICK 0.6 at 1×, CLICK 0.9 at the
  ends; view feedback fallback. Verified on the emulator via `dumpsys vibrator_manager` only — the feel needs
  Derek's Pixel. Audio only (`AudioOnly`, app-wide, persisted): video track disabled (with the background flag,
  `applyVideoTrack`; service applies it at start too), stage = `AudioOnlyStage` (no picture: 78 dp play, 58 dp
  skips, thick scrubber, SponsorBlock pill between the times) at the video's own `stageAspect` (2026-10-14:
  same area as the picture, so toggling never moves the page; every control sits exactly where the video
  overlay puts it — 48/66 dp skips/play centred, "pos / dur" row + scrubber at the bottom, skipped note on top —
  only the look differs). Switching animates in place (`VideoStage`: `cover` fades the audio stage over the
  picture, then `gather` springs the skips out from behind the play button). Back to video is immediate (Derek:
  waiting felt like the tap hadn't registered): `PlayerState.videoStale` (set on audio only, cleared by the next
  rendered first frame; `hidesStaleVideo` only once video is wanted again) hides the surface (`hideStaleVideo`) so the thumbnail + spinner show instead of the frozen
  pre-audio frame, and the fresh frame fades in. Thumbnail under its
  centred 16:9 window shows through as it shrinks; mini-player shows the thumbnail; no fullscreen (orientation
  held portrait, no swipe-up).
- 0.5.24 buffering (2026-10-18, Derek: "back buffer of 3 mins, cache ahead the max amount"):
  `MemoryBudgetLoadControl` (PlaybackService.kt) keeps 3 min behind (from a keyframe) and tops up ahead until
  ONE byte budget covering both is used: 60% of the large heap (`largeHeap` on; ~300 MB on a Pixel, 259 MB on
  the QA emulator). Below 15 s ahead (× speed) it always loads, so a full back buffer can't stall. Measured on
  the emulator: 1080p ≈ 7 Mbps, a 60 s rewind played from memory (no re-download); audio only buffers a
  1-hour video whole (~48 MB). Forward each LoadControl method explicitly — Kotlin `by` skips Java default
  methods and Media3's old defaults throw "not implemented" (crashed the service on start).
- 0.5.25 (2026-10-18): crash / force stop / swipe from Recents restores what was open. `SavedSession` +
  `SessionSaver` (Resume.kt; service listener) persist [current, ...queue] (id, title, channel, thumbnail,
  duration) on every playlist change; closing the mini-player empties the playlist and so clears it. On a
  fresh service `PlayerState.restoreSession` sets the items paused at the `ResumeStore` position and does NOT
  prepare (nothing downloads until play; `togglePlay`/`playFromQueue` prepare an idle player). The queue
  card has its own Clear pill (the sheet's Clear stays). Verified on the release build on skipii_qa.
- 0.5.26: clearing the queue animates (Sheets.kt `SlideAway`, `Motion.Exit` = emphasized accelerate). The card
  slides off to the right (300 ms) then its height closes (`QueueCloseSpec`, critically damped); the sheet's
  rows slide out staggered 40 ms top-down, then the list's height lerps to the empty state's. The queue is
  cleared at once; the UI animates a snapshot. Timing runs on the animation clock (`sweep`), never `delay()` —
  a delay isn't scaled with animator speed and the close started early in slow-motion checks.
- 0.5.27 player bar (2026-10-19, Derek picked mockup A in `docs/design/player-bar.html` minus its next button and
  time label): while the player is open the tab bar IS the player's bar (`NavPill`, NavBar.kt, drawn over
  `PlayerScreen`): −10 · play · +10, compact (0.5.28: play pill 96 dp ≈ 60% of the first 161, ±10 slots 50 dp,
  bar narrows 328 → 216 dp around its centre; tabs keep full width under it while fading). Driven by `morph.progress` in layer/draw lambdas only (a swipe down scrubs it
  back into tabs): tabs drop out staggered, the selected tab's pill glides + stretches into the middle slot and
  becomes Play, buttons spring up (`Motion.Pop`). The play pill is a progress bar: pale primary track (30% over
  surfaceVariant), full-primary fill = played fraction (snaps on 250 ms ticks, springs on jumps), glyph two-toned
  across the fill edge. ±10 taps show a rolling total bubble above the button (700 ms window). Touches go to the
  controls while `expanded || dragging`, else to the tabs. Steps down off screen for the comments panel; covered
  by fullscreen/PiP/sheets. Player details pad by `bottomClearance` and get the bottom vignette.
- 0.5.29 (2026-10-20): `morph.dragging` stuck true = dead mini-player (the player sat at its mini end still
  "interactive", looking exactly like the mini-player) AND live −10/play under the tabs (`controlsActive`). Cause:
  mini-player up a little then back down switched to the dismiss slide mid-gesture and never settled the morph.
  Every gesture that calls `morph.dragBy` must end in `morph.settle` — a gesture that started on the morph stays
  on it; `PlayerScreen` settles on dispose (page gone mid-drag, e.g. turning to fullscreen). Repro: adb
  `input motionevent` DOWN on the mini, MOVE up ~70 px, MOVE down past the start, UP; then tap a tab.
- 0.5.30 (2026-10-23): search history (`SearchHistory.kt`, local prefs "search", newest first, 50 max, case-
  insensitive dedupe; recorded by `SearchState.submit`). Shown while the field is focused (filtered by what's typed)
  and on the page before any search; tap = search, X = remove, "Clear all". The keyboard closing (Back) drops the
  field's focus (`isImeVisible`) so the results come back.
- 0.5.31 split screen (2026-10-23): portrait-locking in multi-window letterboxed the app (grey bar, black sides)
  whenever its pane was wider than tall. `OrientationController` follows `addOnMultiWindowModeChangedListener`:
  in multi-window it requests UNSPECIFIED and the app takes the pane's shape; leaving restores the usual lock.
  Fullscreen there fills the pane (`showsFullscreen`): on by itself in a wide pane, the button / swipe up / Back
  toggle it (`paneFullscreen`, reset when the pane changes shape or the player closes); never in audio only.
  The portrait stage stays within 60% of the window's height (`StageMaxHeightShare` → `PlayerState.stageFloor`,
  set from the window in MainActivity; tall videos pillarbox) so a short pane keeps the details. Test on the
  emulator: hold-swipe into Recents → app icon → Split screen; drag the divider (adb `input motionevent`).
- 0.5.32 player bar reach (2026-10-25, Derek picked mockup A "Lift" in `docs/design/player-bar-reach.html`): the open
  player's bar is a size up and lifted toward the thumb — 264×72 (`PlayerBarHeight`), play 120×60, ±10 60, icons 30 dp,
  its bottom `PlayerBarLift` 36 dp above the tab bar's. The tab bar's slot never moves: the bar grows upward in its
  `layout` and lifts by a graphicsLayer translation (touches follow) on a back-out ramp (p 0.22–0.92, small overshoot)
  that lags the narrowing. Player `bottomClearance` and the comments step-down include `PlayerBarRaise`. Burst bubble
  sits 10 dp above the lifted bar.
- 0.5.33 bubbles (2026-10-25, Derek's recording 6410.mp4 in Launchii): MainActivity is `singleTop`, NOT singleTask.
  singleTask gave a bubble the app's one task: on the phone, tapping Skipii's Home icon while it was bubbled pulled
  that task full screen and left the bubble blank; on the emulator, bubbling a running Skipii got the task killed by
  System UI. Now a bubble gets its own task, and the app keeps ONE window itself: a new MainActivity
  `finishAndRemoveTask()`s the previous one (`MainActivity.current`), so bubbling moves Skipii into the bubble and
  opening it from Home closes the bubble. Playback carries on: `PlaybackService.onTaskRemoved` only pauses + stops
  when the open window's task (`MainActivity.openTaskId`) is gone from `appTasks` (a real swipe from Recents or a
  dismissed bubble); logs "an older window's task was removed … playing on". The new window starts on Home with the
  mini-player (page state isn't carried over). Verified on skipii_qa release build; not yet on the phone.
- 0.5.34 everything in the bar (2026-10-25, Derek picked "A · Key" in `docs/design/player-bar-bloom.html`, v2: speed
  and audio only stay in the action row): bar = −10 · play · +10 · ⋮ (322×72, `CompactBarWidth`). `PlayerBarState`
  (PlayerBar.kt) holds three 0…1 Animatables — `tools` (dome 380×252, four tools on an arc: Quality, PiP,
  Fullscreen, Next), `list` (quality list in the bar, Auto + every height, check lands then folds after 420 ms) and
  `scrub` (bar grows to 340×112, play stretches into a 316 dp chapter track: chapter slits, SponsorBlock marks,
  knob 1:1 under the finger, seek on release, a tick per chapter edge). NavPill's size (`playerBarSize`, layout)
  and outline (`playerBarShape`, graphicsLayer shape+clip+shadow) follow them per frame on top of the morph.
  ONE gesture handler (`playerBarGestures`, Initial pass so a gesture it takes cancels the buttons' clicks):
  sideways on play = scrub; up/down = dome/list follow the finger, release settles with velocity (dismiss from
  75%, open from 35%, flick 1.6/s); hold at rest = bloom, slide onto a tool, release picks. The bar resizes under
  the finger, so movement is summed from each change (position − previousPosition), never from the first touch.
  Tools ride an arc whose radius shrinks with the dome (never clipped). ⋮ turns into ✕. Closes on ✕, Back, a tap
  anywhere outside (MainActivity scrim), and when the player collapses / goes fullscreen / opens a panel. Next =
  queue first, else the first of More videos. Chapters come from NewPipe `streamSegments` (`VideoDetails.chapters`).
- 0.5.35 bar punch list (2026-10-25, Derek): bar = audio only · −10 · play · +10 · ⋮ (play dead centre; 48 dp side
  slots, 372 wide — wider than the tab bar, it scales down on narrower windows). Speed and audio only left the action
  row (signed out there's no row): audio only is the bar's left slot, speed is a dome tool whose panel is the existing
  ruler (`SpeedPanelContent(inBar = true)`) inside the bar. Dome tools: Speed, Quality, PiP (if supported),
  Fullscreen, Next ONLY while the queue has something; spread evenly on the arc. Tools never fly out of play: each
  waits at its spot and pops in once the dome has grown past it (`toolRevealAt`). Panels and the scrub track are
  never narrower than the rest bar (380; track 348). Shadow: 12 dp elevation with FULL black ambient/spot colours —
  Android already scales them to ~4%/19%, so the old 0.10/0.12 alphas were invisible (white on white). Chapters:
  NewPipe only reads description chapters; YouTube's auto chapters (`engagement-panel-macro-markers-auto-chapters`)
  come from `CommentFeed.parseChapters` on the watch `next` page the comments already load (`chapterRenderer`
  {title, timeRangeStartMillis}; tested by `ChaptersParseTest`), merged into details when NewPipe had none.
- 0.5.36 (2026-10-25, Derek): the resting bar is 15% smaller (316×61: play 102×51, ±10 51, sides 41, gaps/inset 5 —
  `PlayerBarPadding`; tabs keep `BarPadding` 6) so opening it reads bigger; dome/panels/track keep their sizes.
  Audio only has NO video space on the open player: `VideoStage`'s `fold` (spring, critically damped) closes the
  stage's layout height to 0 (picture fading + lifting, clipped while folding), scaled by `ramp(progress, 0.8)` so a
  swipe down unfolds it again and the thumbnail still lands in the mini-player. Swipe the details to minimize then.
- 0.5.37 progress (2026-10-25, Derek picked A "Inside" in `docs/design/player-bar-progress.html`): bar 10% taller —
  row `PlayerRowHeight` 67 + `PlayerProgressRoom` 8 = `PlayerBarHeight` 75 (316×75 at rest). `BarProgress` (PlayerBar.kt)
  draws a 3 dp hairline under the row (row width − 20 dp each side, 5 dp from the bottom): chapters as slits, SponsorBlock
  marks in their colours, fill from `rememberPlayedFraction`. Scrubbing swells THAT line into the 348×40 chapter track
  (it no longer grows out of play). Play is a plain full-primary button now (supersedes 0.5.27's play-pill progress fill).
  Dome 262 tall, arc centre 80 dp up, so tools clear the taller row.
- 0.5.38 (Derek): 8 dp `PlayerTopRoom` above the row so the buttons have as much room above (8 + 5 inset) as below
  (5 inset + 8 hairline room): bar 316×83. Hairline 10% smaller: 248×2.7 (inset 34). Dome 270, arc centre 88 up.
- 0.5.39 (2026-10-25, Derek picked A "Card" in `docs/design/player-bar-row.html`): the dome is gone. The ⋮ key / hold /
  swipe up opens a short rounded card (`CardWidth` 380, `CardHeight` 160 above the resting bar's bottom, 32 dp corners)
  with the tools as 58 dp circles in ONE straight row (`rowSpot`: evenly across 348 dp, centred 113 dp up; pop in left
  to right). The card also reaches `CardBelow` 24 dp under the resting bar (`playerBarBelow`; NavPill places the bar
  that much lower and measures its contents without it, so the row and hairline never move) — chapter · time sit
  there, centred under the progress line. (`PlayerBarState.dome` → `card`, `BarDome` → `BarCard`.)
- 0.5.40 (Derek): while scrubbing, the time sits on the LEFT above the track (a right thumb hid it on the right);
  the chapter moved to the right, end-aligned.
- 0.5.41 (2026-10-26, Derek): over a video the status bar is pitch black with white icons, in every theme mode.
  `PlayerScreen` draws a black strip the status bar's height (alpha `ramp(progress, 0, 0.9)` × (1 − audio-only `fold`,
  now hoisted out of `VideoStage`)) and sets `StatusBarOverVideo.on` (Theme.kt), which `SystemBars` reads for the
  icon colour. Audio only and the pages keep the page look. 0.5.42: the strip sits OUTSIDE the morph's clipped frame
  and fades across the open — inside the frame it slid in with the card (Derek disliked it). 0.5.43/0.5.44: the fade
  starts only once the video's top edge is 25% of the way up the screen (`StatusBlackFromUp`; its p from
  `miniThumb.top` → `stage.top`), black by p 0.9.
- 0.5.45 scrub preview + cancel (2026-10-26, Derek): while scrubbing, the frame the scrub would land on floats 12 dp above
  the bar over the knob (kept within the bar's width): YouTube's storyboard sprites (NewPipe `previewFrames` →
  `VideoDetails.storyboards`, `Storyboard.frameAt`), sharpest level, a smaller "quick" level prefetched whole as a scrub starts
  and shown while the sharp sheet loads (`StoryboardSheets`, ≤16 decoded, via Coil). Swiping up off the track brings out a
  Cancel pill over the bar (`PlayerBarState.cancel` follows the finger 24 → 92 dp up; armed at 92, disarms below 78); armed
  = full primary + haptic, the frame washes out (an opaque wash, never layer alpha — the page showed through) and the
  landing spot holds still; letting go there drops the scrub (no seek). `ScrubOverlay` (PlayerBar.kt), placed by NavPill
  outside the bar's clip like the burst bubble; a scrub whose pointer vanishes is dropped (`endScrub`, finally).
- 0.5.46 (Derek, 6434.png): Cancel sits ABOVE the preview frame (10 dp over it, centred on it; just over the bar without a
  storyboard — `cancelRaise`) and arms only when the finger reaches its bottom edge (bar-local y ≤ −cancelRaise; disarms
  14 dp below); it fades in from 24 dp up, the frame no longer lifts. Shadows: each piece is ONE graphicsLayer holding its
  shape + clip + shadowElevation + alpha. A fade on an outer layer around a separate `.shadow()` layer crops the shadow to
  a hard-edged box (the half-faded Cancel showed one) — never pair layer alpha with a nested shadow.
- 0.5.47 (2026-10-26, Derek): the scrub knob sits UNDER THE THUMB — the track (centred in the bar) maps straight onto
  the video (`landingAt`), so a scrub starts wherever the finger is (supersedes "knob from the current position + delta").
  While scrubbing, "now" is a pin (primary dot ringed in the bar's tone on a thin stem through the track), played is
  solid primary up to now and the stretch the scrub would jump over is primary at 45%; the time row adds the jump
  ("+6:33" / "−5:02", primary). Chapters: with chapters, the card's bottom strip (`cardBelow()` 46 dp, else 24) is a
  Chapters button (≡ chapter · time ⌃) opening `BarPanel.Chapters` — `BarChaptersList`: every chapter with a storyboard
  thumb from its start (`StoryboardThumb`; `StoryboardSheets` now shared via `rememberStoryboardSheets`, 24 kept), title,
  start time, current filled; opens scrolled to the current one; tap = seek + fold after 420 ms. Inside the rows the list
  scrolls (gesture mode 4); pulled down from its top it closes the panel. `PlayerState.seekTo` on an item that isn't
  known seekable yet (restored, not prepared) uses seekTo(currentIndex, ms) — Media3 silently drops a plain seek then
  (scrubs/chapter taps did nothing until play).
- 0.5.48 (2026-10-27, Derek): a scrub starts from a sideways drag ANYWHERE on the resting bar (not only play; the
  buttons still get taps — nothing is taken before touch slop). While scrubbing the whole bar lifts `ScrubLift` 56 dp
  (NavPill's layer translation, `playerBarScrubLift`, so touches, the preview and Cancel follow) and its shadow deepens
  12 → 20 dp (`playerBarElevation`): the track sits clear of the thumb, which keeps scrubbing in the space below.
  Cancel's fade follows the finger's own travel since the scrub began (summed deltas; bar-local heights move with the lift).
- 0.5.49 bot check, part 2 (2026-10-27, Derek's 5G screenshot): the error means ANDROID was refused AND `PlayerFallback`'s
  VISIONOS stand-in was refused too. A refused extractor VISIONOS request (its only stream source) used to be silent: the
  extractor played ANDROID's 360p combined itag 18 with no word. `PlayerFallback` now watches that request too
  ("bot check on VISIONOS for …", `BotCheck.noteRefused`); `StreamCache.getOrLoad` turns "refused + no separate streams"
  into `StreamsRefused` (nothing cached). `BotCheck.retrying` (YouTube.kt) retries after 3 / 8 / 20 s with the spinner
  and a note under it ("YouTube is checking this connection. Trying again…", `BotCheck.waiting`), then gives up: the 360p
  combined stream if there was one (`keepRefused`, 10 min, never returned by getOrLoad), else the error. Used by
  `YouTube.details` and `StreamRecoverer`; `onAddMediaItems` uses `withoutWaiting` (a restored item resolving after its
  retries hijacked the video just opened). No third client exists (probed 2026-10-27): TVHTML5_SIMPLY / MWEB links need the
  player JS for `n`, which extractor 0.26.5 (and dev without an external JS engine) can't run; ANDROID_VR 403s past chunk 1.
  Real fix still open: PO tokens / running the player JS (WebView). Test by forcing refusals in `PlayerFallback`.
- 0.5.50 Shorts (2026-10-28, Derek; the idea is essopsp's PR #10, credited in README, code written fresh). `Shorts.kt`.
  Feed = `InnerTube.shorts`: the reel sequence (reel/reel_watch_sequence, `reelSequenceParams` = hand-encoded ReelSequence
  protobuf) after a seed that plays first: signed in, the first short on Home; else/none, a random one of the first 10 of a
  public "shorts" search — the sequence works anonymously (probed 2026-10-28), so signed out there's a generic feed (Derek).
  Parser tested by `shortsSequence` (reel_sequence.json, real anonymous capture). Shorts run on their OWN players (Derek:
  "pause it, restore on leave"): entering pauses the app's video (no mini-player on the tab), leaving plays it again if it
  was playing (`resumeMain`). `ShortsState` keeps a pool of 4 ExoPlayers (`ShortPlayer`: `YouTubeMediaSourceFactory`, now
  internal, + `SyncedRenderersFactory`, loops, 20 MB / 180 s buffer) for the short before, this one and the two after, each on
  its page's own TextureView (`VerticalPager`, beyondViewportPageCount 1, so the neighbour's first frame is live mid-swipe).
  Preloading (Derek: "if the current video is fully loaded, start loading the next"): the next short prepares only once
  the current one is `loaded` (buffered to its end, or capped with ≥30 s ahead), the one after only once the next is;
  details/streams (`YouTube.details`) for the next three once this one's are in. Logs "shorts: loading <id> (on screen|next|
  after next)". Leaving releases the players (feed and spot kept); background pauses (`setForeground`). 403 → fresh links once.
  Signed in: like / subscribe (`InnerTube.videoState`), history start ping only (no watch-time pings). Tap = pause (own tap
  detector: detectTapGestures took the down and a quick flick came out as a pause).
  The bar (Derek's mockup, iterated 2026-10-28): the tab bar turns into ‹ + a pill of dark frosted glass along ONE progress
  (`shortsMorph`, 620 ms linear in, 460 ms `ShortsExitEasing` out, everything moving from the first frame — the first cut
  waited ~300 ms on the tab glide and felt slow). NavPill: `shortsTabDrop` / `shortsHomeFade` / `shortsBarFade`, tabs off
  while shown. `ShortsBar`: ‹ 50 + gap 8 + pill 236, 50 dp tall, centred, bottom = tab bar's bottom; pill = Like ·
  Comments · Share · channel avatar (+/✓ badge; tap subscribes, signed out a note). ‹ and Back return to the tab Shorts
  was opened from (`shortsFrom`). Glass = One UI Launcher's recipe on the live frame: each page's frame sampled 40 px wide
  (TextureView.getBitmap, ≤30/s while under the bar, once for a waiting short), 3×2 box blur, saturation 1.25 + Samsung curve
  (15–255 → 53.5–232) as a ColorFilter, under #252525 at 38% (Derek: dark, more transparent); per page so mid-swipe each short
  shows through its own half. No outline (rule 7). Icons are the mockup's own paths, centred by VISUAL MASS (silhouette
  centroid, `Mass`; Derek: box-centred "doesn't look centered"); the comment bubble only halfway, the avatar as drawn.
  Progress = 2.5 dp line on the screen's bottom edge (out of the pill: it weighed the pill down). Video full-bleed under both
  system bars (`StatusBarOverVideo.shorts`: white icons on both), the page vignette hidden. Comments = `CommentsPanel` from
  42%; the bar steps down out of its way; Back closes it. Five tabs: active weight 3.5 so "Subscriptions" fits.
- 0.5.51 Shorts flow (2026-10-29, Derek: "taking too long to load every single video. It's not flowing"). Measured with
  "shorts: <id> playing N ms after coming on screen (<what was ready>)": nothing ready 3.6 s, links only 1.9 s, first frame
  ready ~0 ms. Causes: links (NewPipe extraction, 2–3.5 s) fetched only after the current one's; the next short loaded only
  after the current was COMPLETE (most get swiped first). Now: links for the next 5 (+1 behind), 3 at a time, nearest first;
  `YouTube.details(id, wait = false)` (no bot-check waits in Shorts); the next two load a 5 s preview (`CappedLoadControl`,
  `capMs`) once the short on screen has played 1 s and has 5 s ahead, each in full once all before it are; `poke()` acts on
  player/links events at once; `tick()` never nests (ExoPlayer notifies listeners inside prepare(): doubled prepares).
  Fixed quality, no adaptive ladder (a switch stalled playback): `ShortsBank.tracks` = H.264 ≤720p by the SHORT side (720×1280)
  + M4A. Note `StreamSelection`'s 1080 cap is on height, so vertical videos in the main player still top out at 854 tall.
  The BANK (`ShortsBank.kt`, Derek: "always keep 10 shorts in memory even when app is closed … once viewed, forget it and
  load more"): RAM can't outlive the app, so the next 10 unseen shorts are kept WHOLE ON DISK (SimpleCache in
  cacheDir/shorts-bank, NoOpCacheEvictor; index + manifests + details in filesDir/shorts_bank.json, with the feed's next
  token). Every Shorts player reads through that cache (DASH manifests whose stream URLs are the cache keys), so a banked
  short plays with no request at all. `ShortsState` is one per process (`SkipiiApplication.shorts`, MainScope; the shell
  calls leave() on dispose) and `fillBank()` runs every 1.5 s from 3 s after start: downloads the next unseen short in 1 MB
  ranged pieces, one at a time, only while the connection is free (on Shorts: on-screen short started + previews loaded;
  elsewhere: the app's video isn't buffering, `mainBusy`). Seen = forgotten once its player holds it; bytes no player holds
  are dropped (`cleanup`). A banked short that errors plays live instead. Measured: the bank fills in ~1 min (1–38 MB each,
  ~135 MB); after a force-stop, every banked short starts in 0–36 ms. Comments: two requests on tap (~1–2 s); now
  `CommentFeed.prefetch` as each short comes on screen (73 ms to open). Nothing else starts until the on-screen short has
  played 1 s. `prewarm()`: with the app open and Shorts not, one player sits ready (paused, no surface) on the bank's next
  short, so opening the tab plays in ~25 ms (was 1–2.4 s); released in the background. Leaving Shorts moves past the short
  just seen, so the tab reopens on an unseen one. "shorts: <id> stalled at Ns (M ms loaded ahead)" with plenty loaded is
  the EMULATOR's c2.goldfish.h264 decoder falling behind (EventLogger: droppedFrames 50; happens with no other player and
  with the glass sampling off) — judge stalls on the Pixel, not the emulator.
- 0.5.52 (2026-10-29, Derek): ‹ left Shorts slowly (the Back gesture didn't): `leave()` released all four players on the
  tap, and ExoPlayer.release() blocks until each decoder is shut (heavier with the Pixel's hardware decoders; the system
  back animation hid it). Now leave() only pauses and detaches them; they're released one a frame after the exit animation
  (`retired`, `retiring`), and the player of the short the tab reopens on stays paused (instant reopen). Never release
  players on a tap. The progress line was hidden by the bezel/rounded corners: 4 dp off the bottom edge and inset by the
  display's bottom corner curve at that height (`bottomCornerRadius`, Android 12+ RoundedCorner) + 12 dp, rounded ends.
- 0.5.53 (2026-10-29, Derek: tapping the channel gives two options, subscribe and go to channel). The bar's channel button
  opens `ShortsChannelMenu`: a card of the bar's dark frosted glass (darker tint, 62%, so words read; `drawFrost` /
  `framesUnder` now shared with the bar), right edge on the pill's, 10 dp above the bar, growing from the button: the
  channel's name, Subscribe / Unsubscribe (signed out: the sign-in note), Go to channel. A tap elsewhere (consumed), Back,
  or the next short closes it. Go to channel = the app's channel page over Shorts (`showChannel`); the short pauses
  (`ShortsState.cover`), the glass bar steps aside, status icons follow the page; Back returns to the short. A video opened
  from that page leaves Shorts and plays in the app's player.
  0.5.54: its buttons did nothing on the phone. The close-on-outside-tap catcher wrapped the card, so it saw every touch
  (a parent's Main pass) and closed the card on touch-down before a row's click; now it's a layer BEHIND the card (a hit
  sibling stops the ones below) and the card swallows its own touches. Test menus with a tap ~0.5 s after opening.
- 0.5.55 self-update (2026-10-29, Derek; GitHub issue #13): `AppUpdate` (Updater.kt) asks the GitHub API for the latest
  release at most once a day (each window start; a failed check retries after 1 h). If its Skipii.apk is newer than
  `BuildConfig.VERSION_NAME`, Home's header shows an Update pill left of the profile picture (`UpdateAction`); otherwise
  nothing. On an unmetered network the APK is fetched ahead. Tap = download (fill + %, size and the asset's sha256
  `digest` checked) → PackageInstaller session (`USER_ACTION_NOT_REQUIRED` on 12+). Not yet allowed to install apps →
  Android's "Install unknown apps" page, and the install carries on on return (`onWindowResumed`). The first update from
  a Drive/Files install asks once; after that Skipii is the installer of record and updates silently: the app just
  closes, and `UpdateReceiver` (MY_PACKAGE_REPLACED) posts "Skipii updated to X · Tap to open" (not when Android's
  own dialog was shown, which offers Open). Releases MUST go through scripts/publish-apk.sh (tag v<versionName>, asset
  Skipii.apk, signed with this Mac's debug key; another key = INSTALL_FAILED_UPDATE_INCOMPATIBLE). Logs "update check: …"
  / "update: …". Tested on skipii_qa: a 0.5.0 build found v0.5.54 and installed it silently (adb-installed, so no
  prompt); notification via a seeded `installing` pref + reinstall; Retry via a fake 9.9.9 release (downgrade refused).
- 0.5.56 (2026-10-29, Derek, from GitHub issues): Shorts can be turned off (#21): `ShortsSetting` (Shorts.kt, prefs
  "playback"), a card in the account sheet above SponsorBlock. Off = no Shorts tab (NavPill shows four, selected weight 2.2
  as before 0.5.50), the bank loop idles, `ShortsState.turnOff` drops the feed + waiting player and `ShortsBank.clear()`
  deletes the banked shorts. Vertical videos (`videoAspect` < 1) go fullscreen UPRIGHT (#20/#22): `OrientationController`
  `portraitFullscreen` (button / swipe up / ⋮ → Fullscreen); turning the phone sideways makes it ordinary landscape
  fullscreen (the flag drops), turning back exits; Back / swipe down / exit button leave it. Share (#19) is an `ActionPill`
  after Save (signed out it's the whole row, so the action row always shows now); `shareLink` (UiKit.kt) is shared with
  the long-press sheet and the Shorts bar. Age-restricted videos (#14) still can't play: stream extraction is anonymous
  (cookies only go on InnerTube browse/next/pings), every anonymous client answers LOGIN_REQUIRED, and the clients that
  would accept the account need the player JS (signature + `n`), which extractor 0.26.5 can't run (same open item as 0.5.49).
- 0.5.57 (2026-10-29, Derek, from GitHub issues): the fullscreen quality sheet scrolls (#1: in landscape only the first
  three rows fit). Save to playlist (#12, signed in): `InnerTube.saveChoices` (playlist/get_add_to_playlist →
  `playlistAddToOptionRenderer`s, Watch later first, `containsSelectedVideos`; falls back to the Library's playlists with
  membership unknown), `setInPlaylist` (browse/edit_playlist, Watch later uses it too), `createPlaylist` (playlist/create
  with the video). State + writes in `PlayerState` (`openPlaylists`, a closing sheet never cancels a write; the answer
  also sets `inWatchLater`); `SaveToPlaylistSheet` (Sheets.kt): rows with a filled check, New playlist (name + Private /
  Unlisted / Public). Opened from a Playlist pill after Save and the long-press "Save to playlist". NOT verified: any of it
  signed in (`SaveChoicesParseTest` is hand-written in the web answer's shape, not a capture — replace it with one).
  Fullscreen brightness / volume (#3, #6; `FullscreenSliders.kt`): vertical drags starting on the left 35% set the
  window's brightness (never the system setting; back to the system's when fullscreen closes), on the right 35% the media
  volume (whole steps, no system UI); full video height = full range; a glass level pill on that side, fading 700 ms after.
  The middle keeps swipe down to exit (now in the same handler, 120 px or 1400 px/s). Starts within 28 dp of the top/bottom
  are left to the system. Nothing taken before touch slop, so taps and double-tap seek are untouched.
- 0.5.58 (Derek: "the brightness control isn't very good"): it worked on the LINEAR brightness a window sets, so the top
  half of a drag barely changed anything, everything happened in the bottom third, and the pill disagreed with the system
  slider (emulator default 0.40 linear = 82% on Android's slider, the pill said 40%). Levels now go through Android's own
  slider curve (`toSlider` / `fromSlider`, SettingsLib BrightnessUtils' HLG constants; `BrightnessCurveTest`); the start
  is the system setting ((n − 1) / 254, which adaptive brightness writes into too).
- 0.5.59 (Derek: "needs two full swipes to go from the upper end to the bottom"): a swipe over 40% of the screen's height
  (`SwipeRangeShare`) now runs the whole range, brightness and volume alike (was the full height).
- 0.5.60 (2026-10-29): a new version checks for updates on its first start (`KEY_CHECKED_BY` in Updater.kt). Installing
  over the app keeps the daily timer, so a build installed from Drive showed no Update pill until the next day. (0.5.60 on
  Drive, the same code as 0.5.61 on GitHub, so Derek could try the Update pill.)
- 0.5.62 (Derek: the Shorts ‹ looked shifted right): an open chevron reads as the triangle it outlines, so `Mass.back` is
  that triangle's centroid (12.97), not the stroke's (11.6): the glyph sits ~1.2 dp left of its box-centred spot.
- 0.5.64 RTL phones (2026-10-30, a user's Arabic phone, screenshot 6484.jpg; Derek: "UI elements should be where the
  English version has them"). The app is `supportsRtl="false"`: the layout is the English one in every language (no
  mirroring — 0.5.63 mirrored the pages and was rejected). With it on, Compose mirrored Rows/alignments/`offset {}` but
  not `place()`, drawing or pointer x, so the Shorts glass and its buttons disagreed. Text still runs in its own
  direction: `OwnDirectionText` (Theme.kt, merged into `LocalTextStyle`; text fields merge it into their own style) =
  `TextDirection.Content` + `TextAlign.Left`, so an Arabic title is right to left (punctuation in place) but left-aligned
  like English. Never pass a typography style to a Text (it would drop that); use `TextAlign.Right`, not `End`, for a
  right-hand label (End follows an Arabic text's own direction). Numbers in labels use Latin digits (`Locale.US` in
  formatDuration/formatCount; the default locale gave "١,٢M"). Lines joined with " · " use `joinDots` (each part a bidi
  isolate: an Arabic channel name pulled the next "2.4" into its run, "2.4 قرآن كريم M views"). ONE exception Derek allowed
  once it was confirmed as the norm (Android's own Settings in Arabic): settings switches (`SettingSwitchRow`, Sheets.kt) sit
  LEFT of their words on an RTL-language phone (`rememberRtlLanguage`, from the locale) and are the mirrored switch. Test: `adb shell cmd locale set-app-locales com.skipii
  --locales ar-EG` (back: `en-US`).
- 0.5.65 (2026-10-30, GitHub #24): a tall empty gap under Appearance's mode chips on a big font / display size. The chips sat
  in a plain Row: the last one (AMOLED) got only its padding's width and its label wrapped a letter a line. The row now
  scrolls like the SponsorBlock and theme rows, labels one line. Any row of chips scrolls (or wraps) — never a bare Row.
  Test: `adb shell settings put system font_scale 1.3` + `adb shell wm density 500` (back: 1.0 / `wm density reset`).
- 0.5.66 feed cards (2026-10-31, Derek): thumbnails sit 11 dp from the screen's edge, half the old 22 (`VideoCard`: card
  6 dp in, 5 dp padding, thumbnail radius 23 to stay concentric with the 28 dp card). `CardMeta`: "Channel · views · age"
  stays one line when it fits; when it doesn't (narrow phones, big fonts; measured with `rememberTextMeasurer`), the
  channel gets its own line and views · age go under it (18 sp line height). Test: `adb shell wm density 480` (360 dp).
- 0.5.66 What's new (2026-10-31, GitHub #30): the Update pill opens `WhatsNewSheet` (Updater.kt): the notes of every
  release newer than this build up to the latest, newest first (version headings only when there are several), the
  Update button pinned under them (fills with the download; "Try again" after a failure). `AppUpdate` now reads the
  releases LIST (`parseReleases`, drafts/prereleases skipped) so a phone that skipped versions sees all their notes;
  `parseNotes` keeps "###" headings and "- " bullets only, stops at a "---" line (the web page's install help), strips
  bold/code/links. Notes live in CHANGELOG.md: every change people would notice goes under "## Unreleased" (### New /
  Improved / Fixed), written for users; `publish-apk.sh` posts that section as the release notes, renames it to the
  version (commit CHANGELOG.md after), and refuses to publish with no notes. Releases before 0.5.66 only say "Install
  Skipii.apk…", which parses to nothing (the sheet says no notes were published). Tested: `AppUpdateTest`; live check
  against GitHub; the sheet on skipii_qa with a seeded release (9.9.9 + 9.9.8 notes in prefs "update").
- 0.5.66 fullscreen quality (2026-10-31, GitHub #25; Derek picked A "Drop" in `docs/design/fullscreen-quality.html`): in
  fullscreen the quality pill opens `QualityDrop` (VideoView.kt), not the sheet (it covered the whole video): a 184 dp card
  of dark glass hanging 8 dp under the pill, right edges aligned, popping out of it. Auto + every height with its tag, 38 dp
  rows, scrolls when taller than the room above the scrubber (`DropBottomReserve`), opens scrolled to the chosen row. A
  pick lands the check, folds after 420 ms. Tap outside (a layer BEHIND the card, consumes the whole gesture) or Back
  closes it; the controls don't auto-hide while it's open. Portrait keeps the sheet. Verified on skipii_qa: scroll (no
  volume slide), pick → "video itag 298 (720p)", outside tap, Back stays in fullscreen.
- 0.5.67 controls on the video (2026-10-31, Derek): `PlayerControls` (PlaybackModes.kt, prefs "playback" `controls_in_bar`,
  default the pill) picks where the open portrait player's controls live; a chip card in the account sheet ("Player controls":
  Playback pill / On the video). On the video: NavPill never morphs (its morph reads go through `progress()`, 0 in this mode,
  `controlsActive` false) and MainActivity steps the tab bar down along `morph.progress` (0..0.5); `bottomClearance` drops the
  bar. `VideoContent` shows the fullscreen overlay in portrait too. The overlay (both modes, fullscreen included) is drawn like
  the pill: `OverlayTile`/`OverlayPill` on `surfaceVariant` (full primary while a mode is on), ±10 `OverlaySeek` tiles, play a
  full-primary 2:1 capsule (`OverlayPlay`); top row audio only (portrait) · speed (`SpeedSheet`, `PlayerSheet.Speed`) ·
  quality · PiP; bottom row time · chapter · Ask · Next (queue) · fullscreen. Portrait has no inset scrubber: the stage's bottom
  edge is the playback head (`Modifier.videoEdge` on an unclipped box around VideoStage's stage, zIndex over the details so the
  knob overhangs): 3 dp line, chapter gaps, SponsorBlock marks, primary knob 5 dp (7 with controls, 9 scrubbing). Grab = the
  bottom 36 dp, Initial pass: sideways anywhere, any direction within 28 dp of the knob (so it beats the minimize drag); seeks on
  release, ticks per chapter; `EdgeScrubPreview` shows the storyboard frame + time over the knob, the overlay fades meanwhile.
  Audio only doesn't fold the stage in this mode: `AudioOnlyStage` keeps its (restyled) controls plus audio only / speed tiles.
  Verified on an emulator (debug): setting, tab bar away/back, edge scrub (seeked to the landing time), audio only, fullscreen, speed.
  0.5.68 (Derek): play on the overlay is a full-primary CIRCLE (`OverlayPlay(size)`: 66 dp portrait / audio stage, 78
  fullscreen), not the pill's capsule. Fullscreen's `Scrubber` is drawn like the portrait playback head (3 dp, white 32/45%,
  primary played + knob, a gap per chapter cut with BlendMode.Clear on its own offscreen layer).
- 0.5.69 (2026-10-31, GitHub #31): fullscreen's speed pill opens `SpeedDrop` (VideoView.kt), the quality card's twin: presets
  0.5–3× (`SpeedPresets`), "Normal" on 1×, a ruler speed that isn't a preset gets its own "Custom" row, and an "Every video" row
  = Remember (toggles, stays open). Both cards share `OverlayDrop` + `dropShown` (each card's state starts over per opening);
  `VideoContent`'s `drop: DropKind?` says which is open. Portrait still opens the ruler sheet (`SpeedSheet`).
- 0.5.69 (2026-11-01, GitHub #33, @BaooRaami): the comments panel lives inside the morph's frame, so swiping the video down
  shrank it into the mini-player card and it was still open on the next expand. Now (PlayerUi.kt) it rides and fades like the
  details (same alpha ramp + translation under the video), the details under it stay hidden while p < 1 (no see-through), and
  `state.sheet` is cleared once `showDetails` goes false. A short drag that springs back keeps it open. Checked on skipii_qa.
- 0.5.70–0.5.72 (2026-11-01, Derek): Save to playlist is always exactly as tall as the comments panel rests (`panelRestTop`,
  PlayerUi.kt: just under the portrait video; MainActivity passes window height − that); the list scrolls inside. The size
  goes on the sheet's CONTENT (`height`, minus the 48 dp handle and the nav bar), never on ModalBottomSheet's modifier — M3 1.3
  computes its resting spot from the constraints after that modifier, so a capped sheet hung from the top with its bottom cut
  off (0.5.70). A fixed height, not a max: sized to its content it set off for the spinner's height and changed course when the
  playlists arrived (Derek's 6526.mp4 "hiccup", 0.5.71). The action row has no Watch later pill any more (0.5.72; Derek: keep
  only Playlist, whose first row is Watch later); `inWatchLater`/`toggleWatchLater` are gone, long-press "Save to Watch later" stays.
  0.5.73: no scrim (`scrimColor = Color.Transparent`, the video stays bright like with the comments; an outside tap still closes).
  Picking: Save's `Flourish.Drop` on the row icon, the circle fills (`squishOn`) and the check springs in. The sheet is its own
  window, so the player's `ActionMessage` was hidden under it: `SheetMessage` shows `actionMessage` at the sheet's bottom, over the list.
- 0.5.74 hold for 2× (2026-11-01, Derek, GitHub #11/#26/#32/#36): a finger held still for the long-press timeout on the
  video's right 40% (the double-tap seek side; portrait + fullscreen, both via `VideoContent`'s one tap handler) plays at
  `PlaybackSpeed.HOLD` until it lifts (`PlayerState.startFastHold`/`endFastHold`: old speed back, paused again if it was; never
  saved/remembered; skipped if the next video started mid-hold, SpeedKeeper already set its speed). LONG_PRESS haptic, controls
  hide, `FastHoldPill` ("2× ▸▸", the seek pill's glass) at the top. Moving before the timeout keeps the old gestures (volume slide,
  swipes, scrub take it); once held, the hold consumes every move (Main pass, before fullscreenGestures / the stage's draggable),
  and the edge scrub (Initial pass) backs off while `holdingFast`. Not during a seek burst, an error, collapsing, idle/ended.
- 0.5.75 captions (2026-11-01, Derek: "build this" on `docs/design/captions.html`; Captions.kt + CaptionsUi.kt). Tracks come
  with the details (`VideoDetails.captions`): NewPipe's `subtitles` keeps each track ONCE, as TTML — the same link with
  `fmt=srv3` is fetched instead (srv3 = YouTube's XML, auto-generated "asr" tracks carry per-word `<s t>` offsets). The files
  need no sign-in or PO token (probed). `Srv3` parses (tested by `CaptionsParseTest` on real captures); `captionLinesAt`
  = the creator's cues running at t (3 at most). Auto-generated tracks show as YouTube does (Derek, 0.5.77, after word by
  word in 0.5.75 and a sliding roll-up in 0.5.76 were both hard to follow): `captionPages` pairs lines in order (a pause
  over 1 s or a sound ends a pair), both lines at once from the pair's first word, hard cut to the next pair, no animation
  at all; `captionPageAt` looks a page up. 0.5.78 (Derek: "both lines connect, no gap"): a page is ONE text (`CaptionText`,
  lines joined by \n, sounds italic by span) with a box per visual line, touching, as one Path filled once; a corner
  is round only where its line sticks out past the neighbour it touches (no notch, no darker seam). Word times only light the transcript. Pressing any captions button (bar tool,
  overlay CC tile) turns captions ON first (`turn(true)`), then opens the list (Derek: no second press).
  `CaptionsState` (PlayerState.captions): tracks, choice (track or track + `translateTo`), on, cues; lines load only while
  shown or the transcript is open. `CaptionSettings` (prefs "captions"): Show Never / Always / Foreign (default: on when
  the asr track's language ≠ the phone's), Look Skipii / Classic / Outline / Android's (`CaptioningManager` style + font
  scale), Size, and the language last PICKED (preferred next time). YouTube's own auto-translate (`tlang`) answers HTTP 429
  (probed twice), so translation is ML Kit on the phone (`CaptionTranslator`, a ~30 MB model per language, downloaded on
  first pick, English built in; ML Kit logs every line under tag "native"). It translates SENTENCES (lines grouped to . ? !,
  a 1.5 s pause or 5 lines) and shares each back over its lines by length (`shareOut`) — a lone asr line is half a
  sentence and read badly. `CaptionLayer` draws on the picture (VideoContent's aspect box + PiP), sized 6.5% of its height
  (9–20 dp) × size, a box per visual line; it re-reads the controller's position every frame while playing (the 250 ms
  poll is too coarse for words) and lifts 56 dp (72 fullscreen) while the overlay controls show, 50 dp over the Ask pill.
  Pickers share `captionOptions` (tracks, then "Translated on your phone": phone language, last picked, downloaded, More
  languages = all): the bar's `BarTool.Captions` → `BarPanel.Captions` (switch + rows + Transcript; scrolls like Chapters via
  `bar.panelList`; six tools shrink the circles, `petalPx`), the overlay's CC tile → `CaptionsDrop` in fullscreen /
  `CaptionsSheet` in portrait. Transcript = `PlayerSheet.Transcript`, a `PlayerPanel` (the comments panel's shell, now
  shared) with search, the current line's words lit, follow + "Back to now", tap to seek. Verified on skipii_qa (debug):
  tracks + transcript + seek, sheet, bar panel, word roll-up, ML Kit German download + translation, settings looks; release
  (R8): playback, tracks, model download + translated captions. ML Kit's `libtranslate_jni.so` (~16 MB per ABI) is the app's
  only big native code: release ships arm64-v8a + armeabi-v7a only, compressed (`useLegacyPackaging`): 17 MB APK, not 67.
  NOT verified: Derek's 5G (bot check: whether the VISIONOS stand-in carries the track list).
- 0.5.75 the bell (2026-11-01, Derek picked A "Split" in `docs/design/bell.html`; Bell.kt + NewVideos.kt). Bell on = the account's
  "All" for that channel and a phone notification per new video; off = "Personalized" (YouTube's default). `Bells` (prefs
  "bells") is the local copy: `learn` takes what a page says (`parseBell`: the subscribe button's
  `subscriptionNotificationToggleButtonRenderer`, current state's icon NOTIFICATIONS_ACTIVE / id 2 = All) from the watch page
  (`videoState`), the channel page (`channelHome`) and the whole subscriptions list (`InnerTube.subscriptions`,
  FEchannels + continuations, `syncIfDue` every 20 h from the worker); a page read within 30 s of a change doesn't overrule it.
  NOT subscribed, YouTube still sends the toggle on Personalized (next.json) — `learn` ignores it then. Writes:
  `InnerTube.setBell` → notification/modify_channel_preference with hand-built params (`bellParams`, YouTube.js's
  encoding: 1 Personalized, 2 All, 3 None; `BellTest` matches youtube.com's own items minus their field 10). Unsubscribing
  `forget`s; signing out `clear`s. UI `SubscribeWithBell` (player row + channel page, `wide`): Subscribed (inner corners 7) ·
  3 dp · bell key 48/64 dp growing out of it; on = full primary, round, Ring flourish; Subscribed still unsubscribes in one
  tap. Shorts' channel card: a 38 dp key at the Unsubscribe row's end. Turning one on needs notifications
  (`rememberBellToggle`: Android 13+ asks; turned off in Settings → that page opens). `NewVideos` (WorkManager, unique
  periodic "new-videos", 15 min, network): reads each belled channel's public RSS feed (no cookies) only up to the first
  video already seen (seen ids in prefs "bells" `seen:<id>`, 30 kept); a channel's first look announces only what went up
  after its bell did (`BellChannel.since`), nothing older than 3 days, ≤4 per channel per pass; Shorts (/shorts/ links)
  only while Shorts are on. YouTube's RSS feeds 404 on and off for hours (reported widely since Dec 2025; 4 of 5
  channels on 2026-11-01): a pass where a channel's feed failed fills those channels in from the first page of the
  account's Subscriptions feed (ONE signed-in request, only then; ages from "3 hours ago", `ageMs`; no Shorts there).
  Notification channel "New videos", thumbnail as big picture, tap → MainActivity
  (`takeVideo` → `NewVideos.opening` → `player.open`). Logs "bells: …" / "new videos: …".
  Verified on skipii_qa (debug, seeded prefs "bells"): worker run, 404 → "feed failed", notifications with thumbnails,
  tap opens and plays the video; the button's states / grow / revert-on-failure via a /tmp preview build (fake
  subscribed state, never shipped). Test the worker: delete `no_backup/androidx.work.workdb*` (run-as) and relaunch —
  WorkManager won't run periodic work early even with `cmd jobscheduler run -f`. NOT verified (needs Derek's account):
  bells read from his pages / FEchannels, `setBell` accepted, the Subscriptions-feed fallback, the Shorts card's key.
- 0.5.79 languages (2026-11-02, Derek; GitHub #23, Spanish users): Skipii in English + Spanish, Portuguese (BR, `values-pt`),
  French, German, Italian, Indonesian (`values-in`), Turkish, Vietnamese, Russian, Arabic, Hindi, Japanese. `AppLanguage`
  (Language.kt): the phone's language or a pick from the account sheet's Language card (first card; a drop-down, 0.5.80, each language
  named in itself). Android 13+ = the system per-app language (`LocaleManager`, `res/xml/locales_config.xml`, also in Settings ›
  Apps › Skipii › Language; Android recreates the window); below = prefs "language" + `attachBaseContext` wrap + `recreate()`.
  YouTube is ALWAYS asked in English (`hl=en`): parsers, the movie / members-only filters and bot-check detection read its
  English words; only Google's sign-in page follows the app (`AppLanguage.webTag`). Skipii translates at DISPLAY: `formatCount`
  (suffixes from count_thousands…, the language's decimal mark, Latin digits; Japanese 万/億 via bool count_in_ten_thousands),
  `viewsLine` / `subscribersLine` (plurals by `compactQuantity`: a compacted count stands in as 1,000 / 1,000,000 so the noun
  agrees with "1,2 M" — French/Spanish "de", Russian "просмотров"), `displayAge` (YouTube's English "3 days ago" → ICU
  RelativeDateTimeFormatter; data stays English, NewVideos parses it). Strings live in `res/values/strings_<area>.xml` (prefix
  per area: video_, shorts_, bar_, player_, comments_/captions_, ui_, app_) and `strings.xml` (shared). In a composition use
  `stringResource`; elsewhere `str(R.string.x)` / `plural(R.plurals.x, n)` (Language.kt, the app's current language). Never keep
  translated text in something built once (enums, objects): keep a `@StringRes` and resolve where shown. Placeholders are
  always `%N$s`, never `%d` (Resources format %d with Arabic-Indic digits). New strings need all 12 translations (same names,
  same placeholders; check with a script). Caption "Foreign" mode and language names follow the app's language. Verified on
  skipii_qa (Android 16): picker → Spanish, feeds/player/bar/tabs, Arabic search line, release (R8) playback in Spanish.
  NOT verified: Android 12 and below (the prefs path), signed-in screens in other languages, every translation on screen.
- 0.5.81 Settings page (2026-11-02, Derek picked example 4 "Big title that folds" in `docs/design/settings-merged.html`,
  A + C of `docs/design/settings.html`; Settings.kt). Supersedes the account sheet (gone): the header avatar / person icon
  opens Settings, a page over everything (mini-player and tab bar included) sliding in from the right; Back goes category →
  front page → search cleared → closed. Ledgii's anatomy + Android's structure: a large title (RoundedHeavy 38) that folds into
  a 64 dp bar as the page scrolls (read in layer lambdas only, 70 dp of scroll; search becomes an icon in the bar, a page
  vignette fades in under it), the search field, Ledgii's primaryContainer account card, four quick toggles (Audio only,
  SponsorBlock, Shorts, Keep speed: full primary when on), then category tiles (icon square, title, a summary of what's set,
  chevron) JOINED per group: 3 dp apart, inner corners 7, outer 21 (`Joined`). Groups: Watching (Playback, Captions,
  SponsorBlock, Shorts), Look & language (Appearance, Language), then Notifications + About; version under them. Sections
  enter Ledgii's way (fade up 16 dp, 35 ms apart); categories slide shared-axis X (1/8 width, 440 ms). Categories hold joined
  rows (`SettingBox`): Playback = controls placement, remember speed, audio only; Captions = preview + show/look/size + kept
  translation languages; SponsorBlock = switch, the eight segments (dimmed, not hidden, when off) and the credit; Shorts;
  Appearance = mode + themes; Language = a radio list (picking rebuilds the window; page state is saveable so it reopens on
  Language); Notifications = every belled channel (`Bells.on`, a list frozen as the page opens so one turned off can go back
  on) + Android's notification settings; About = version (tap: What's new if newer, else `AppUpdate.checkNow()`) + GitHub;
  Account = sign in / out and what full sync sends (0.5.83: no suspension warning, Derek). Search (`entries()`): every setting's title and option words in the
  app's language, accents and case folded; a result opens its category, scrolls to that row (`Find`) and lights it
  (surfaceContainer → primaryContainer and back). A new setting needs its row key in `entries()` to be searchable. Strings:
  `strings_settings.xml` (prefix settings_). Verified on skipii_qa (debug): fold, search → lit row, every category, quick
  toggles, Spanish switch reopening on Language, light + AMOLED. NOT verified: signed-in Account/Notifications (bells list).
  0.5.82 (Derek: "match the opening animation of my app Moodii", ~/decio/Moodii AppShell `transitionsFor` + Entrance.kt): Settings
  comes forward like Moodii's pages — scaleIn from 0.94 + slideInVertically 1/12 on Moodii's spatial spring (0.86, 650;
  `MoodiiSpatial`, MainActivity) + fadeIn 220 ms; out = scaleOut 0.96 + 1/16 down + fadeOut 160. The tab bar and mini-player
  sink away while it's open (`settingsAway`, as Moodii's bar under a page). The front page rises in with Moodii's `riseIn`
  (620 ms EmphasizedDecelerate, 70 ms per order, 26 dp; title 0 … developer card 7), ONCE per opening (`entered` hoisted
  above the category AnimatedContent, so Back from a category doesn't replay it). Categories keep the shared axis X.
  Moodii's developer card is last on the front page (replaces the version line; About has it): "Made by @y7esdii", Come say
  hi, Instagram / Threads / X discs (Moodii's brand paths, even-odd), "Email the developer" (SENDTO mailto:, subject
  "Skipii feedback"). Outside links go through `openOrSay` (no app → a message, never a crash).
- 0.5.85 foldables (2026-11-02, a Z Fold 6 user: "videos on the cover screen get stuck in full screen"; Derek: unfolding must
  carry on with the same video / short, never back to Home). Stuck: the fullscreen button's forced landscape outlived the
  fold, and it only lets go once the phone is turned sideways (with auto-rotate on), so the cover screen held upright stayed
  sideways. `OrientationController.Bind` drops `forced` when the SCREEN changes (`screenSides()`: maximumWindowMetrics as
  short side × long side) — never key it on smallestScreenWidthDp, which changes with rotation on the near-square inner
  screen (the bars come off it) and cancelled fullscreen at once. Continuity: `configChanges` adds density|keyboard|navigation
  (Samsung's two screens can differ in density; never add locale, picking a language rebuilds the window). If the window is
  rebuilt anyway: `player.expanded` is saved (`savedExpanded`) and the player reopens at once (morph snapped to 1) once the
  controller has the video back; `openChannel`, `resumeMain` are saveable; Shorts' `leave()` and the ON_STOP background
  switch are skipped while `isChangingConfigurations` (leave() moves past the short on screen). The controller's
  reconnect path (`onConnected` rebuilding from the session) now loads comments and like state too (it had only details).
  Test: AVD `skipii_fold` (Pixel 9 Pro Fold, API 36): `emulator -avd skipii_fold -port 5562 -no-window`, `adb emu fold` /
  `unfold`; screencap needs `-d <display id>` (`dumpsys SurfaceFlinger --display-id`). Pixel Fold doesn't rebuild the window
  on a fold; force a rebuild with `cmd uimode night yes|no` (uiMode isn't handled). Verified on skipii_fold (debug + release):
  fullscreen → fold = upright player, playing; rebuild with the player open / on a short = same video / short, no break.
  NOT verified: a real Galaxy Fold.
- 0.5.86 big screens (2026-11-02, Derek picked "Rail 3 · Flow" in `docs/design/large-screens-rail.html` with the Fold's pages
  swapped, option B in `large-screens-rail3-fold.html`; rounds 1–2 in `large-screens.html`). `Adaptive.kt`: `WindowLayout`
  (`LocalWindowLayout`) = `wide` (window ≥ 600 dp), `foldX` (a vertical fold in a wide window, androidx.window FoldingFeature,
  flat or half open), `tabletopY` (HALF_OPENED + horizontal fold). Large screens (`onLargeScreen`: the screen's short side ≥ 600 dp)
  are never held portrait: `OrientationController.free` = multi-window or large → UNSPECIFIED, and fullscreen there is a choice
  (`paneFullscreen`, button / swipe up / Back; kept across a turn and carried from the cover screen when unfolding in fullscreen).
  Wide: `NavRail` (Rail.kt, 84 dp, the five tabs, selected = full primary pill; hidden on Shorts) replaces the pill, pages start
  right of it (`pagesStart`), feeds are grids kept INSIDE the LazyColumns (`videoCards` chunks rows of `gridColumns()`: page width /
  280 dp, max 4 — one column is exactly the phone list; Home/Subscriptions/Search/Channel/Playlist), the mini-player is a card in
  the bottom-right corner (morph geometry follows). Player (`PlayerLayout`, PlayerUi.kt): Phone as before; `Tablet` = the player
  right of the rail, video + details + comments in one scroll on the left (`inlineComments`, Comments.kt: heading, composer, rows,
  paging), `MoreVideosColumn` on the right (38%, 300–460 dp; All / From <channel> / Related `FilterPill`s by channel name);
  `Book` (open foldable) = the video across the whole width on top, under it details + comments on the left page and More videos
  on the right (0.5.87; option B had them the other way). The pill exists on wide windows only as the open player's bar, centred
  on the screen (0.5.87), fading in with the morph; tabs off. Panels (transcript) rest under the video over
  that pane. Black status strip bleeds over the rail (`statusBleed`). Tabletop (`TabletopPlayer`, Tabletop.kt; Derek: the bottom
  controls are ALWAYS pure black): video above the fold, deck below on #000: time / chapter / remaining, a 64 dp scrub surface of
  10 storyboard frames (drag anywhere, seeks on release, tap jumps; unplayed dimmed 62%, primary playhead with knobs, chapter
  gaps, landing label), keys speed · quality · −10 · play (76 dp primary) · +10 · captions · audio only · next. Replaces the page
  and fullscreen while the player is open; system bars hidden. Shorts on wide: never cropped, full height (`ShortPage`): centred on
  a tablet (title left margin, glass bar right), against the left edge on an open foldable (title + bar on the right page), over the
  short's own thumbnail blurred 48 dp at 55%; comments open in that margin (`shortsMargin`, which also places the bar and its
  channel card, assuming 9:16). Home on wide: `ShortsHomeCard` (next three shorts in one video-sized card, second grid spot via
  `videoCards(extra)`; signed-in Home only). Settings wide = list-detail (`SettingsScreen(wide)`: front page left 300–400 dp with
  the open tile lit, category in a surfaceContainerLow panel right; Back clears search then closes). Home chips (all devices,
  GitHub-style request from Derek): `InnerTube.parseHomeChips` (chipCloudChipRenderer + the newer view-model form; drop-down chips
  skipped), `FeedState.chips/chip/selectChip` (a chip's token reloads the feed via reloadContinuationItemsCommand; YouTube does NOT
  re-send the chip bar there, so the first page's row is kept), `HomeChips` as the list's first item (0.5.89, Derek picked B "Underline" in `docs/design/home-chips.html`: plain words in a horizontalScroll Row, chosen = primary + ExtraBold, a 4 dp rounded bar 60% of the word (≥18 dp) springing under it from each word's `onPlaced` spot, a pick near an edge scrolls it to the middle, the row fades 28 dp at whichever edge has more to scroll); fixtures
  home_chips.json / home_chip_feed.json are ANONYMOUS captures (a fresh visitor gets no Home at all; after a few watches the same
  request returns 21 chips). "Your custom feed" (YouTube's prompt-driven feed: a prompt box, no videos Skipii can read; Derek's 6632.png showed a blank page) is dropped by title, and so is "Live" (0.5.90, 6633.png: live streams can't play) — `SKIPPED_CHIPS`, hl=en keeps titles stable. 0.5.90 also drops live/upcoming streams from every `parseFeed` (`InnerTube.isLive`: a lockup's THUMBNAIL badge THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE; older renderers BADGE_STYLE_TYPE_LIVE_NOW / time overlay style LIVE|UPCOMING / upcomingEventData), as search (NewPipe `streamType`) already did. An avatar's `liveData` only means the CHANNEL is live: keep those videos (`liveDropped`). Upcoming lockups' marker (text UPCOMING) is a guess, no capture; any other chip whose page has no videos shows an EmptyState with a pill back to the plain chip (`FeedState.loadedEmpty`). NOT done: the custom feed itself (0.5.88; needs a signed-in capture of that chip's answer and of the prompt's submit request). Test: AVDs `skipii_fold` (5562; `adb emu fold|unfold`, `adb emu posture 2` = half open, `cmd window
  user-rotation lock 1` for tabletop) and `skipii_tablet` (Pixel Tablet, 5564, data partition 4G). Verified (debug; release
  playback on the tablet): rail, grids (2 on the Fold, 4 on the tablet), Book + Tablet players, cover → unfold continuity mid-video,
  tabletop deck, Shorts both ways, Settings list-detail, phone layout unchanged folded. NOT verified: signed-in Home (chips on
  screen, the Shorts card), a real Galaxy Fold / tablet, "controls on the video" mode on wide windows.
  0.5.87 (Derek, same day): the pill is CENTRED ON THE SCREEN in every layout (no longer over the details pane), so More
  videos pads for it too; and `Book` swapped back: details + comments on the LEFT page (up to the fold), More videos on the
  right (panels rest over the left page). The Fold's Shorts layout (short at the left edge, details right) was left as is.
- 0.5.91–0.5.93 zoom to fill (2026-11-02, Derek: "lots of users want it", like YouTube's app). Fullscreen only (`VideoContent` with
  `fullscreen`; PiP has its own surface and never zooms). Pinch out = fill the stage (the picture's layer scales by
  `fillScale` = max(stage ratio / aspect, its inverse), its clip with it; the root clips), pinch in = whole picture. The pinch is
  an Initial-pass pointerInput that takes nothing until TWO fingers pass touch slop, then consumes every change, so the tap
  handler, the brightness / volume slides and swipe down back off (their drags end on a consumed change); not while holding
  for 2×. Feedback is the picture's own motion, nothing drawn on it (0.5.93; Derek rejected a "Zoomed to fill" text pill,
  corner marks and 14 overlay mockups, then picked "Rubber band" in a round of bounce variants): the zoom (`Animatable`, 0
  whole … 1 fill) follows the fingers 1:1, past either end it stretches on with resistance (`stretched`, at most
  `ZoomStretch` 6% more / less), and on release it springs (ζ 0.5, k 380, the pinch's own velocity via VelocityTracker1D,
  capped) to the side the pinch's net direction picked (±4%, `ZoomDecide`; less goes back), bouncing once past it — below 0
  is a moment smaller than the whole picture. A CLOCK_TICK when the mode changes. Never add an overlay cue for it.
  Captions and the burst line stay on the unzoomed box. A pinch is for THAT video (`PlayerState.zoomedToFill`: the pinch for
  the current id, else `FullscreenZoom.fill`, Settings › Playback "Zoom to fill in fullscreen", prefs "playback" `zoom_fill`);
  changing the setting drops any pinch (`setZoomFillSetting`). Test: raw two-finger evdev (slots 0/1, as the double-tap note
  below) — `adb input` can't pinch; dismiss Android's "Viewing full screen" tip first.
- 0.5.94 square screens (2026-11-03, GitHub #38, a Unihertz Titan 2 Elite: 1080 × 1200): on a near-square phone
  (`onSquareScreen`, Adaptive.kt: the whole screen's long side ≤ 4:3 of its short side, so the bars don't count) fullscreen
  stays UPRIGHT for every video, the vertical-video path (`portraitFullscreen`); turning the phone sideways still gives
  landscape fullscreen. No keyboard check: any near-square screen gains next to nothing from turning. Large screens stay
  `free`. Test: `adb shell wm size 1080x1200` + `wm density 400` (back: `wm size reset` / `wm density reset`). Verified on
  skipii_qa (debug): upright fullscreen, letterboxed 16:9, Back exits; the normal 1080×2400 screen still turns landscape.
- 0.5.95 double tap to like Shorts (2026-11-03, GitHub #47): a double tap anywhere on a short likes it, never unlikes
  (`ShortsState.likeFromTap`; signed out only the sign-in note, no thumb). `shortTaps` (Shorts.kt) replaces the plain tap: a
  lone tap now waits out the double-tap timeout before pausing (as the main player's side taps); every further tap within
  `ThumbRunWindowMs` 600 ms of the last throws another thumb. `ThumbBurst`: the filled like glyph 104 dp box at the tap,
  random 8–20° lean, springs in from 0 with overshoot + the like button's eight sparks, holds 520 ms, floats 64 dp up and
  fades (`Motion.Exit`); a soft dark halo so it reads on bright frames. The bar's like icon pops by itself (its Flourish).
  Test with ONE evdev write holding both taps (separate `cat`s land ~300 ms apart = two lone taps). Verified on skipii_qa
  (debug, with a throwaway build faking the signed-in path): thumb, no pause, runs of 4, single tap pauses. NOT verified:
  the like reaching Derek's account (same `InnerTube.setLike` as the bar's button).
  0.5.97 (Derek: "my finger hides it"): the thumb's centre sits `ThumbAboveFinger` 96 dp ABOVE the tap (clamped
  `ThumbTopMargin` 24 dp from the page's top), never on it.
  0.5.98 (Derek: "a solid 2 seconds after switching shorts before I can double tap"): likes waited for
  `InnerTube.videoState`, asked only once a short was on screen. Now `tick` asks for this short and the next two, and a
  like with no state yet goes through at once (a stand-in state, Like); the answer, when it lands, keeps the local like /
  subscribe (`accountFor` merges). A failed like reverts only `like`. NOT verified signed in (no account on the emulator).
  Animation mockups in `docs/design/shorts-double-tap.html` (A–J). 0.5.99: Derek picked B · Confetti. `ThumbBurst` now
  plays the mockup's numbers in dp (it drew 0.72 px per dp), all read off ONE ms clock (`clock`, 1100 ms linear): the thumb
  runs `keyframe`s on CSS ease-out over the whole timeline (scale 0 → 1.2 → 0.97 → 1, lean 0 → −8° → 3° → 0, holds to 70%,
  then rises 42 dp and fades); 16 confetti (`Confetto`, per tap: every third a 8.3 dp dot, else a 5.6×12.5 dp strip that
  spins ±540°/s; colours #FF0033 #FFD000 #3EA6FF #FF4FB8 #7CF29A white) leave the thumb's centre 40 ms in, up and out
  (−170…−10°, 167–320 dp/s, gravity 722 dp/s²), for 900 ms, fading over the last 40%. Drawn in the thumb box's drawBehind
  (unclipped). Reduced motion: the thumb fades in and out, no confetti.
- 0.5.96 (2026-11-03, Derek's 6641.mp4): the player's bar came back from the comments cut off above a line, then popped in
  whole. MainActivity faded the NavPill's tab-bar-sized slot with layer alpha; alpha < 1 draws a layer offscreen CROPPED to
  its own bounds, and the player's bar stands taller than that slot (grown upward + lifted). The slot's layer now only
  translates; the fade is NavPill's `fade`, applied on the bar's own layer (with its shape/clip/shadow). Never put alpha
  on a layer whose content is placed or drawn outside its bounds.
- 0.5.100 channel page (2026-11-04, GitHub #53; Derek picked A "Underline tabs" in `docs/design/channel-page.html`, opening on
  Home, shorts in the Shorts pager). Channel.kt: profile, then `UnderlineTabs` (Home's chip row, now shared in Screens.kt)
  Home · Videos · Shorts · Playlists, pinned under the header once scrolled up to it (`CollapsingHeaderLayout`'s new `pinned`
  slot, told the header's bottom edge; under the status bar while the header is hidden). Data (Account.kt, anonymous via
  `postAny`; captures of 2026-11-04 in `ChannelParseTest`): `channelHome` = the page itself (tabs + their params by English
  title, `channelVideoPlayerRenderer` featured video, `shelfRenderer` / `reelShelfRenderer` shelves; See all from the shelf's
  browse endpoint: VL… = playlist page, the channel's own id = the tab its params name, `tabOfParams`), also the signed-in
  subscribe state + bell (replaces `channelSubscribed`). Videos = `channelVideos` with its first page's sort chips
  (Latest · Popular · Oldest, chipBarViewModel, read by `parseHomeChips`; a chip is a continuation, so `FeedState.selectChip`
  does the sorting). Shorts = `channelShorts` (`shortsLockupViewModel`), Playlists = `channelPlaylists` (`parsePlaylists`,
  shared with the Library's list). `InnerTube.playlist` is anonymous when signed out now. Home is hidden when it has nothing,
  Shorts tab / shelves while Shorts are off; without the page (it failed) only the uploads show, as before. A tab switch (or
  sort) while pinned keeps the tabs where they are: `holdTabs` re-places them as the new content arrives until a finger
  drags (the list anchors on the item under the header's edge, which the new content replaces, so it drifted); an empty tab's
  spinner / empty / error fills the page so it can scroll that far. A short tapped there: `ShortsState.openList(ShortsList)`
  plays the channel's list in the pager (the feed set aside in `saved`; `loadMore` pages through the channel; the bank waits);
  ‹ / Back (`leaveShorts`, MainActivity) reopens the channel page and `leave()` / `closeList()` put the feed back where it
  was. Playlists opened from a channel go over it (`playlistOverChannel`, zIndex; Back closes the playlist first).
  `ChannelState.list` is hoisted so the page keeps its scroll across that round trip. Verified on skipii_qa (debug, signed
  out): tabs, pinning + switches, Popular order, Shorts pager swipe + Back, playlist over channel, Shorts feed restored.
  NOT verified: signed in (subscribe state from `channelHome`), wide windows (grids), every translation on screen.
- 0.5.101 (2026-11-04, Derek, from GitHub issues). Subscriptions' channel row opens each channel (#37/#32; `ChannelRail` had no
  click). Shorts progress (#48/#44): the line moved from the screen's bottom edge into each PAGE (`ShortPage`, rides the swipe),
  centred `LineOverBar` under overlayBottom (12 dp over the glass bar), the words lifted `LineRoom`; on wide windows along the
  short's own bottom. A 36 dp strip grabs it: sideways past slop = scrub (consumed, so the pager and `shortTaps` stay out),
  up/down first = left to the pager, a tap = jump there (consumed: no pause). `ShortsState.scrubTo` / `endScrub`: paused
  (`scrub` in playWhenReady), CLOSEST_SYNC seeks while moving, EXACT on release; the line swells 3 → 6 dp with a knob and
  "0:39 / 0:59" over it, the words fade. Card feedback (#16): `InnerTube.feedbackOf` reads a card's ⋮ items that carry a
  feedbackEndpoint (lockup sheet `listItemViewModel` / `menuServiceItemRenderer`; icon NOT_INTERESTED / REMOVE, else the
  English label) into `VideoItem.feedback`; long-press rows Not interested / Don't recommend channel → `sendFeedback`
  (youtubei/v1/feedback {feedbackTokens}); `Dismissed` hides the video / the channel's videos in every `videoCards` at once,
  restored if the request fails. NOT verified signed in: anonymous menus hold only Add to queue + Share, so
  `CardFeedbackParseTest` grafts the items onto real anonymous cards — replace with a signed-in capture. Settings › Playback
  (PlaybackModes.kt, prefs "playback", now two joined groups): `SeekStep` 5–30 s in 5s (#36) for double tap, the bar's and the
  overlay's −/+, the tabletop deck and the session's increments (read when the service starts); every −/+ draws `seekIcon`
  (Material's 5/10/30; 15/20/25 built by `seekGlyph` from Material's arrows and digits — the 5's "5" shifted into the "0"'s
  place, measured from its paths — plus a "2" drawn as a 0.84 stroke in the digits' box, `twoAt`), labels `seekLabel` (plurals). `PlaybackSpeed.musicAtNormal` (#35, default on): `SpeedKeeper` gives music
  1× even when remembering (`YouTube.isMusic`: NewPipe `category` "Music" — present, checked on dQw4w9WgXcQ — or an
  "… - Topic" channel). `ChaptersStayOpen` (#46): the bar's chapter list doesn't fold on a pick. `AutoPip` (#42, default off):
  `PipController.AutoEnter` = setAutoEnterEnabled on 12+, the user-leave hint below, while a video plays with its picture.
  Verified on skipii_qa (debug): 15 s glyphs + summary, +15 jumps 15 s, chapter list stays, Home → PiP playing, Shorts line
  scrub / tap / swipe up from it, music 1× vs 1.5× remembered.
- 0.5.102 (2026-11-04, Derek): Settings' choice chips show the pick in full `primary` / `onPrimary` (secondaryContainer was
  too close to the unpicked tone in some themes). Subscriptions in upload order (GitHub #50): `InnerTube.parseSubscriptionsFeed`
  empties the "Most relevant" richShelfRenderer / shelfRenderer (by its English title) before parsing, then stable-sorts each page
  by `NewVideos.ageMs` (an ageless card keeps the age before it). NOT verified signed in: `SubscriptionsOrderTest` builds the web
  shape around real anonymous lockups — capture FEsubscriptions on Derek's account and replace it.
- 0.5.103 (2026-11-04, Derek's 6659.mp4: Subscriptions took ~4 s, its channel row ~6 s more and pushed the list down).
  Both were asked for only when the tab opened. `LibraryData.warmUp` (MainActivity, signed in) reads the Subscriptions feed
  and the row (FEchannels) once Home's first page is in (or after 6 s); `showSubscriptions` reloads a read-ahead page that is
  over 10 min old (or failed) the first time the tab shows. The row is kept in prefs "library" `channels` and shown from the
  first frame on the next start (refreshed once per session, `ensureChannels`; cleared by `reset`). NOT verified signed in.
- 0.5.104 Flip cover screen (2026-11-04, a Z Flip 6 user: "switch to the cover screen mid-video, it plays in full screen
  and I can't get out"). The cover screen (720 × 748) never turns, but its window came out wider than tall (the bars off the
  height; Samsung's / older Android's Configuration), so `landscape` = always fullscreen, and exit's PORTRAIT request couldn't
  turn a screen that doesn't turn. On a near-square screen (`onSquareScreen`, not in multi-window) MainActivity's `landscape`
  is now the display's rotation (`turnedSideways`, Adaptive.kt: ROTATION_90/270), never the window's shape. Repro on
  skipii_qa: `wm size 760x720` + `cmd window set-ignore-orientation-request true` (the old build opened every video
  fullscreen); fixed: normal player, swipe up / exit button / Back in and out; Titan size (1080x1200) turned sideways still
  goes landscape fullscreen; the normal phone unchanged. NOT verified: a real Flip.
- 0.5.105 bot-check panel (2026-11-04, Derek; stopgap until PO tokens): when `BotCheck` gives up, `PlayerState.botCheck` (set by `fail`
  from a SignInConfirmNotBotException, incl. `StreamRecovery.failed`, which now carries the Throwable) makes `ErrorPanel` show
  the message + airplane-mode hint (`player_error_bot_check_hint`; `friendlyError` joins both for pages / Shorts) and an
  Open in YouTube pill (`openInYouTube`: watch URL + `&t=`) beside Try again. Verified on skipii_qa with a throwaway build
  forcing the exception in `StreamCache.getOrLoad`: panel after the 3/8/20 s retries, the pill opened YouTube's app.
- 0.5.107 (2026-11-04, a Threads report: "only a single row of recommended videos" on a tablet held upright): `gridColumns`
  gives a wide window at least TWO columns. An upright 600–643 dp tablet has under 2 × `GridCardMin` (280) right of the rail,
  so it got one phone card stretched over ~520 dp. Test: skipii_tablet, `cmd window user-rotation lock 1` + `wm density 427`
  (600 dp across; back: `wm density reset`, `user-rotation free`). Open there too: the `Tablet` player puts More videos at its
  300 dp minimum, leaving the video ~216 dp wide on that screen (not changed; a design call).
- 0.5.108 Flip flex mode (2026-11-04, Derek's 6666.jpg; picked A "Two rows" in `docs/design/flip-flex.html`, refined in
  `flip-flex-a.html`): the tabletop deck's eight keys in one row crushed the five tools to ~18 dp slivers on a Flip (411 dp).
  `DeckButtons` (Tabletop.kt): narrower than `OneRowMin` 660 dp, −10 · play · +10 get their own row and the tools a second
  (68 dp keys); wider (the Fold) keeps one row. Transport bigger everywhere: ±10 80 dp (glyph 38), play 116 (glyph 54); deck
  side padding 20. A tool that's on is "Tint" (Derek: less obtrusive than full primary, not muted): `lerp(DeckKey, primary,
  0.24)` with primary ink. NOT verified on a real Flip (no Flip AVD; tested by forcing the narrow layout's width).
- Themes = Ledgii's (2026-10-05): Wallpaper (default on 12+), Cream Rose, Erica, Lilac Air, Jai,
  Pumpkin, Moonstone, Iris, Harbor + System/Light/Dark, picked in the account sheet's Appearance
  card (`ThemeSettings`, persisted). Mode chips System / Light / Dark / AMOLED (2026-10-14): AMOLED = dark with
  `ColorScheme.amoled()` (page pure black, containers pulled toward black but kept a step apart); use
  `themedScheme`, not `schemeFor`, wherever a scheme is shown. Palettes in Theme.kt are copied verbatim from
  ~/decio/ledgii/android/.../Theme.kt — re-copy on change; `completeLedgiiScheme` fills roles
  Ledgii leaves unset. Tab bar is 62 dp (Derek; tabs keep 50 dp targets); the page has Ledgii's bottom vignette
  (130 dp + system bar, page color 0 → 76% on a smoothstep ramp so its top edge is invisible; `vignetteBrush`, shared with the top vignette) under the mini-player and tab bar.

## Binding UI rules (inherited from Castii)
1. No tap ripple anywhere (`LocalRippleConfiguration provides null`); press feedback is
   `Modifier.pressable` (0.94 scale + spring).
2. Never animate a color to `Color.Transparent`; animate alpha.
3. In a Box inside a Column, fade with `animateFloatAsState` + `graphicsLayer`, not
   `AnimatedVisibility`.
4. Enter/Done/Search dismisses the keyboard on every field (see `SearchScreen.submit`:
   the window-level hide is posted ~120 ms later or the IME undoes it).
5. No fake or placeholder data in shipped UI; empty states are designed states.
6. Skips (SponsorBlock, later) fade audio — never hard-cut.
7. No 1 dp outlines/borders on cards, buttons, sheets or the nav — tone separates surfaces (Derek, 2026-10-04).
8. Re-tapping the selected tab scrolls its list to the top (`rememberScrollToTop`); fresh content
   (first load, refresh, new search) always starts at the top. Never key a list's trailing loader
   item — a keyed loader at index 0 makes LazyColumn land at the bottom when items arrive.
9. `Modifier.pressable(enabled = false)` attaches no click handler, so taps fall through (hidden
   video controls must not swallow the tap that reveals them).
10. Text color comes from `LocalContentColor` set in `SkipiiTheme`; never rely on Compose's black default.
11. Root-cause fixes only; `runCatching` around a side effect needs a fallback/state change.

## Build & verify
- `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- `./gradlew assembleDebug` before presenting any change; fix compiler output first.
- `./gradlew testDebugUnitTest` runs the InnerTube parser tests.
- The default emulator (emulator-5554) is shared with other sessions that send their own taps/Back/Home.
  For uninterrupted checks use the `skipii_qa` AVD: `emulator -avd skipii_qa -port 5560 -no-window`.
- Emulator note: it has "stay awake while charging" on; turn off `stay_on_while_plugged_in` before
  testing anything about screen timeout.
- Judge feel on `./gradlew assembleRelease` (R8). **Verify playback on the RELEASE build before
  shipping** — Derek installs release APKs. R8 once emptied NewPipeExtractor's audio streams in
  release only (360p fallback under a "1080p" label); the extractor is now kept whole in
  proguard-rules.pro. Check `adb logcat -s Skipii:I | grep "media source"` (should say
  "video itag … + audio itag …", never "combined fallback") and the quality sheet's "Playing …" line. Release is signed with the debug key,
  so it installs over debug builds.
- Double taps can't be driven with `adb shell input tap` (each call takes ~0.3 s). Use `adb root` and
  write raw evdev events (24-byte input_event structs: slot/tracking id/x/y scaled to 0..32767, touch
  major, pressure, BTN_TOUCH, SYN) for both taps in ONE file, then `cat` it to /dev/input/event1.
  They arrive as STYLUS, and Gboard may pop its stylus tutorial if one lands on a text field.
- An emulator is usually running (`adb devices`); install, drive with `adb shell input`,
  screenshot with `adb exec-out screencap -p`. Check playback via
  `adb shell dumpsys media_session | grep PlaybackState`.

## Map (`app/src/main/java/com/decio/skipii/`)
- `YouTube.kt` — models, search/details via NewPipeExtractor, `StreamCache` (shared by UI
  and service), `StreamSelection` (≤1080p, H.264 first, original audio track).
- `PlaybackService.kt` — MediaSessionService + ExoPlayer; resolves video IDs in
  `onAddMediaItems`; `YouTubeMediaSourceFactory` merges video+audio, each wrapped in a
  generated DASH manifest (avoids YouTube throttling), progressive fallback.
- `PlayerState.kt` — UI-side MediaController state; loads details then starts playback;
  restores the mini-player when reopened mid-playback.
- `PlayerMorph.kt` — the mini ↔ player transform: one `progress` (0 mini, 1 full) read only in
  graphicsLayer/draw lambdas; gestures drive it 1:1, release settles with a spring, taps use
  `Motion.Morph`. Geometry is computed in `MainActivity` from the shared constants in `PlayerUi.kt`.
- `PlayerUi.kt` — portrait watch screen (pinned video, details, account actions, comments card,
  More videos), mini-player, morph geometry constants.
- `VideoView.kt` — `VideoContent` (surface + overlay controls, shared by portrait and
  fullscreen), `FullscreenPlayer`, `QualitySheet`, `MorphPlayButton`, scrubber.
- `Fullscreen.kt` — `OrientationController` (portrait app; open player follows rotation, never
  upside down; button forces landscape and releases once the phone is physically turned), system
  bars, and `PipController` (picture-in-picture).
- `Comments.kt` — `CommentsState` (paging, replies, likes, names) and the card + panel.
- `CommentFeed.kt` — comments read from YouTube's own `next` pages (avatars, liked/hearted state;
  tested by `CommentFeedParseTest`) and `ChannelNames` (display names from channel RSS titles).
- `Account.kt` — `Account` (session, SAPISIDHASH auth, rotated-cookie merge) and `InnerTube`
  (browse feeds, channels, playlists, video like/subscribe state, writes, `startWatch` /
  `pingWatchtime` for history + watch time, driven by `WatchTimeReporter`) with a
  renderer-agnostic parser (videoRenderer family + lockupViewModel, continuationItem
  Renderer/ViewModel). Tested by `src/test/.../InnerTubeParseTest` on real captured responses —
  re-capture fixtures when YouTube changes layouts.
- `PlayerFallback.kt` — bot-check fallback for the extractor's ANDROID player request (see 0.5.16).
- `CommentPosting.kt` — create_comment / create_comment_reply. Params come from the signed-in
  comments pages: `createCommentParams` in the header; per-thread `createReplyParams` in the
  engagementToolbarSurfaceEntityPayload that each commentViewModel points to via
  `toolbarSurfaceKey`. Use `forEachObject` (entity mutations are array elements; `walk` skips them).
- `SponsorBlock.kt` — hash-prefix lookups, cache, settings. Skipping happens in
  `PlaybackService.SponsorSkipper` (PlayerMessage at each segment start, 300 ms fade out → seek →
  450 ms fade in; logs "SponsorBlock: skipping …"). UI: scrubber marks, Ask pill, Skipped·Undo.
- `PlaybackModes.kt` (`PlaybackSpeed`, `AudioOnly`) and `SpeedPanel.kt` (speed/audio buttons, ruler panel, `SpeedHaptics`).
- `Sheets.kt` — long-press options, queue card + sheet, SponsorBlock settings.
- `SecureStore.kt` (Keystore AES-GCM prefs), `SignIn.kt` (WebView sign-in, header account button),
  `Settings.kt` (the Settings page: folding title, search, quick toggles, categories),
  `Feeds.kt` (`FeedState` paging, `LibraryData`, `FeedList`).
- `MainActivity.kt` — shell: tabs, nav capsule, morphing player / fullscreen, sheets, sign-in and
  playlist overlays.
- `Screens.kt` — Home / Subscriptions / Search / Library.
- `Captions.kt` — caption tracks, srv3 parser, `CaptionSettings`, `CaptionTranslator` (ML Kit), `CaptionsState`.
- `CaptionsUi.kt` — `CaptionLayer` (on the picture), `captionOptions`, `CaptionsSheet`, `TranscriptPanel`, the account card.
- `Shorts.kt` — `ShortsState` (feed, preloading player pool, glass sampling), `ShortsScreen` (pager), `ShortsBar` (glass bar).
- `ShortsBank.kt` — the 10 unseen shorts kept whole on disk (cache, manifests, index); every Shorts player reads through it.
- `Updater.kt` — `AppUpdate` (daily GitHub release check, release notes, APK download + PackageInstaller), `UpdateReceiver`, Home's Update pill + What's new sheet.
- `Bell.kt` — `Bells` (each channel's bell, synced with the account), `SubscribeWithBell`, `rememberBellToggle`.
- `NewVideos.kt` — the 15-minute new-video check (RSS feeds of belled channels), notifications, `NewVideoWorker`.
- `NavBar.kt` — `NavPill`: the tab bar, which morphs into the open player's −10 · play · +10 · ⋮ bar.
- `Adaptive.kt` (`WindowLayout`: wide / fold / tabletop), `Rail.kt` (`NavRail`, wide windows), `Tabletop.kt` (`TabletopPlayer`, the half-open deck).
- `PlayerBar.kt` — the open player's bar: `PlayerBarState`, tools dome, quality list, scrub track, gestures.
- `UiKit.kt` — header, cards, expressive shapes (`CookieShape`), formatting.
- `AvSync.kt` — TextureView lip-sync measurement + `SyncedRenderersFactory`.
- `Language.kt` — `AppLanguage` (phone's or picked language, per-app locale), `str()` / `plural()` for code outside a composition.
- `Theme.kt` (wallpaper color, seed fallback), `Motion.kt` (curves, `pressable`), `Net.kt`.

## Roadmap
1. ✅ Shell, search, playback (DASH), overlay controls, mini-player, background audio.
2. ✅ Mini-player ↔ player container-transform morph (`PlayerMorph.kt`); swipe the video or the
   details list down to minimize, swipe the mini-player up to expand.
3. ✅ Comments (read-only), quality picker, landscape fullscreen + rotation.
4. ✅ Google sign-in, full sync: Home recommendations, subscriptions feed + channels, history,
   Watch later / Liked / playlists, like/dislike, subscribe, Save, watch-history sync.
   Verified: parsers on real responses, sign-in page loads. NOT verified: an actual sign-in
   (needs Derek's account) — first thing to check on device.
5. ✅ 0.3.0: Castii tab bar, live mini-player video, queue + long-press menu, comment/reply
   posting, SponsorBlock (verified on emulator: auto-skip with note, Ask pill, scrubber marks),
   scroll-to-top fixes, visible refresh, translucent play button, no minimize button.
   NOT verified (needs Derek's account): posting comments/replies, signed-in refresh.
6. ✅ 0.3.2 startup speed: Auto = one adaptive DASH set of the H.264 (else VP9) ladder ≤1080p
   (`StreamSelection.autoLadder` + `adaptiveVideo`), starts ~480p and climbs to 1080p in ~4 s;
   pinned qualities stay single-stream. 1 s start buffer (`DefaultLoadControl`), quality climbs
   after 2 s. Mixed-decoder adaptiveness MUST stay allowed — without it the 60 fps HD rungs are
   dropped and Auto sticks at 480p. Fixed cost: `StreamCache` dedupes in-flight extractions;
   prefetch on touch-down (`prefetchOnPress`) and top 2 of Home/Subscriptions; comments load
   after details (their request duplicated the critical-path `next`); video-server connection
   pre-warmed (`Net.prewarmMediaConnection`); NewPipe iOS client off. Screen stays on while
   playing (`keepScreenOn` from isPlaying); player wake mode NETWORK.
   Measure with `adb logcat -s SkipiiTime:I` (open → details → first frame → quality changes).
7. Downloads (audio-only mode ✅ 0.5.20, captions ✅ 0.5.75).
