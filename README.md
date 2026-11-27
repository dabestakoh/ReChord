<div align="center">
  <img src="docs/screenshots/rechord-banner.jpg" alt="ReChord" width="880" />
  <p>
    <a href="https://github.com/dabestakoh/ReChord/releases"><img src="https://img.shields.io/github/v/release/dabestakoh/ReChord?style=flat-square&color=8B5CF6" alt="Latest Release" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-F43F5E?style=flat-square" alt="License GPL-3.0" /></a>
    <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-1F2937?style=flat-square" alt="Android 8.0+" />
    <a href="https://github.com/dabestakoh/ReChord/actions/workflows/preview-apk.yml"><img src="https://img.shields.io/badge/Preview-Develop%20APK-374151?style=flat-square" alt="Develop Preview APK" /></a>
  </p>
  <p>
    <a href="https://github.com/dabestakoh/ReChord/releases">Releases</a> ·
    <a href="https://github.com/dabestakoh/ReChord/actions/workflows/preview-apk.yml">Preview Builds</a> ·
    <a href="https://github.com/dabestakoh/ReChord/issues">Issues</a> ·
    <a href="docs/RECHORD-STANDARDS.md">Engineering Standards</a>
  </p>
</div>

**ReChord** is an Android music player for YouTube Music, local Hi-Res libraries, and open custom audio sources, forked from [BitChord](https://github.com/kushagrasinghx/BitChord).

ReChord is not affiliated with or endorsed by YouTube, Google, Spotify, Apple, Qobuz, or other music services.

---

## Screenshots

### Now Playing Skins

<table width="100%">
  <tr>
    <th align="center" width="33%">Default</th>
    <th align="center" width="33%">Vinyl</th>
    <th align="center" width="33%">Poster</th>
  </tr>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/now-playing-default.jpg" alt="Now Playing Default skin" width="230" /></td>
    <td align="center" width="33%"><img src="docs/screenshots/now-playing-vinyl.jpg" alt="Now Playing Vinyl skin" width="230" /></td>
    <td align="center" width="33%"><img src="docs/screenshots/now-playing-poster.jpg" alt="Now Playing Poster skin" width="230" /></td>
  </tr>
</table>

### Landscape

<table width="100%">
  <tr>
    <th align="center" width="33%">Default</th>
    <th align="center" width="33%">Vinyl</th>
    <th align="center" width="33%">Poster</th>
  </tr>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/landscape-default.jpg" alt="Now Playing Default Landscape" width="280" /></td>
    <td align="center" width="33%"><img src="docs/screenshots/landscape-vinyl.jpg" alt="Now Playing Vinyl Landscape" width="280" /></td>
    <td align="center" width="33%"><img src="docs/screenshots/landscape-poster.jpg" alt="Now Playing Poster Landscape" width="280" /></td>
  </tr>
</table>

### Home, Queue &amp; Vinyl Studio

<table width="100%">
  <tr>
    <th align="center" width="33%">Home</th>
    <th align="center" width="33%">Queue (Familiar / Discovery)</th>
    <th align="center" width="33%">Vinyl Studio</th>
  </tr>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/home.jpg" alt="ReChord Home screen" width="230" /></td>
    <td align="center" width="33%"><img src="docs/screenshots/queue-autoplay.jpg" alt="Queue with Familiar and Discovery Autoplay" width="230" /></td>
    <td align="center" width="33%"><img src="docs/screenshots/vinyl-studio.jpg" alt="Vinyl Studio 16 finishes" width="230" /></td>
  </tr>
</table>

### Audio Pipeline &amp; Local Hi-Res

<table width="100%">
  <tr>
    <th align="center" width="33%">Fidelity Score</th>
    <th align="center" width="33%">USB DAC Pipeline</th>
    <th align="center" width="33%">Local Music</th>
  </tr>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/audio-pipeline-fidelity.jpg" alt="Audio Pipeline Fidelity Score" width="230" /></td>
    <td align="center" width="33%"><img src="docs/screenshots/audio-pipeline-dac.jpg" alt="Audio Pipeline USB DAC Output" width="230" /></td>
    <td align="center" width="33%"><img src="docs/screenshots/local-music.jpg" alt="Local Music Hi-Res library" width="230" /></td>
  </tr>
</table>

---

## Highlights

| Area | Details |
| --- | --- |
| **Now Playing Skins** | **Default**, **Vinyl** (16 finishes + machined tonearm), and **Poster** (word-by-word lyrics + rim visualizer) with dedicated Portrait &amp; Landscape layouts. |
| **Discovery &amp; Queue** | **Familiar vs. Discovery** Autoplay, **Rediscover** picks, per-account durable taste memory, and queue add/remove learning with Undo. |
| **Lyrics &amp; Auto-Sync** | Per-song saved lyrics offset, one-tap Auto-sync with Undo, and multi-provider timing/language matching. |
| **Audio Pipeline &amp; DACs** | **Fidelity Score**, live Source → Decoder → DSP → Output view, Seamless Quality Upgrade (OPUS → FLAC), and **Direct USB (`libusb 1.0.30`)** with PCM Preserved reporting. |
| **Open Custom Add-Ons** | Compatible third-party add-on servers with no device attestation gate, bulk URL import, and Search Add-ons radio. |
| **Audio-First Video** | Watch synchronized music videos in the Default skin without downgrading lossless FLAC audio. |
| **Local Music &amp; Playlists** | Background-indexed Local Music (Hi-Res filter, A–Z jump rail), Apple Music / Spotify / YouTube Music link import, real-time playlist sync, and Liked Music reordering. |
| **Themes &amp; Performance** | True-black AMOLED &amp; Light themes, **Reduce Liquid Glass** and **Reduce Motion** support, and opt-in redacted Diagnostics export. |

---

## Installation

Download a signed build from [Releases](https://github.com/dabestakoh/ReChord/releases), or grab a testing build from the [Develop Preview workflow](https://github.com/dabestakoh/ReChord/actions/workflows/preview-apk.yml).

| Build | Android Package | Purpose |
| --- | --- | --- |
| **Release** (`prod` flavor) | `com.music.rechord` | Daily listening (signed production builds) |
| **Preview** (`dev` flavor) | `com.rechord.preview` | Testing upcoming changes; installs side-by-side with Release |

Preview and Release use separate app data. In-place updates require a matching signing certificate.

---

## Build from Source

1. Clone this repository and open it in Android Studio (Android SDK, NDK 27, JDK 17).
2. Configure signing in `keystore.properties` (see `keystore.properties.example`) for release builds.
3. Build `:app:assembleDevDebug` for Preview or `:app:assembleProdRelease` for Release.

See [ReChord Engineering Standards](docs/RECHORD-STANDARDS.md) and [Contributing Guide](CONTRIBUTING.md).

---

## Credits &amp; License

Based on [BitChord](https://github.com/kushagrasinghx/BitChord), with inspiration from [Auris](https://github.com/OreoMuncher45/Auris) and [Mochy Music](https://github.com/realidkroo/Mochy-Music).

Licensed under **GNU GPL-3.0**. See [LICENSE](LICENSE).
