# Privacy Policy

PiscesPlayer is local-first and does not include analytics, advertising SDKs, Firebase, Crashlytics, or Google Play Services runtime dependencies.

## Data Stored On Device

The app stores music library metadata, playlists, favorites, lyrics, preferences, playback state, artwork cache, and optional backup/restore data on the device.

## Network Access

Network features are optional and user-controlled:

- Navidrome/Subsonic and Jellyfin are used only after the user signs in to a self-hosted server. Those servers may receive the authentication, library, playback state, and play history requests needed for their protocols.
- MusicBrainz lookup runs only when the user requests enrichment for a track. The app sends that track's title and, when available, artist, album, and duration to the public MusicBrainz search API; applying a result stores the selected MusicBrainz identifiers locally.
- LRCLIB lyric lookup is used only when online lyrics are enabled.
- Deezer artist artwork lookup is used only when online artist images are enabled.
- CoverArtArchive cover lookup runs only as part of a MusicBrainz enrichment the user requests. The app fetches an image for the selected MusicBrainz release or release-group identifier and caches it locally; no account data is sent.
- Serendipity's optional weather context queries `api.open-meteo.com` with latitude/longitude parameters. When the user picks a city in Settings, the bundled catalogue supplies that city's coordinates. When the user selects the **device location** source instead, the app uses the device's last known coarse position (the bundled catalogue only supplies a display name) and sends those coordinates to Open-Meteo. A city name is sent to `geocoding-api.open-meteo.com` only when a restored or custom city name is missing from the bundled catalogue. No location history is stored, and the weather source can be set to Off.
- ListenBrainz scrobbling is optional and disabled by default. It activates only after the user connects a ListenBrainz account with their own user token. While connected, the app submits listening activity (track title, artist, album, duration, listen timestamps, and MusicBrainz identifiers when available) to the configured ListenBrainz server for the playback sources the user has enabled — listenbrainz.org by default, or a user-supplied custom URL for self-hosted ListenBrainz-compatible servers such as Maloja; per-source toggles cover local files, Navidrome/Subsonic, and Jellyfin playback. Disconnecting stops submissions and deletes any queued listens. Last.fm is not supported.
- AI Q&A is optional and only sends data after the user configures an AI provider in Settings → AI. Questions, the visible conversation, and the chosen song metadata are sent to that provider to answer; file paths, media URIs, cloud track ids, artwork, favorites, ratings and playback history are not included. Stored lyrics are sent only when the user explicitly enables the lyrics option or asks the lyrics question. The provider may be Xiaomi MiMo, Volcano Engine Ark, or a user-supplied OpenAI-compatible endpoint. Requests are logged locally for diagnostics and token accounting, including their prompt and response text, and can be cleared from the AI request log.
- Update checks contact the GitHub Releases API when the user opens the Updates screen or taps Check, and once a day if automatic checks are enabled (off by default). Installing an update downloads the APK from GitHub. These requests carry no account or library data, only the standard request headers including the app version.

Server credentials and preferences are stored locally. The app does not sell or share user data.

## Files And Media

PiscesPlayer requests media/file permissions to scan and play local music, read artwork, edit metadata, and export/import user backups. It also declares `ACCESS_COARSE_LOCATION` for the optional device-location weather source described above (read only when the user requests that context, never in the background); `ACCESS_FINE_LOCATION` is declared with maxSdkVersion 30 for older Android builds only. Optional Navidrome/Subsonic and Jellyfin downloads are stored in the app's private files area for offline playback and are excluded from Android cloud backup and device transfer.

## Crash Reports

The app keeps crash logs locally for troubleshooting. It does not automatically upload crash reports.

## Contact

Open a GitHub issue for privacy questions that do not contain sensitive personal data.
