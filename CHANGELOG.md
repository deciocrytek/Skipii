# Skipii changes

What each release brings, in a line or two per change, written for the people using the app.
`scripts/publish-apk.sh` posts a version's section as its GitHub release notes, and the app's
What's new sheet (tap Update on Home) shows them before installing.

Add every change someone would notice under **Unreleased**, under `### New`, `### Improved` or
`### Fixed`. Publishing renames Unreleased to the version. Leave out under-the-hood work unless it
changes something people see or feel (faster, fewer errors); then say that.

## Unreleased

## 0.5.109

### New
- **Crash report** in **Settings › About** (#55). If Skipii closes by itself, open it again, tap Crash report and paste what it shares into a GitHub issue. It says what went wrong and how Android closed the app, with your phone's model and Android version, and nothing about your account or what you watched. Settings › About says "Crash report saved" after a crash.

## 0.5.108

### Improved

- Flex mode on a Galaxy Z Flip: the controls under the fold no longer squeeze into slivers. Play and skip are bigger, and the other buttons get a row of their own with their names in full.
- Flex mode: buttons that are on (captions, a changed speed, audio only) are now a soft tint instead of a bright fill, so play stays the one that stands out.

### Fixed

- Tablets held upright now show videos two to a row, instead of one huge card across the screen.

## 0.5.106

### New
- Channel pages look like YouTube's (#53, thanks @usa2525-code). Tabs for **Home**, **Videos**, **Shorts** and **Playlists** sit under the profile and stay at the top as you scroll. Home shows the channel's featured video and its own rows (latest uploads, playlists, featured channels), each with See all. Videos can be sorted Latest, Popular or Oldest.
- Tapping a short on a channel plays it in Shorts, and swiping goes through that channel's other shorts. Back takes you to the channel again, and your Shorts feed is where you left it.
- A channel's playlists open even when you're signed out.
- Drag along a short's progress bar to go anywhere in it (#44, thanks @ganapathi-subramanian). The bar thickens under your finger and the time shows above it; a tap on the bar jumps there.
- **Not interested** and **Don't recommend channel** in the long-press menu of the videos YouTube recommends you when you're signed in (#16, thanks @RaulKong878). The video, or every video from that channel, leaves your feeds at once, and YouTube learns from it.
- Choose how far a double tap and the back / forward buttons jump, from 5 to 30 seconds in steps of 5, in **Settings › Playback** (#36, thanks @jm11x). The buttons show the number you picked.
- Music plays at normal speed even when you keep a speed for every video, and the next video that isn't music is back at your speed (#35, thanks @risingsword). You can turn this off in **Settings › Playback**.
- **Keep chapters open** in **Settings › Playback** (#46, thanks @Suhareb): picking a chapter leaves the list open, so you can jump from one chapter to the next.
- **Picture-in-picture when you leave** in **Settings › Playback** (#42, thanks @ganapathi-subramanian): go home while a video plays and it keeps playing in a small window. Off unless you turn it on.

### Improved
- When YouTube asks to confirm you're not a bot, the player now says what usually fixes it (turning airplane mode on and back off) and has an **Open in YouTube** button that opens the video in YouTube's app from where you were, next to Try again.
- Subscriptions opens faster. Your subscriptions are loaded in the background once Home is in, and the row of channels at the top is there right away instead of appearing a few seconds after the videos.
- Subscriptions shows your channels' videos newest first, in the order they went up. YouTube's own "Most relevant" picks no longer sit at the top (#50).
- In Settings, the option picked in a row of choices (like Player controls or Skip back and forward) is now filled in with your theme's colour, so it's easy to tell which one is on.
- The progress bar in Shorts sits just above the buttons, under the title, where it's easier to see (#48, thanks @Suhareb).

### Fixed
- On a Galaxy Z Flip's cover screen, videos no longer get stuck in full screen. The player opens normally there, and the full screen button goes in and out again.
- Tapping a channel at the top of Subscriptions now opens it (#37, thanks @Rooknee56, and #32, thanks @ganapathi-subramanian).

## 0.5.99

### New
- Double tap a short to like it, as on YouTube (#47, thanks @Suhareb). A thumbs up pops out just above where you tapped, clear of your finger, with a burst of confetti; keep tapping for more. A double tap never takes a like away, and a single tap still pauses.

### Fixed
- Liking a short (double tap or the like button) works the moment it comes on screen. It used to do nothing for about two seconds after each swipe.
- Closing the comments brought the player's controls back with their top half cut off for a moment. They now come back whole.

## 0.5.94

### Fixed
- On near-square phones like the Unihertz Titan 2 Elite and other keyboard phones, fullscreen no longer turns the screen sideways. The video fills the screen the way you're holding it, and you can still turn the phone sideways for landscape.

## 0.5.93

### New
- Tablets and foldables get a layout of their own. On a wide screen the tabs move to a rail down the left, feeds become grids, and the mini-player waits in the corner.
- Watching on a tablet: the video, its details and the comments scroll together on the left, and More videos stays in its own column on the right, with filters for the channel's own videos and the rest.
- On an open Galaxy Fold or Pixel Fold the video runs across the top, the details and comments sit on the left page, and More videos on the right. The playback controls stay centred, as on a phone.
- Stand an open foldable half open on a table and the video takes the top half while the controls lie on the bottom half on pure black, with a strip of preview frames you drag across to jump anywhere.
- Shorts on a big screen play as big as the screen allows, never cropped, with the title and buttons beside them, and the comments open beside the short. On Home they're a single card, not a shelf.
- Settings on a big screen shows its categories on the left and the one you pick on the right.
- Home shows YouTube's categories (All, Music, Gaming and more) at the top, on phones too (#35, thanks @risingsword).
- Zoom to fill in fullscreen, as in YouTube's app: pinch out on the video to fill the whole screen (the edges are cropped), pinch in to see it whole again. A pinch counts for that video only. To have every video fill the screen, turn on Settings › Playback › Zoom to fill in fullscreen (#41, #43, thanks @ganapathi-subramanian and @risingsword).

### Improved
- Tablets and open foldables are no longer held in portrait: turn them any way you like.

### Fixed
- Live streams no longer show up in Home, Subscriptions, channels or playlists. Skipii can't play them yet, so they only led to an error (search already left them out).
- Foldables: folding or unfolding keeps you where you were. The same video or short carries on playing, on the same page, instead of starting over on Home.
- Galaxy Z Fold: a video no longer gets stuck sideways in fullscreen on the cover screen after you fold the phone. The screen you switch to follows how you're holding it.
- Coming back to the app while a video plays in the background now loads that video's comments again.

## 0.5.84

### New
- A real Settings page. Tap your profile picture (or the person icon) at the top of Home, Subscriptions or Library. Search finds any setting, and tapping a result takes you straight to it. Audio only, SponsorBlock, Shorts and Keep speed switch on and off right from the top of the page. Everything else sits in categories: Playback, Captions, SponsorBlock, Shorts, Appearance, Language, Notifications, About and your account.
- Notifications lists every channel whose bell is on, so you can turn bells off in one place.
- About shows your version and checks for an update when you tap it.
- Say hi to the developer from the bottom of Settings: Instagram, Threads, X, or an email.

## 0.5.80

### New
- Skipii in your language: Spanish, Portuguese, French, German, Italian, Indonesian, Turkish, Vietnamese, Russian, Arabic, Hindi and Japanese. It follows your phone's language; pick another in the new Language card at the top of the account sheet (on Android 13 and later also in Settings › Apps › Skipii › Language). Views, subscriber counts and "3 days ago" are written in your language too. Videos, titles and comments stay as their creators wrote them.

## 0.5.78

### Improved
- Captions show like YouTube's: two whole lines at a time, switching straight to the next two as they're said, instead of one word at a time.
- The captions button turns captions on with one press; the list it opens is there to pick another language.
- The two caption lines sit together in one box, with no gap between them.

## 0.5.75

### New
- Captions. Every track a video has (the creator's, other languages, auto-generated) in ⋮ › Captions in the player's bar, or the CC button on the video and in fullscreen. Auto-generated captions appear word by word as they're said.
- Captions in any language, translated on your phone when the video doesn't have yours (a one-time download of about 30 MB per language, then it works offline).
- Captions turn themselves on when a video isn't in your phone's language. Change that, their look (Skipii, Classic, Outline or Android's own caption settings) and their size in the new Captions card in the account sheet.
- Transcript: every line of a video's captions, under the video like the comments. The line being said lights up as it's said, tap any line to jump there, and search finds a word.
- The bell. Once you're subscribed, a bell sits next to Subscribed (on the video, the channel page and the Shorts channel card). Turn it on and Skipii sends a notification for every new video from that channel; tap one to play it. It's your YouTube account's own bell ("All"), so channels you've already belled in the YouTube app show up within a day, and turning it on here turns it on there.

## 0.5.74

### New
- Hold a finger on the right side of the video to play at 2×, in portrait and fullscreen. Let go and it goes back to the speed it had (a paused video plays while held, then pauses again) (#11, #26, #32, #36, thanks @usa2525-code, @hpqs2024-sys, @ganapathi-subramanian and @jm11x).

### Improved
- Save to playlist opens to the same height as the comments, just under the video, in one smooth motion, and scrolls inside.
- The player's Save (Watch later) button is gone: Watch later is the first row of Playlist.
- Picking a playlist animates (the check pops in), "Saved to …" shows right over the list, and the video above stays bright while it's open.

## 0.5.69

### Fixed
- In fullscreen, Speed opens a small list of speeds under its button, like Quality, instead of a sheet over the whole video. "Every video" at the bottom keeps the speed for every video (#31, thanks @nsk-47).
- Swiping the video down to the mini-player with the comments open now takes the comments along and closes them, instead of leaving them open over the mini-player (#33, thanks @BaooRaami).

## 0.5.68

### New
- Tapping Update now shows what's new in the update first, with the Update button under the list (#30, thanks @usa2525-code).
- Player controls can sit on the video instead of the playback pill (account sheet → Player controls). On the video, the buttons look like the pill's with a round play button, and the video's bottom edge is the progress bar: drag its dot to scrub, with a preview of where you'll land (#28, thanks @BaooRaami).

### Improved
- Fullscreen's controls match the new look: round play button, pill-style buttons, and a slimmer progress bar with gaps between chapters.
- Thumbnails in the feeds are wider, closer to the screen's edge (#27, thanks @nsk-47).
- On narrow phones the channel name gets its own line, so views and upload time are no longer cut off (#27, thanks @BaooRaami).

### Fixed
- In fullscreen, Quality opens a small list under its button instead of a sheet over the whole video (#25, thanks @nsk-47).
