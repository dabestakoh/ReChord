<div align="center">
  <img src="ReChordIcon.svg" alt="ReChord monochrome app icon" width="160" />
  <h1>ReChord</h1>
  <p>An Android music player with YouTube Music, local music, and custom audio sources.</p>
  <p><a href="https://github.com/dabestakoh/ReChord/releases">Releases</a> · <a href="https://github.com/dabestakoh/ReChord/issues">Issues</a></p>
</div>

## About

ReChord is an independent fork of [BitChord](https://github.com/kushagrasinghx/BitChord)
with its own branding and additional improvements. It is not affiliated with or
endorsed by YouTube, Google, Spotify, Qobuz, or other music services.

The **Preview** phone build uses `com.rechord.preview` and installs separately
from the **Release** build (`com.music.rechord`). The two builds keep separate
on-device data.

## Features

- YouTube Music browsing and playback, including authenticated sessions.
- Cache-first playlists: previously loaded playlists open immediately while
  updates are checked without resetting the playing queue.
- Custom addon servers with search, streaming, selectable quality and
  independent HTTP authentication enforced by each server.
- Local music, downloads, music library and playlist management.
- Audio-source selection, lossless-capable playback and USB audio output.
- Lyrics, music controls, media notifications and home-screen widgets.
- Optional Spotify playlist import and scrobbling integrations.

## Build and install

Use GitHub Actions artifacts for development builds. The Preview application
uses the `dev` Android flavor; release builds use `prod`. Custom addons no
longer need official BitChord device attestation, but servers that require
authorization can still reject an unauthenticated request.

## Attribution and licensing

ReChord is based on the open-source BitChord project by
[kushagrasinghx and contributors](https://github.com/kushagrasinghx/BitChord).
Many features and dependencies are the work of their original contributors;
their credits and copyright notices remain applicable.

This fork is distributed under the **GNU General Public License v3.0**.
See [LICENSE](LICENSE) for the full license. Distributors must comply with
its source-code and copyright-notice obligations.

Music and metadata remain the responsibility of the source providers.
Use the software in accordance with applicable law and service terms.
