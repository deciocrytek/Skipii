# Skipii changes

What each release brings, in a line or two per change, written for the people using the app.
`scripts/publish-apk.sh` posts a version's section as its GitHub release notes, and the app's
What's new sheet (tap Update on Home) shows them before installing.

Add every change someone would notice under **Unreleased**, under `### New`, `### Improved` or
`### Fixed`. Publishing renames Unreleased to the version. Leave out under-the-hood work unless it
changes something people see or feel (faster, fewer errors); then say that.

## Unreleased

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
