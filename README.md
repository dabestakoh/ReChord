<div align="center">
  <img src="ReChordIcon.svg" alt="ReChord app icon" width="128" />
  <h1>ReChord</h1>
  <p>Music on your terms. An Android player for YouTube Music, local tracks and compatible custom audio sources.</p>
  <p><a href="https://github.com/dabestakoh/ReChord/releases">Releases</a> · <a href="https://github.com/dabestakoh/ReChord/actions/workflows/fidelity-ui-preview.yml">Preview builds</a> · <a href="https://github.com/dabestakoh/ReChord/issues">Issues</a></p>
</div>

## About

ReChord is an independently maintained fork of [BitChord](https://github.com/kushagrasinghx/BitChord) with its own branding and ongoing improvements to playlist handling, interface performance and audio-source flexibility.

ReChord is not affiliated with or endorsed by YouTube, Google, Spotify, Qobuz or other music services.

## Features

- **Home and discovery** — Browse personalized Featured picks and choose which sections appear on Home in Settings. Explore carries the broader new-releases catalog.
- **Music sources** — Play YouTube Music, downloads, local files, and streams from compatible add-on servers. Set streaming quality separately for Wi-Fi and mobile data.
- **Local Music** — Browse Songs, Albums, Artists, Album Artists, Folders, Playlists, and Recently Added. Open an artist to see their albums and tracks, or use the letter rail to jump through long alphabetical lists. ReChord opens the saved index while checking for file changes in the background. Refresh on demand from the Sort/View menu. Local covers can come from embedded tags or nearby cover images.
- **Playlists and queues** — Import public playlists by link from Apple Music, Spotify, and YouTube Music. Open previously loaded playlists from cache, sort and edit them, and resume playback from the saved position. Autoplay can continue the current radio once the playlist and manually queued tracks finish.
- **Now Playing** — Album artwork colors the player and lyrics. Open Audio Output to select a device, adjust volume, or inspect Audio Pipeline. Playback controls are also available in the mini-player, Android media controls, and home-screen widgets.
- **Audio and USB DACs** — Choose automatic or fixed output precision, use Direct USB or Android USB on supported devices, and check the source, app output, and active DAC format in Audio Pipeline. Compatible sources can provide lossless streams and, where supported, upgrade playback to verified FLAC.
- **Appearance and performance** — Choose a true-black AMOLED theme. Home uses placeholders that follow its layout, and scrolling reuses the backdrop behind glass controls to reduce rendering work.
- **Diagnostics** — In **Settings → Diagnostics**, export a redacted log or choose **Send diagnostic report** when reporting is available. Reports are sent when you select that action.

Available formats and output fidelity depend on the source, device, and playback path. Audio Pipeline reports bit-perfect USB output when it can verify that PCM is preserved.

See [Releases](https://github.com/dabestakoh/ReChord/releases) for version-by-version changes and fixes.

### Custom add-ons

Compatible third-party add-on servers can supply music search and playback streams, including their own selectable quality options. ReChord does **not** require official BitChord device attestation to use compatible custom add-ons. Each independent server may still enforce its own credentials, entitlements or request policy.

## Screenshots

<div align="center">
  <table>
    <tr><th>Home</th><th>Now Playing</th><th>Settings</th></tr>
    <tr>
      <td><img src="docs/screenshots/home.jpg" alt="ReChord Home screen" width="210" /></td>
      <td><img src="docs/screenshots/now-playing.jpg" alt="ReChord Now Playing screen" width="210" /></td>
      <td><img src="docs/screenshots/settings.jpg" alt="ReChord audio quality and Settings screen" width="210" /></td>
    </tr>
  </table>
</div>

## Installation

Download a published build from [Releases](https://github.com/dabestakoh/ReChord/releases), or a testing APK from the [Develop Preview workflow](https://github.com/dabestakoh/ReChord/actions/workflows/fidelity-ui-preview.yml) when an artifact is available.

| Build | Android package | Purpose |
| --- | --- | --- |
| Preview (dev flavor) | `com.rechord.preview` | Test upcoming changes; installs alongside Release |
| Release (prod flavor) | `com.music.rechord` | Regular use |

Preview and Release use separate application data. APK upgrades require a matching signing certificate; do not uninstall a build containing important unbacked-up data merely to replace it.

For implementation and testing conventions, see the [ReChord engineering standards](docs/RECHORD-STANDARDS.md) and the [contributor guide](CONTRIBUTING.md).

## Build from source

1. Clone this repository and open the Android project in Android Studio.
2. Install the Android SDK and JDK required by the project.
3. Configure signing for the intended build variant.
4. Use Gradle to build `devDebug` for Preview or the appropriate `prod` variant for Release.

GitHub Actions runs unit tests on feature and fix branch pushes. Pushes to `develop` normally build the arm64 Preview APK. Feature and fix branch APKs are available through manual workflow runs. Verify playback and performance on the affected devices.

## Project status

ReChord is under active development. Preview builds may include experimental optimizations. Large-playlist scrolling, graphics performance, and USB output compatibility require testing on real devices.

## Credits and license

ReChord is based on [BitChord by kushagrasinghx and contributors](https://github.com/kushagrasinghx/BitChord). Existing upstream copyrights and third-party attribution remain applicable.

Thanks to [Auris](https://github.com/OreoMuncher45/Auris) for inspiring the Now Playing color treatment, startup skeletons, and smoother scrolling.

Thanks to [Mochy Music](https://github.com/realidkroo/Mochy-Music) for inspiring the featured Home carousel, personalized recommendations, the Vinyl and Poster players, expressive timed lyrics on Poster, the expanded lyric effects and provider selection, and synchronized Video mode.

Distributed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the license and source-distribution obligations.

Music and metadata are supplied by independent providers. Use the software in accordance with applicable law and service terms.
