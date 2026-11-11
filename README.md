
<p align="center">
  <img src="assets/piscesplayer-icon.png" alt="PiscesPlayer icon" height="250"/>
</p>

<p align="center">
  <b>English</b> | <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/pisces312/PiscesPlayer/releases/latest">
    <img src="https://img.shields.io/github/v/release/pisces312/PiscesPlayer?include_prereleases&logo=github&style=for-the-badge&label=Latest%20Release" alt="Latest release">
  </a>
  <a href="https://github.com/pisces312/PiscesPlayer/releases">
    <img src="https://img.shields.io/github/downloads/pisces312/PiscesPlayer/total?logo=github&style=for-the-badge" alt="Total downloads">
  </a>
  <a href="https://github.com/sponsors/lostf1sh">
    <img src="https://img.shields.io/badge/Sponsor-Upstream%20(GitHub%20Sponsors)-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white" alt="Sponsor the upstream project">
  </a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="GPLv3 license">
</p>

<p align="center">
  <img src="assets/screenshot-home.jpg" alt="PiscesPlayer home screen" width="165"/>
  <img src="assets/screenshot-nowplaying.jpg" alt="PiscesPlayer now playing screen" width="165"/>
  <img src="assets/screenshot-lyrics.jpg" alt="PiscesPlayer lyrics screen" width="165"/>
  <img src="assets/screenshot-playlist.jpg" alt="PiscesPlayer playlist" width="165"/>
  <img src="assets/screenshot-rhythm-charts.jpg" alt="PiscesPlayer rhythm chart library" width="165"/>
  <img src="assets/screenshot-rhythm-play.jpg" alt="PiscesPlayer rhythm game" width="165"/>
</p>

## What It Is

PiscesPlayer is an Android music player with a **built-in rhythm game** and **AI music tools**. It is maintained by [@pisces312](https://github.com/pisces312) and is based on [PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS), the upstream FOSS player maintained by [@lostf1sh](https://github.com/lostf1sh). It focuses on local playback, self-hosted music libraries, expressive Material 3 UI, user-controlled online lookups, and turning any song into a playable rhythm chart.

PiscesPlayer ships under its own package name (`com.pisces312.piscesplayer`) and is signed with a different key, so it installs side by side with upstream or F-Droid builds of PixelPlayerOSS — see [Release builds](#release-builds). It is developed as an independent project rather than a tracking fork of upstream; the upstream relationship and fork baseline are documented in [PROVENANCE.md](PROVENANCE.md).

The app works offline by default. Optional online services are disabled until you enable them in setup or settings.

## Highlights

### 🎮 Rhythm game — any song, one tap

<p align="center">
  <img src="docs/images/release-1.0.0-rhythm-game-play.jpg" width="270" alt="Rhythm game play"/>
</p>

Pick any song in your library and generate a playable 4-lane chart from the audio itself with on-the-fly DSP:

- **One master chart per song, unlimited derived difficulties** — Easy / Normal / Hard are derived from the master chart, never regenerated from scratch.
- **Two derivation families, freely combinable** — *raw onsets* (notes land exactly where the audio says, melody-first selection) and *beat grid* (notes snap to a piecewise grid that tracks the song's real tempo changes, simultaneous onsets become double/triple notes). The legacy fixed-BPM behavior is preserved as a **Classic** mode.
- **A play-logic picker at game start** — adaptive grid, osu!-style single-BPM snap, the legacy piecewise estimator, or exactly as stored. The run title records the logic so you can A/B two placements of the same song.
- **Feel tuning** — latency calibration stepped in ±10 ms, note-drop (scroll) time, delay compensation; per chart, applied live.
- Every generation and derivation method explains itself behind an ⓘ button, in English and Simplified Chinese.

### 🤖 AI music tools

<p align="center">
  <img src="docs/images/release-0.4.1-ai-playlist.jpg" width="270" alt="AI playlist"/>
</p>

- **AI playlists** — describe a mood and let the AI build the playlist, or roll **Serendipity** for a surprise mix. The request streams and the model's thought process is visible while it thinks, then saved with the playlist for later read-back.
- **Ask about the song that is playing** — a conversation sheet with the song attached as context, quick prompts (song / artist / album / style / lyrics), streamed follow-ups, and a one-tap switch to general chat. Sends only song metadata by default; lyrics are opt-in per question.
- **AI translation** of the song title and the lyrics.
- **Japanese romaji reading** for lyrics, with an on-demand dictionary download.

### 🎧 A full-featured player

- Media3 / ExoPlayer engine with FFmpeg extension decoders, gapless playback, crossfade, custom transitions, ReplayGain, sleep timer, queue controls, shuffle/repeat, external file playback.
- Library: albums, artists, genres, folders, favorites, playlists, listening stats, and in-place metadata editing (including five-star ratings written to the file tags).
- Lyrics: embedded lyrics, local `.lrc` files, lyrics import/editing, optional LRCLIB lookup, and a **Bluetooth car lyric title** mode that scrolls the current lyric line on head units that only render Title/Artist/Album.

### 🏠 Self-hosted first

- Navidrome/Subsonic and Jellyfin: login, sync, streaming, artwork, and app-private offline downloads.
- Cloud playback is limited to self-hosted sources. Everything else — MusicBrainz matching, LRCLIB lyrics, Deezer artist artwork — runs only when you ask for it.

### 🎨 Material 3 UI

- Jetpack Compose, dynamic color, light/dark themes, Glance home-screen widgets, animated player surfaces, complete Simplified Chinese localization.

## Features

| Area | Highlights |
| --- | --- |
| Rhythm game | DSP master chart generation, Easy/Normal/Hard derivations, play-logic picker, latency calibration, scroll speed, note density |
| AI | AI playlists (describe / Serendipity), song Q&A, title & lyrics translation, Japanese romaji |
| Playback | Media3 playback engine, FFmpeg support, gapless playback, crossfade, custom transitions, queue controls, shuffle, repeat, sleep timer, external file playback |
| Library | Local scanning for MP3, FLAC, AAC, OGG, WAV, M4A, albums, artists, genres, folders, favorites, playlists, stats, metadata editing |
| Self-hosted | Navidrome/Subsonic and Jellyfin login, sync, streaming, artwork, and app-private offline downloads |
| Lyrics | Embedded lyrics, local `.lrc` files, lyrics import/editing, optional LRCLIB lookup, car lyric title |
| Artwork | Local artwork, album-art palette extraction, optional Deezer artist image lookup |
| Metadata | On-demand MusicBrainz matching for recording, release, and artist identifiers |
| UI | Jetpack Compose, Material 3, dynamic color, light/dark themes, Glance widgets, animated player surfaces |
| Backup | Preferences, playlists, favorites, lyrics, stats, and app state backup/restore |
| Updates | In-app updater against this fork's GitHub releases, with optional daily check |

## Performance & Optimizations

- **Baseline profiles** for fast startup, plus R8 shrinking that ships only the referenced `material-icons-extended` classes — noticeably smaller APK.
- **Listening stats are computed on demand** and cached per calendar period; opening the stats page no longer rewrites the whole playback history (this is what used to stutter audio over Bluetooth).
- **Compose stability tuning** — strong skipping, explicit stability config, state slicing with `distinctUntilChanged`, and `ImmutableList` state to keep recomposition cheap.
- **Rhythm clock drift recovery** modelled on osu!'s `InterpolatingFramedClock`: a stable start, a one-second lead-in, and notes that stay in step with the audio.
- **Artwork pipeline hardening** — concurrent cache writes no longer corrupt each other, server error responses are never cached as covers, and the cloud-artwork placeholder freeze is fixed ([#138](https://github.com/PixelPlayerHQ/PixelPlayerOSS/issues/138)).

## Online Services

PiscesPlayer separates offline playback from network lookups.

| Service | Purpose | Default |
| --- | --- | --- |
| Navidrome/Subsonic | Self-hosted library sync, streaming, and offline downloads | User login required |
| Jellyfin | Self-hosted library sync, streaming, and offline downloads | User login required |
| AI providers | AI playlists, song Q&A, translation (user-configured endpoint) | User configuration required |
| MusicBrainz | On-demand metadata matching and identifier enrichment | Only when requested |
| LRCLIB | Search online lyrics when local or embedded lyrics are missing | Off |
| Deezer | Fetch missing artist artwork and cache it locally | Off |

LRCLIB and Deezer can be enabled during first-run setup or later from `Settings > Music Management > Optional online services`. MusicBrainz searches run only when you choose the lookup action for a track.

## Requirements

| Requirement | Version |
| --- | --- |
| Android | 11 or newer, API 30+ |
| JDK | 21 |
| Android SDK | compile/target 37 |

## Build From Source

Clone the repository:

```sh
git clone https://github.com/pisces312/PiscesPlayer.git PiscesPlayer
cd PiscesPlayer
```

Build the debug APK:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

Build one universal debug APK for local installation:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug -Ppixelplayer.enableAbiSplits=false
```

Build a universal unsigned release APK suitable for F-Droid verification:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease -Ppixelplayer.enableAbiSplits=false -Ppixelplayer.disableReleaseSigning=true
```

Run unit tests:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
```

Generate the baseline profile with a connected device or emulator:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :baselineprofile:generateBaselineProfile
```

## Download

APKs are published on the GitHub releases page:

```text
https://github.com/pisces312/PiscesPlayer/releases
```

Obtainium app id:

```text
com.pisces312.piscesplayer
```

Releases are cut from `main` once a batch of changes is ready, rather than on a fixed cadence.

### Release builds

Releases are cut by hand from `main` and published on the [releases page](https://github.com/pisces312/PiscesPlayer/releases) as `X.Y.Z`. There is no automated pre-release pipeline here, so no `vX.Y.Z-alpha.N` builds are published.

To install one, download the APK from the release assets and sideload it: `arm64-v8a` is the only ABI built.

Release builds are signed with a personal key, so they update over each other. PiscesPlayer uses its own package name and signing key, so it cannot be installed over a PixelPlayerOSS build (upstream or F-Droid) and vice versa; moving a library between them uses the in-app backup and restore.

## Support

PiscesPlayer is a hobby fork. If the player is useful to you, the best way to support it is to support the upstream project: [PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS) · [GitHub Sponsors of @lostf1sh](https://github.com/sponsors/lostf1sh).

## Project Structure

```text
app/src/main/java/com/pisces312/piscesplayer/
- data/             Room, repositories, preferences, services, workers
- di/               Hilt modules and qualifiers
- presentation/     Compose screens, components, navigation, ViewModels
- ui/               Theme and Glance widgets
- utils/            Shared utilities

baselineprofile/      Macrobenchmark and baseline profile generation
```

## Tech Stack

| Area | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose |
| Design | Material 3 |
| Playback | AndroidX Media3, ExoPlayer, FFmpeg |
| Database | Room |
| Dependency Injection | Hilt |
| Preferences | DataStore |
| Background Work | WorkManager |
| Networking | Retrofit, OkHttp |
| Images | Coil |
| Metadata | TagLib |

## Contributing

Contributions are welcome. Open an issue or pull request with a focused change and include test/build results when possible.

Useful local checks:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:compileDebugKotlin
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:lintDebug
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
```

Release process: [docs/RELEASE.md](docs/RELEASE.md)

F-Droid notes (historical, the fork is not distributed via F-Droid): [docs/FDROID.md](docs/FDROID.md)

Privacy policy: [PRIVACY.md](PRIVACY.md)

Security policy: [SECURITY.md](SECURITY.md)

## License

PiscesPlayer is licensed under the [GNU General Public License v3.0](LICENSE) (`SPDX-License-Identifier: GPL-3.0-or-later`). It is a modified version of [PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS) by Theo Vilardo (@lostf1sh); see [NOTICE](NOTICE) and [PROVENANCE.md](PROVENANCE.md).

```
PiscesPlayer
Copyright (C) 2026 pisces312
Based on PixelPlayerOSS, Copyright (C) 2026 Theo Vilardo

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

Distributed APKs include third-party components under their own licenses. In particular, the optional FFmpeg decoder dependency `org.jellyfin.media3:media3-ffmpeg-decoder` is GPL-3.0; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

<p align="center">
  PiscesPlayer maintained by <a href="https://github.com/pisces312">pisces312</a> · upstream <a href="https://github.com/PixelPlayerHQ/PixelPlayerOSS">PixelPlayerOSS</a> maintained by <a href="https://github.com/lostf1sh">lostf1sh</a>
</p>
<p align="center">
  Original PixelPlayerOSS logo designed by <a href="https://github.com/NPSummers">Aureal</a>. PiscesPlayer icon by pisces312.
</p>
