<div align="center">
  <img src="ReChordIcon.svg" alt="ReChord app icon" width="128" />
  <h1>ReChord</h1>
  <p>Music on your terms. An Android player for YouTube Music, local tracks and compatible custom audio sources.</p>
  <p><a href="https://github.com/dabestakoh/ReChord/releases">Releases</a> · <a href="https://github.com/dabestakoh/ReChord/actions/workflows/playlist-cache-preview.yml">Preview builds</a> · <a href="https://github.com/dabestakoh/ReChord/issues">Issues</a></p>
</div>

## About

ReChord is an independently maintained fork of [BitChord](https://github.com/kushagrasinghx/BitChord) with its own branding and ongoing improvements to playlist handling, interface performance and audio-source flexibility.

ReChord is not affiliated with or endorsed by YouTube, Google, Spotify, Qobuz or other music services.

## Features

- **Music sources:** YouTube Music browsing and playback, locally stored music, downloads, and compatible custom add-on servers.
- **Audio options:** quality preferences for Wi-Fi and mobile data, available lossless-capable streams, output selection, and compatible USB audio paths.
- **Playlists:** cache-first opening of previously loaded playlists, background refresh, sorting, editing, reordering, and playback queues.
- **Listening:** lyrics, mini-player, media notifications, home-screen widgets, and optional scrobbling integrations.
- **Personalization:** appearance and performance settings, music content preferences, and optional Spotify playlist import.

Available audio formats, resolution and output fidelity depend on the source, device and playback path. USB bit-perfect operation is device-dependent and should not be assumed on every configuration.

### Custom add-ons

Compatible third-party add-on servers can supply music search and playback streams, including their own selectable quality options. ReChord does **not** require official BitChord device attestation to use compatible custom add-ons. Each independent server may still enforce its own credentials, entitlements or request policy.

## Screenshots

The Home, Now Playing, and Settings screens are shown in the project's Preview screenshot set. Screenshot image files are being prepared for inclusion in `docs/screenshots/`; the image gallery will appear here when those files are committed.

## Installation

Download a published build from [Releases](https://github.com/dabestakoh/ReChord/releases), or a testing APK from the [Preview workflow](https://github.com/dabestakoh/ReChord/actions/workflows/playlist-cache-preview.yml) when an artifact is available.

| Build | Android package | Purpose |
| --- | --- | --- |
| Preview (dev flavor) | `com.rechord.preview` | Test upcoming changes; installs alongside Release |
| Release (prod flavor) | `com.music.rechord` | Regular use |

Preview and Release use separate application data. APK upgrades require a matching signing certificate; do not uninstall a build containing important unbacked-up data merely to replace it.

## Build from source

1. Clone this repository and open the Android project in Android Studio.
2. Install the Android SDK and JDK required by the project.
3. Configure signing for the intended build variant.
4. Use Gradle to build `devDebug` for Preview or the appropriate `prod` variant for Release.

GitHub Actions runs the branch's playlist, add-on and Preview build checks. A successful workflow does not replace device-level playback and performance testing.

## Project status

ReChord is under active development. Preview builds may include experimental optimizations. Large-playlist scrolling, graphics performance, and USB output compatibility require testing on real devices.

## Credits and license

ReChord is based on [BitChord by kushagrasinghx and contributors](https://github.com/kushagrasinghx/BitChord). Existing upstream copyrights and third-party attribution remain applicable.

Distributed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the license and source-distribution obligations.

Music and metadata are supplied by independent providers. Use the software in accordance with applicable law and service terms.
