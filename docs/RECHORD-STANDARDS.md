# ReChord engineering standards

Use these standards when planning or implementing ReChord changes. They capture decisions made during the project’s playback, USB DAC, UI performance, Local Music, and Home work. The current approved feature plan defines the scope for each task.

## Coding

- **Investigate before editing.** Trace the normal entry point, state owner, callers, and recovery path. Use the relevant platform or library documentation for integrations. Diagnose intermittent issues with logs or reproduction where possible. Keep confirmed behavior and hypotheses clear.
- **Keep the change small and complete.** Reuse existing Compose components, repositories, indexes, caches, playback queues, and settings patterns. Preserve other consumers of shared code. Avoid dependencies, architecture changes, cleanup, or extra features outside the approved scope.
- **Preserve compatibility.** Consider upgrades with existing settings, indexes, playlists, history, cache records, and restored playback. Use stable IDs for persisted choices where available. Make migrations safe to retry and retain fallbacks for older data. Keep identifiers needed by installed apps, backups, invite links, and device pairing. Use ReChord in user-facing copy and retain upstream credits.
- **Make asynchronous state safe.** Bound network fan-out, reuse work, and cancel obsolete requests. Guard late results with the active account, route, or playback identity as appropriate. Retain valid cached data through transient failures and isolate optional work from the primary operation.
- **Verify the regression.** Run the closest existing checks and add focused coverage where the failure could recur. Update all supported Android locale catalogs when adding translatable settings text. Fix the cause of CI failures and retain checks for APK signing, package identity, and version.

## UI

- **Keep ReChord’s visual language.** Use its typography, glass surfaces, controls, touch targets, spacing, and theme colors. Borrow useful interaction patterns from other apps and adapt them to ReChord. Use short labels that describe what actually happens. Keep upstream credits in the README when their work informs a feature.
- **Design the loading state with the finished layout.** Use one stable skeleton matching the actual sections and dimensions. Reveal content together when ready. Keep the app usable when optional artwork or recommendations are slow or unavailable. Hidden items leave no spacer, skeleton, or dead controls.
- **Check placement on real layouts.** Verify 720 × 1280 as the minimum DAP test resolution and 1080p on phones. Record density and text scale when testing emulators. Test light and dark themes, large text, narrow widths, and scrolling with the mini-player visible. Verify that row labels wrap beside switches, primary actions clear the mini-player and navigation, and popups stay fully inside the viewport.
- **Scope controls to the feature.** Use the existing settings hierarchy and persist choices across launches. Home visibility changes apply immediately and preserve the order of remaining shelves. Keep disabled recurring sections manageable. Exclude transient track, artist, and album promotions from Home settings.
- **Keep scrolling lightweight.** Use lazy lists and stable keys. Avoid disk reads, artwork decoding, network work, or fast-changing playback state at the list level. Preserve section position during refresh. Pause costly artwork effects where existing scroll or visibility handling supports it.
- **Keep Local Music browsable.** Reserve a right gutter for A–Z and place its drag indicator inside the viewport. Use the alphabet rail where lists are alphabetical. Keep Recently Added newest-first and capped at 100. Preserve Artist → Album → Tracks and the Album Artist view. Put library refresh in the existing menu.

## Logic

### Playback, history, and audio output

- **Keep playback modes distinct.** Direct Source Stream should start with the lossless source when available, including cold resume from a nonzero position. Seamless Quality Upgrade may start promptly on its initial stream and upgrade in place. Reuse safe source lookup or prefetch where possible, without starting playback early.
- **Preserve the listener’s action and position.** Play and Shuffle should start the intended queue without cycling through tracks while source lookup is pending. Handle failure and retry at the responsible layer. Verify next track, seek, restart, and recovery across supported output paths.
- **Report the actual audio path.** Check built-in DAC, standard Android USB, Android 14 USB DAC, and USB Direct separately against their respective APIs. Keep source, decoder, resampler, sink, and hardware format claims tied to the active track and route. Verify sample-rate transitions in both directions, device reconnect, and fallback. Use **PCM Preserved** where supported by the actual path. Reserve **bit-perfect** for verified conditions. Avoid exposing internal failure codes as user-facing DAC copy.
- **Preserve YouTube Music history.** Signed-in, online playback with a YouTube video ID should continue reporting to the same account when the stream upgrades to lossless. Verify a qualifying play appears once and that watch time continues. Local files without a YouTube ID stay in on-device history. Keep local tracking independent of remote reporting failures.

### Home, Explore, and Local Music

- **Build recommendations from existing listening.** Combine on-device ReChord history and signed-in YouTube Music history. Sample liked, recent, and frequent listening across artists, tracks, and albums. Give playlist membership a smaller, bounded boost and count duplicates once. Keep candidate lookup bounded and personalized features usable when one data source is unavailable.
- **Keep discovery distinct.** Try YouTube Music Related before radio and try alternate seeds when results are sparse. Prefer unheard tracks, cap repeat artists, and deduplicate across Featured cards and Daily Discover. Keep the visible carousel stable while browsing and rotate the picks on refresh.
- **Give each screen a purpose.** Featured highlights Continue Listening, releases from familiar artists, and a paged set of song recommendations with up to two artist recommendations. Home leads with Recently Played, Quick Picks, and Find Your Mood, then discovery, library, and community shelves. Top Songs and Trending default off. Music Videos, New Music Videos, and one-off promotions stay out of Home and its visibility controls. Explore carries the broader New Releases catalog, including unfamiliar artists.
- **Index local files incrementally.** Open Local Music from its index and add or update changed files without reloading the whole library on every visit. Use embedded track artwork first, then supported album-folder artwork such as cover, folder, or album JPEGs. Reuse cached thumbnails and a consistent fallback. Verify that external system playback surfaces can read published artwork.

## Delivery and verification

- **Respect approval boundaries.** For investigation and plan-only requests, inspect and report. After approval, implement the agreed scope. Treat a local-only instruction as an explicit exception to the normal delivery flow.
- **Deliver a testable build.** After completing an approved app implementation, commit and push the feature branch, manually start the Preview APK workflow, and send the commit and run links. Confirm the run was created for the intended commit. Report its status and return control to the tester without waiting for it to finish, unless asked to monitor it. Merge or release only when requested.
- **Use representative verification.** Cover fresh installs and upgrades with existing data, signed-in and signed-out states, offline or slow requests, and the affected playback and UI paths. For hardware or service behavior, use real devices and accounts when available. Clearly state what was verified with fixtures or an emulator and what still needs live verification.
