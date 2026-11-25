# ReChord Upstream Sync Policy (BitChord)

This document is the canonical record of which upstream **BitChord** (`kushagrasinghx/BitChord`) features ReChord intentionally omits, which upstream features are scoped or gated, and which ReChord implementations always take precedence during future upstream syncs.

Read this guide alongside [RECHORD-STANDARDS.md](RECHORD-STANDARDS.md) before assessing, planning, or porting any future BitChord release.

---

## 1. Intentionally Omitted Upstream Features (Do Not Pull)

Do **not** port or reintroduce any of the following upstream features, dependencies, routes, settings, or string keys unless explicitly re-authorized by the maintainer:

| Upstream Feature | Why ReChord Omits It | Files, Symbols, Dependencies & Keys to Skip |
| --- | --- | --- |
| **Google Drive Backup & Cross-Device Replay Sync** | ReChord keeps backups local and avoids adding Google Play Services Auth / cloud sync dependencies. | `DriveAuth`, `DriveApi`, `DriveBackup`, `ReplaySync`, `PeerListening`, `DriveBackupScreen`, `DriveDevicesScreen`, `DriveBackupAlerts`, `DriveBackupAlertsHost`, `SyncPlannerTest`, `AppRoute.DriveBackup`, `AppRoute.DriveDevices`, `com.google.android.gms:play-services-auth`, `drive_*` string keys, Drive exclusions in `backup_rules.xml` / `data_extraction_rules.xml`. Keep ReChord's local Export/Import in `Backup.kt`. |
| **On-Demand AutoMix AI Model Downloads** | APK size is not a concern; bundling `.onnx` models directly inside the APK guarantees offline AutoMix with zero external hosting or download failures. | `AutomixModels`, `ignoreAssetsPattern` excluding `*.onnx` in `app/build.gradle.kts` and `tv/build.gradle.kts`, stripped `onnxruntime-android-*-automix.aar`, `native/onnxruntime/*`, `automix_model*` string keys, and model-download changes in `TrackAnalyzer`, `BeatTracker`, `VocalTracker`, `SettingsSheet`, and `TvSettingsScreen`. Keep `beat_this_final0_q8.onnx` and `vocals_umxhq_int8.onnx` bundled in `app/src/main/assets/`. *(Packaging excludes for unused BouncyCastle data are fine to keep.)* |
| **Radio Tab in Bottom Navigation Bar** | ReChord keeps its cleaner 4-tab bottom navigation bar (**Home**, **Explore**, **Library**, **Search**). | `MixesScreen.kt`, `KeyedMix`, `mixedForYouKeyed`, `TAB_RADIO`, `MainViewModel.Feed.MIXES`, `onMixesShown`, `mixesListState`, 5-tab `FloatingTabBar` merges (`mergeStandaloneWhenExpanded`), `radio` tab and `mixes_*` string keys. |
| **Seasonal / Festive Overlays** | ReChord keeps its Obsidian Vinyl R branding consistent year-round. | `DiwaliGate`, `DiwaliGreetingScreen`, `DiwaliGateTest`, `GoldenLogo`, `diwali_greeting.jpg`, and festive logo branches in `MainActivity.kt`. |
| **Manual YouTube Premium Audio Opt-In Toggle** | ReChord already handles YouTube Premium audio automatically and account-safely via `ResolveKey`. | `youtubePremiumAudio` preference and setter in `AppSettings.kt`, toggle in `AccountAndScrobblingScreen.kt`, `youtube_premium_audio*` string keys, and manual Premium gating in `StreamResolver.kt`, `InnerTubeXResolver.kt`, and `AndroidStreamHooks.kt`. |
| **Upstream Branding, Icons, Package IDs & Versioning** | Preserves installed ReChord app identity, upgrade signing continuity, and brand identity. | Never overwrite `com.music.rechord`, `com.rechord.preview`, `com.music.rechord.tv`, signing verification checks, Obsidian Vinyl R app/notification icons, ReChord `README.md`, `bitchordVersionName`/`bitchordVersionCode`, or user-facing `"ReChord"` copy (including share watermarks, gallery folders `Pictures/ReChord` & `Movies/ReChord`, and filenames `rechord-lyrics-*`). Retain internal `com.music.bitchord` package names for upgrade/data compatibility and retain upstream BitChord attribution. |

---

## 2. Scoped / Gated Upstream Features

Features adopted from BitChord with explicit ReChord scope boundaries that must be preserved on future syncs:

- **AutoMix Artwork Transitions (`#1870`):**
  - Enabled **only** on the **Default** Now Playing skin when Video Mode and layout previews are inactive.
  - Must remain gated off on **Vinyl**, **Poster**, **Video Mode**, and layout previews across [`NowPlayingScreen.kt`](../sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt) (`artworkGate`), [`PlaybackService.kt`](../app/src/main/java/com/music/bitchord/playback/PlaybackService.kt) (`allowsAutoMixArtwork`), and [`MainActivity.kt`](../app/src/main/java/com/music/bitchord/MainActivity.kt) (`nowPlayingLayout == NowPlayingLayout.DEFAULT`).
  - Mini-player AutoMix artwork (`rememberMiniAutoMixArtwork` in [`AutoMixCover.kt`](../sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/AutoMixCover.kt)) must preserve `PlayerDock.reportMiniImage` so ReChord's synchronized dock-flight transitions remain intact.

---

## 3. ReChord Implementations That Always Take Precedence

When upstream changes conflict with any of the following ReChord areas, retain ReChord's implementation and port only targeted, non-conflicting bug fixes:

- **Add-Ons:** Attestation-free `AddonSource.kt`, capability-checked structured metadata in `AddonClient.kt`, `AddonSourceTest.kt`, `SourcesTest.kt`, and multiline batch import in `SourcesScreen.kt`.
- **YouTube Premium Audio:** Automatic, account-safe `ResolveKey` handling in `StreamResolver.kt`, `InnerTubeXResolver.kt`, and `AndroidStreamHooks.kt`.
- **Local Music:** Incremental Local Music index, embedded + album-folder artwork resolution, and multi-disc track ordering in `LocalMediaRepository.kt` and `DetailScreen.kt`.
- **Home, Featured & Explore:** Personalized Home shelves, Featured carousel, and shelf visibility filtering.
- **Now Playing Skins & Lyrics:**
  - **Vinyl Studio** (16 finishes, off-thread LRU raster bitmaps, turntable choreography, headshell-only scrub hitbox, edge-to-edge landscape vinyl).
  - **Poster** expressive lyrics (`PosterExpressiveLyrics`, `PosterSpectrumTap`, curved rim visualizer).
  - Per-song lyrics offset & one-tap Auto-Sync (`LyricsAutoSyncEngine`, `LyricsOffsetSheet`), lighter visibility-gated lyric strip clock, and clearer provider statuses.
  - Default-skin Video Mode gating and synchronized `PlayerDock.artworkInFlight` dock-flight.
- **Adaptive Layouts & Settings:** Portrait, Landscape, DAP (`560–720 × 360 dp`), and 720p Car Head-Unit (`640 × 360 dp`, `853 × 480 dp`, `1280 × 720 dp`) parity; reorganized `SettingsSheet.kt` with row alignment and text boundaries.
- **Audio Engine & Diagnostics:** Direct USB DAC, Direct Source Stream, Seamless Quality Upgrade, memory budgeting, and opt-in Diagnostics submission.

---

## 4. Upstream Sync History & Pre-Merge Audit Checklist

### Sync Log
| Upstream Tag | Upstream Commit | ReChord Merge Commit (`develop`) | Notes |
| --- | --- | --- | --- |
| `v1.10` | `2135620` | `43dab3b` | Selectively ported playback, queue, lyrics, Replay Year, Best Part, Animated Lyrics Share, and Default-skin-only AutoMix artwork transitions; omitted Google Drive Backup, on-demand AutoMix model downloads, Radio tab, Diwali overlay, and manual YT Premium toggle. |

### Pre-Merge Audit Commands
Before committing any future upstream sync, run these checks from the repository root:

```bash
# 1. Confirm zero references to omitted upstream features
rg -n "DriveBackup|DriveAuth|DriveDevices|PeerListening|ReplaySync|AutomixModels|youtubePremiumAudio|MixesScreen|KeyedMix|mixedForYouKeyed|TAB_RADIO|DiwaliGate|GoldenLogo" shared/ sharedUi/ app/ tv/

# 2. Confirm AutoMix ONNX models remain bundled
ls -lh app/src/main/assets/*.onnx

# 3. Confirm no user-facing "BitChord" copy leaked into strings or share/replay UI
rg -n "BitChord" app/src/main/res/values*/strings.xml sharedUi/src/commonMain/composeResources/values*/strings.xml
```
