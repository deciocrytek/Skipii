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
  The account-suspension risk was explained; keep the in-app warning. Signed out, everything
  still works except the account feeds. Signed in, comments and replies can be posted.
- No inbox/notifications tab.
- Portrait: every playback control lives in the player's bar (0.5.34, PlayerBar.kt); the video keeps
  only double-tap seek, the spinner, SponsorBlock Ask/Skipped and errors. Chapters live on the bar's scrub
  track (never as a card under the video). Fullscreen keeps its overlay controls. The related list is
  "More videos", never "Up next".
- No watch-time stats in Library.
- Never add an ad SDK. SponsorBlock: per-category Skip / Ask / Show / Off (defaults: sponsor
  Skip; self-promo + reminder Ask; intro/outro/preview Show; music/filler Off). Settings and the
  CC BY-NC-SA credit live in the account sheet (Derek's choice). No commercial use.
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
- `SecureStore.kt` (Keystore AES-GCM prefs), `SignIn.kt` (WebView sign-in, account sheet),
  `Feeds.kt` (`FeedState` paging, `LibraryData`, `FeedList`).
- `MainActivity.kt` — shell: tabs, nav capsule, morphing player / fullscreen, sheets, sign-in and
  playlist overlays.
- `Screens.kt` — Home / Subscriptions / Search / Library.
- `Shorts.kt` — `ShortsState` (feed, preloading player pool, glass sampling), `ShortsScreen` (pager), `ShortsBar` (glass bar).
- `ShortsBank.kt` — the 10 unseen shorts kept whole on disk (cache, manifests, index); every Shorts player reads through it.
- `Updater.kt` — `AppUpdate` (daily GitHub release check, APK download + PackageInstaller), `UpdateReceiver`, Home's Update pill.
- `NavBar.kt` — `NavPill`: the tab bar, which morphs into the open player's −10 · play · +10 · ⋮ bar.
- `PlayerBar.kt` — the open player's bar: `PlayerBarState`, tools dome, quality list, scrub track, gestures.
- `UiKit.kt` — header, cards, expressive shapes (`CookieShape`), formatting.
- `AvSync.kt` — TextureView lip-sync measurement + `SyncedRenderersFactory`.
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
7. Downloads, audio-only mode, captions.
