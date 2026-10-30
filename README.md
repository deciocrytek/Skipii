# Skipii

An ad-free YouTube player for Android, built in Material 3 Expressive.

No ads. Use it signed out, or sign in with Google to get your own recommendations,
subscriptions, history and playlists, with likes, subscribes and watch history syncing back.
Sponsor segments get skipped with [SponsorBlock](https://sponsor.ajay.app).

| Search | Player | Mini-player |
|---|---|---|
| ![Search](docs/screens/step1-search.png) | ![Player](docs/screens/step1-player.png) | ![Mini-player](docs/screens/step1-mini.png) |

## Download

**[Skipii.apk](https://github.com/deciocrytek/Skipii/releases/latest/download/Skipii.apk)**: always the
latest release. Open it on your phone and allow installs from your browser. Updates install over
the previous version.

Publishing a new build (same link): bump `versionName`/`versionCode` in `app/build.gradle.kts`, then
run `scripts/publish-apk.sh`.

## Features

- **Search** YouTube with no sign-in.
- **Playback** up to 1080p, with video and audio fetched as separate streams through
  generated DASH manifests (avoids YouTube's throttling).
- **Player controls on the video:** skip ±10 s, a play/pause button that changes shape, a
  draggable scrubber, and controls that auto-hide.
- **Mini-player** that morphs into the player: the video grows out of the thumbnail. Swipe the
  player down to minimize it (it follows your finger) or swipe the mini-player up to expand.
- **Background audio** with a media notification; the mini-player shows the live video.
- **Queue:** press and hold any video for Play next / Add to queue.
- **Shorts:** a full-screen vertical feed (yours when signed in, a general one otherwise). Swipe
  between shorts, tap to pause; the tab bar turns into a frosted-glass bar with Like, Comments,
  Share and the channel (tap to subscribe). The next 10 unseen shorts are kept downloaded (about
  100–150 MB of cache), so they start instantly even right after the app opens; each one is
  forgotten once seen and the next is fetched in the background. Your regular video pauses and
  comes back when you leave.
- **SponsorBlock:** per category, Skip (with an audio fade and Undo), Ask (a Skip button), Show on the
  scrubber, or Off. Settings are in the account sheet.
- **Comments** with replies, a **quality picker** (Auto or any height YouTube offers, remembered),
  and **landscape fullscreen** by rotating the phone or tapping the fullscreen button.
- **Google sign-in (optional):** Home recommendations, subscriptions feed, history, Watch later,
  liked videos and playlists. Likes, subscribes, Save and watch history sync to your account.
  The session is stored encrypted with an Android Keystore key. Note: Google can suspend
  accounts used with unofficial clients; a secondary account avoids risking your main one.
- **Wallpaper colors** (Material You), Ledgii-style floating nav capsule, and no tap ripple.

## Roadmap

1. ✅ Shell, search, playback, overlay controls, mini-player, background audio
2. ✅ Mini-player ↔ player morph; swipe down to minimize, swipe up to expand
3. ✅ Comments, quality picker, landscape fullscreen
4. ✅ Google sign-in with full sync
5. ✅ Queue, comment posting, SponsorBlock, Castii-style tab bar
6. ✅ Shorts tab
7. Downloads, captions

## Building

Requirements: Android SDK (compileSdk 36) and JDK 17+. Android Studio's bundled JDK works:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # shrunk with R8, signed with the debug key
```

Judge smoothness on the **release** build; debug Compose runs unoptimized.

## Project layout

```
app/src/main/java/com/decio/skipii/
  YouTube.kt          search + video details (NewPipeExtractor), stream cache and selection
  PlaybackService.kt  Media3 session service, DASH/merging media sources
  PlayerState.kt      UI-side playback state over a MediaController
  PlayerUi.kt         watch screen, overlay controls, scrubber, mini-player
  MainActivity.kt     app shell: tabs, floating nav capsule, player sheet
  Screens.kt          Home, Subscriptions, Search, Library
  Shorts.kt           Shorts tab: feed, preloading player pool, frosted-glass bar
  UiKit.kt            shared components and expressive shapes
  Theme.kt, Motion.kt, Net.kt
docs/
  design/             approved mockup (open mockup.html in a browser) and iterations
  screens/            screenshots of the running app
CLAUDE.md             product decisions and working rules for AI-assisted development
```

## Credits

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor): YouTube data, GPL-3.0
- [AndroidX Media3](https://github.com/androidx/media): playback
- [SponsorBlock](https://sponsor.ajay.app): sponsor segments, CC BY-NC-SA 4.0 (planned)
- [essopsp](https://github.com/essopsp): the idea of a Shorts tab ([#10](https://github.com/deciocrytek/Skipii/pull/10))
- [@o0ir4](https://www.threads.com/@o0ir4): reported the layout on Arabic (right-to-left) phones (0.5.64)
- [nsk-47](https://github.com/nsk-47): found the gap under Appearance on large font sizes ([#24](https://github.com/deciocrytek/Skipii/issues/24), 0.5.65)

## License

GPL-3.0. See [LICENSE](LICENSE). Required because NewPipeExtractor is GPL-3.0.

Skipii is not affiliated with or endorsed by YouTube or Google. It's a personal project for
sideloading, not distributed through the Play Store.
