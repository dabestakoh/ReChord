# Audio Fidelity + Navigation Glass Freeze (preview)

Branch: `feature/audio-fidelity-ui-optimization`

## Scope
- Auris-inspired 0–100 playback-path fidelity score in the existing Audio Pipeline dialog (diagnostic only, **not** an objective sound-quality measure).
- Navigation-only glass freeze clock: holds a previously rendered backdrop for up to 500 ms after a destination change, then redraws on thaw. The freeze never changes the page content, playback, queue, or navigation transitions.
- The vendored glass backdrop draw node now reuses an existing capture while frozen. First capture, resize, and surface reposition always re-record. A lightweight coroutine polls for thaw without invalidating each frame.
- ReChord's existing glass visuals, high-performance mode, animations, snapshot backdrops, playlist features, and bit-perfect playback are unchanged.

## Source-to-DAC fidelity policy
- The active dedicated ReChord driver is authoritative, including USB_DIRECT and Android's verified USB bit-perfect mixer. Its published source/output precision, source/output sample rates, conversion flag, and exactness verdict override potentially stale ordinary AudioTrack telemetry.
- The 0–100 path score measures known alteration of **decoded PCM**, independent of original codec, lossy/lossless source, or compressed bitrate. 100/100 means no known PCM changes, not necessarily independently verified bit-perfect output.
- Separate verification status: VERIFIED requires exact active dedicated transport, matching known sample rates, sufficient PCM precision and no known processing. UNVERIFIED means no known modification but not enough endpoint proof, including ordinary or direct AudioTrack. MODIFIED means known sample alterations and score deductions.
- DSD-to-PCM conversion, DAC-limited conversion, resampling, active DSP, Bluetooth re-encoding and narrowed PCM precision lower score. Missing metadata blocks VERIFIED but does not automatically lower score.
- Scores remain software-path assessments, not independent measurement of the USB digital bus or the DAC's analog output. This only changes diagnostics and does not change how audio is routed or processed.

## Regression matrix
1. Enable Liquid Glass and switch Home / Library / Search repeatedly, including rapid tab changes.
2. Open and close albums and playlists; verify glass content refreshes within 500 ms and never remains stale.
3. Navigate while a 3,000-track playlist is open. Verify scrolling and sort/caching behavior are unchanged.
4. Open/close Now Playing and menus, and scroll while navigating; ensure mini-player controls and artwork remain live.
5. Switch between reduced dynamic blur, progressive blur, and high-performance settings; confirm the glass still appears correctly.
6. Verify Audio Pipeline scoring with built-in speaker, Bluetooth, USB DAC, direct/bit-perfect output, DSP enabled, DSP bypassed, and resampled audio.
7. Run unit tests `AudioFidelityTest` and `GlassMotionClockTest`; run the Android assemble task in CI.

## Measure before/after
On the *same phone* and same refresh rate, compare the existing main/release build with the preview branch after a warm-up. Repeat the same Home → Library → playlist → Back route several times with Liquid Glass enabled and compare janky frames, 90th/95th percentile frame durations, and CPU/GPU traces. On a connected Android device use:

```sh
adb shell dumpsys gfxinfo com.rechord.preview reset
# Perform navigation gestures
adb shell dumpsys gfxinfo com.rechord.preview framestats
```

The reported package must match the installed test APK. No claim of measured performance improvement is made until these tests are run.

## Feature branch CI
The branch-scoped `.github/workflows/fidelity-ui-preview.yml` workflow runs unit tests and builds `:app:assembleDevDebug` on pushes to this branch. Download the `ReChord-Fidelity-UI-Preview-<run>` artifact from that workflow's Actions run. Its package is `com.rechord.preview`; it does not replace production `com.music.rechord`. The build must finish successfully before installing the APK.
