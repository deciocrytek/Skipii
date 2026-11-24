# Skipii changes

What each release brings, in a line or two per change, written for the people using the app.
`scripts/publish-apk.sh` posts a version's section as its GitHub release notes, and the app's
What's new sheet (tap Update on Home) shows them before installing.

Add every change someone would notice under **Unreleased**, under `### New`, `### Improved` or
`### Fixed`. Publishing renames Unreleased to the version. Leave out under-the-hood work unless it
changes something people see or feel (faster, fewer errors); then say that.

## Unreleased

## 0.6.1

### New
- **Downloads.** Keep a video, or just its sound, on your phone and watch it with no connection, captions and SponsorBlock skips included. Tap **Download** under the player (or long-press any video) and pick a quality; the sheet shows each size. Find them under **Library › Downloads**, swipe one away to delete it (with Undo). In **Settings › Downloads** you can have your queue and the first five of Watch later download by themselves, keep those to a space limit, delete them once watched, and download only on Wi-Fi. Downloaded videos play from the phone even when you're online, so they start instantly and use no data.
- **Better search.** Suggestions as you type, a channel you follow at the top when its name matches, and **All / Videos / Channels / Playlists** chips. **Filters** narrows by upload date and length and can sort by popularity; the filters you've set show as chips you can remove one by one.
- **Feed filters** for Home and Subscriptions: hide videos you've watched, hide short ones, mute words, and mute channels (long-press a video › Mute this channel, with Undo).
- **Subscription groups.** Make groups of channels ("Music", "Tech"…) and switch between them with chips at the top of Subscriptions; an **Unwatched only** chip shows only what you haven't seen. Shorts from your subscriptions can show in a row there too (#96).
- **Watch later** gets length chips (under 10 min, 10–30, over 30), a Hide watched chip and a **Pick one for me** button.
- **Sort comments** by Top or Newest. Double-tap a comment to like it (signed in).
- **Fullscreen, tidier:** speed, quality, captions and audio language now sit behind one gear button (a dot shows when one isn't at its default). Swipe up in the middle of the video for a drawer with your queue and more videos, without leaving fullscreen; tap to play, long-press to queue.
- **Home screen widgets:** the **Skipii player** (what's playing, progress with SponsorBlock marks, −10 · play · +10, and Continue watching), **Up next**, and a **Search** shortcut. They take your wallpaper's colours.


### Improved
- **Shorts from your subscriptions** now show on Home too (above your recommendations) when that switch is on in Your feed.
- The **Your feed** button on Home and Subscriptions stays plain instead of turning coloured while a filter is on.

## 0.5.129

### New
- **Search inside Watch later and Liked videos.** A search field sits at the top of each list and searches every video in it, by title or channel, even in lists with thousands of videos. Play all and Shuffle are gone from these two lists.
- **Search your History.** This uses YouTube's own history search, which only covers your recent history, so very old videos may not turn up.
- **Dubbed audio.** When a video has other audio languages (a creator's dubs, YouTube's auto-dubs, audio description), an **Audio** button lets you switch, and the video carries on from the same spot. Find it under ⋮ in the player bar, next to speed on the video, or in fullscreen. Videos always start in their original language unless you pick a language in **Settings › Captions & Dubbing › Audio track**. YouTube's AI auto-dubs are only picked for you if you allow them there. Videos with a single audio track look and sound exactly as before.

### Improved
- **Watch later and Liked videos are back to their tiles** in Library, each in its own bold colour so they stand out from the page in light and dark themes.

### Fixed
- **Swiping down to leave fullscreen** (#94) no longer slides the video down and shows the top of the Home page behind it. The video now shrinks in place over black as you pull, the mirror of how it grows when you swipe up into fullscreen, and eases back if you let go early.
- **Leaving fullscreen is quicker.** Skipii now asks the phone to turn back the moment you let go, and brings the status and navigation bars back first, so the pause before the screen turns is about a quarter shorter.
- **A rare crash when opening a video** while YouTube was checking the connection is fixed.

## 0.5.120

### New
- **Open YouTube links in Skipii** (#59). Tap a youtube.com or youtu.be link in another app, or share one to Skipii: videos and Shorts play (from the time in the link), channels and playlists open. To make Skipii the default, go to **Settings › About › Open YouTube links** and add the links under "Open by default".
- **Play all** and **Shuffle** on every playlist (#81). The rest of the playlist goes into your queue.
- **Remove videos from Watch later and your own playlists** (#65, #85). Swipe a video left, or long-press it and choose **Remove from …**. **Undo** brings it back.
- **Screen lock in fullscreen** (#63). Tap the lock at the top left and the video ignores touches, so nothing seeks or swipes by accident. To unlock, tap anywhere and hold the **Hold to unlock** button, or press Back.
- **Background play** switch in **Settings › Playback** (#68). Turn it off and the video pauses when you leave Skipii or the screen turns off. Audio only keeps playing either way.
- **Word by word** captions in **Settings › Captions** (#87, #7). Auto-generated captions then appear as each word is said, as in YouTube's app. There's also a bigger **Giant** caption size.
- **Swipe a video right to add it to your queue**, on every list of videos: Home, Subscriptions, Search, channels, playlists, History and More videos under the player. It takes a real swipe, about a third of the way across, so scrolling or a quick flick never queues anything by accident. A "Queued #2" badge on the thumbnail shows its place in line.
- **Rearrange your queue**: drag a video by its handle, or press and hold it anywhere and move it. Swipe a video left to take it out. The queue also shows how many videos it holds and how long they run.
- **Up next**: in the last seconds of a video, when something is queued, a countdown shows what plays next. Play it now, or Cancel to stop at the end of this one.
- **Library** starts with **Continue watching** (videos you didn't finish, with the time left) and a link to your whole **History**. Watch later and Liked sit side by side, and your playlists show their covers.
- The player bar briefly shows the time and chapter when a new chapter starts or after you jump back or forward. Tap it to open the chapter list.

### Improved
- Hold anywhere on the video for 2×, not just on the right side (#51).
- Timestamps in comments are links (#66): tap "4:21" to jump there. Web links in comments open too.
- A video title longer than three lines shows one line with an arrow; tap it to see the whole title and the description (#34).
- When "Subscriptions" doesn't fit in the tab bar (big fonts, narrow phones), it says "Subs" instead of being cut off (#39, #70).
- Sharper thumbnails on tablets, open foldables and TVs (#18).

### Fixed
- Captions that fail to load now try again with fresh links before giving up (#64). When translating them is what fails, the message now says so instead of "Couldn't load these captions."
- Links in a video's description show the whole address (YouTube cuts long ones short with "...") and can be tapped: a timestamp jumps there in the video, another YouTube video plays in Skipii, a channel or @mention opens its page, a #hashtag searches, and any other link opens in your browser.
- Chapter names in the player bar's chapter list show in full, on as many lines as they need, instead of being cut off after one line.
- Going back from a playlist slides it away, as a channel page does. It used to vanish with no animation.
- A video added to the queue while YouTube was checking the connection is kept, and loads when its turn comes. It used to disappear even though Skipii said "Added to queue".

## 0.5.114

### Fixed
- Skipii no longer closes by itself when you open it again after leaving it with Back.

## 0.5.113

### New
- **Swipe to adjust brightness** and **Swipe to adjust volume** in **Settings › Playback** (#62, thanks @sushi1579). Turn either one off if you set it off by accident; that side of the fullscreen video then works like the middle (swipe down to leave fullscreen). Both stay on unless you change them.

## 0.5.112

### New
- **Android TV and Google TV.** Skipii now shows up in your TV's apps and works with the remote. Pages have the tabs down the left and four videos a row; the focused card lifts so you always know where you are. Videos play across the whole screen: press any button for the controls, ◀ ▶ to skim with a preview of where you'll land, OK to pause, ▼ for More videos, and the side panel for captions, speed, quality and chapters. Shorts change with ▲ ▼.
- **Live activity** in **Settings › Playback**. Turn it on and leave Skipii while a video plays: its controls (−10 s, play/pause, +10 s), the thumbnail and a progress bar sit at the top of your notifications and on the lock screen, and a chip in the status bar counts down the time left. Tap it to go back to the video. Needs Android 16 QPR1 or later (Pixel, One UI 8); the usual media controls stay as they are.

### Improved
- AMOLED is now a switch in **Settings › Appearance** instead of a fourth mode. Turn on **AMOLED black** and dark mode is pure black, whether you picked Dark or System (when your phone is dark). If you used AMOLED before, you're on Dark with the switch on.

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
