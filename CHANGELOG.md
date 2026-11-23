# Changelog

All notable changes to PiscesPlayer will be documented in this file. PiscesPlayer is a fork of
PixelPlayerOSS; versions up to and including 0.4.3 were published under the upstream name.

## [Unreleased]

### Added
- Home: customizable card grid — cards can be added, removed, resized (1x1 / 2x1 / 1x2 / 2x2) and
  reordered by long-press drag; layout persists per user. AI playlist and Daily Mix cards keep
  their rich content, every card uses the neutral rounded style of the old AI playlist entry.
- Home: a help (?) button in the top bar opens a quick-start sheet (edit gestures, adding cards,
  Serendipity gestures, Daily Mix) with a link to the full changelog.
- Jelly press feedback on pressable cards and covers (home grid cards, recent AI mixes, album
  grid/list items, artist rows, genre tiles) and on the Serendipity button: pressing squashes
  the element around the touch point like a soft surface, dragging tilts it toward the drag,
  and releasing settles it with a bouncy spring. The whole effect runs in the graphics layer
  and only while a finger is down, so it never recomposes and costs nothing at rest.
- Home: the Serendipity button breathes as a live jelly — its outline morphs through eight
  organic phases (a travelling bulge on a superellipse) while the whole pill sways gently
  and pulses in scale, all on separate clocks (Material 3 Expressive shape morphing);
  the short-press/long-press hint is one line directly under the button.

### Fixed
- Rhythm game: a MIDI-generated chart failed to start with "Unsupported MIDI render output:
  audio/x-exoplayer-midi/-1". The offline renderer was treating Media3's MIDI extractor output
  (raw MIDI messages) as PCM; it now synthesizes those events through the same Sonivox/JSyn
  decoder the main player uses, and converts the float PCM to the 16-bit stereo stream the
  game's AudioTrack needs. MIDI messages are also read from the extractor's current position
  now, so meta events no longer shift the byte stream.
- Home: long-pressing the Serendipity pill also entered grid edit mode, because the grid's own
  long-press detector starts from a down the pill had already consumed and fired at the same
  timeout. The pill now claims its bounds, and the grid leaves that gesture to it.
- Remote artwork cache: replacing an existing cache file used `File.renameTo`, which fails on
  Windows JVMs when the destination exists (POSIX rename replaces silently). Now uses
  `Files.move(REPLACE_EXISTING)` on every platform.

### Changed
- Home: the Serendipity pill grew from 35dp to a plump 96dp jelly on the 2x2 AI card (20sp label,
  26dp icon), and the entry card now fills its whole grid cell — the recent-mixes shelf hugs the
  bottom and the vertical dead space around the content is gone. Smaller card spans
  (2x1 banner, 1x1, 1x2) fall back to a 64dp pill so the entry row plus hint still fit.
  The breathing pulse no longer animates a shadow (it read as invisible and cost the most
  per-frame work); the shape morph, scale pulse and gentle sway stay.
- Rhythm game: the derived/other chart cards were redesigned — the title owns a full line and
  scrolls (marquee) when it overflows, every parameter (source, note count, notes per second,
  best score, delay/skip/fall) is a rounded chip under the title, and the action row keeps the
  export/settings/delete icon buttons on the left with "Start" anchored to the right edge.
- Rhythm game song picker: reworked into two swipeable tabs modelled on the library tabs —
  "With charts" (with an All/Recent scope filter defaulting to All, plus a difficulty filter)
  and "Without charts". Both tabs share the search field and the common song sort options; the
  old chart-presence dropdown filter and the "current song" shortcut row are gone.
- Rhythm game song picker: the "Without charts" tab no longer lists MIDI songs — MIDI rows
  appear only in their own tab, because their charts are generated from note tracks rather
  than the DSP path. The tab now shows a dedicated empty state when no non-MIDI song is left
  without a chart.
- Rhythm game: the mini player is now hidden across the whole flow (song picker and chart
  setup screens included), so those screens use the freed bottom space.

## [1.0.4] - 2026-11-22

### Added
- Music quiz: a new "Built-in Classical" mode (内置古典) that draws 10 rounds from the
  built-in classical MIDI library (877 tracks) — no user library needed. Each run mixes
  five "guess the piece" questions (bilingual titles) with five "guess the composer"
  questions; the quiz's own player now renders MIDI clips. The existing "Classical" mode
  is renamed to "Classical (My Library)" to keep the two pools unambiguous.
- A hint shown on the search screen while there is no search history: it tells the user that
  the search bar can be tapped directly, and that double-tapping the Search icon in the bottom
  navigation (while the Search tab is selected) brings up the keyboard. It disappears for good
  once any search history exists.
- Rhythm game: every derived chart can now be saved out like a hand-tapped one (osu!mania,
  `.prc` bundle or unified chart JSON, through the same file picker). The "Tap take" format is
  only offered for charts that actually keep a raw take, and an exported file now names its real
  producer (`piscesplayer-dsp`, not the hand-tap recorder), so PC-side analysis cannot mistake a
  derived chart for a hand-tap recording.
- Rhythm game: manual tap chart recording. Record your taps on the four lanes against the
  game clock and save the take as a playable `tap` chart through the normal play chain; the
  raw take is kept as a `.tap.json` sidecar in the PC recorder's wire format (an optional
  constant offset applies at save time), so takes can be cross-analyzed with RhythmChartLab.
  Hand-tapped charts export as osu!mania 4K beatmaps, `.prc` chart bundles, the unified
  chart JSON or the raw tap take, with PC-compatible canonical hashes. The recorder also
  gets an exit button with a discard confirmation.
- Rhythm game: the derive sheet now offers measured presets v1-v4 (field tables compared
  against the hand-tapped chart) plus a custom difficulty editor with every derivation
  parameter; v1 is the default.
- Music learning: a fifth bottom-navigation tab ("Learn" / 音乐学习) that hosts the built-in
  classical library today and learning tools later — ear training and metronome appear as
  placeholder cards that show a "Coming soon" toast when tapped.
- Classical browsing is now two-level: a compact composer list (favorited composers pinned on
  top) and, per composer, tracks grouped by work — opus sets, numbered series such as
  "La Traviata - N1 …", or single pieces — instead of one flat list per composer. The composer
  list and the work screen both get a live search field matching original/English/Chinese names.
- Classical titles are bilingual (original plus translation) with a display toggle, and the
  built-in MIDI metadata is embedded.
- Classical favorites: composers and tracks can be starred inside the classical screens, and a
  "Favorite tracks" tab lists favorited tracks (newest first) with its own search. Classical
  favorites are stored apart from the global Liked list, so built-in content never leaks into
  user-data pools or the library "Liked" tab.
- MIDI library (Music learning → MIDI library): the built-in pack's non-classical tracks —
  hymns, art songs & folk, jazz, exercises, marches and dances (155 tracks) — live in their
  own genre-sectioned view, and your own MIDI files join by scanning folders (SAF; files are
  referenced, never copied) or adding single documents. User MIDI files play, join playlists
  and count in stats like local songs; their favorite stars use the Liked list.
- User MIDI files deduplicate against the built-ins with two-level hashing: identical file
  SHA-256 first, then an identical music fingerprint (a metadata-stripped canonical SMF hash,
  so re-saved copies of the same music match) — a user copy shadows its built-in twin: the
  built-in leaves the MIDI library list while everything else keeps its curated composer, work
  and title names and silently plays the user's file.
- Classical browsing gains two flat tabs beside the composer list: "Works" lists every work
  set across composers (bilingual title, composer, opus, track count) and opens a work-set
  detail page whose movements/arrangements group under their piece, each node with its own
  play-all; "Tracks" lists all classical tracks with the library's song rows (playback
  highlight, more menu, classical favorite star) and plays them in catalogue order.
- Rhythm game: tap-based audio latency calibration. Ten ticks play through the current
  output route (speaker / wired / Bluetooth / USB); tapping along from the third tick
  measures the output delay of "you + the device" as one system and saves it as that
  route's latency offset. A chart follows its route's calibrated offset by default and
  can carry its own override to nudge the timing relative to the route.
- Rhythm game: the HUD shows current and total playback time during a run, and the pause
  overlay gains a "View log" button that opens the full clock/tap diagnostics in a
  scrollable, selectable dialog with one-tap copy-all, next to the existing export
  button. The export now also carries the clock startup trace (which anchor each frame
  accepted or rejected and when the start hold released) and every lane press with its
  matched note and signed judgement error (mean / max-early / max-late summary plus a
  per-tap table), so a player's timing can be compared against note crossings offline.

### Changed
- Rhythm game: the "Chart play logic" experiment picker is removed; charts now always
  play exactly as saved.
- Rhythm game: "Skip intro" becomes a per-chart setting replacing the fixed 1 s lead-in —
  0 ms default, 500 ms steps. It is a pure play-time filter: displayed counts and scoring
  use only the notes that remain visible while the chart keeps every note.
- Rhythm game: the song picker starts filtered to "all songs with charts" instead of
  "recent, all charts", which used to read as "my charts are gone"; the recency scope
  and the chart-less view remain as deliberate opt-ins.
- Rhythm game: the song card shows the effective Delay · Skip · Fall values, the in-run
  HUD shows Delay · Skip, and the picker warns when the audio route is Bluetooth (large,
  route-dependent latency that notes can only meet through calibration; the first note
  is the most sensitive).
- Rhythm game: the difficulty ladder is now two rungs — Easy (one slot per two beats) and
  Hard (one slot per beat, the former Easy density) — and the classic derive path is
  removed (CLASSIC_043 preset, classic timing-variant picker and the frozen 0.4.3
  replica): two independent hand-tap baselines put it half a beat off the tracked
  lattice, so derivation now always resolves the tracked timing variant. Existing charts
  stay playable.
- Rhythm game: grid derivation leaves quiet cells empty (a fade-out tail or drumless
  outro no longer gets a note per beat) and the beat tracker pulls grid phase toward
  onset evidence at every anchor, bounding mid-song grid drift to about a quarter of its
  former amount.
- The built-in classical/MIDI pack stays out of user-data pools: it is excluded from AI
  playlists, home recommendations and listening stats, and feeds only the quiz's
  "Built-in Classical" mode.
- The app now supports exactly two interface languages: English and Simplified Chinese,
  plus "System default". The in-app language picker, `locales_config.xml` and the translated
  resource folders of the ten stale upstream locales (ar/de/es/fr/in/it/ko/nb/ru/tr) are
  removed: those translations covered only ~63% of all strings, so every fork-added screen
  fell back to English and produced a mixed-language UI. A user who had picked one of the
  removed languages falls back to "System default" automatically.
- Home: the two local-recommendation sections ("Daily Mix" and "Discover") are merged into a
  single "Daily Mix" (每日推荐) card. One 30-song feed now blends familiar picks (2/3,
  history-driven) with discovery picks (1/3, low-affinity and recently added tracks),
  interleaved 2:1 for playback and grouped back under "Picked for You" / "Discover" headers
  on the full-list screen. The home page keeps one recommendation card (collage hero +
  "Check all of Daily Mix"); the separate list card is gone.
- Classical browsing UI now matches the library style: the four tabs (Composers | Works |
  Tracks | Favorite tracks) and the MIDI library tabs swipe with a `PrimaryScrollableTabRow`
  + `HorizontalPager` pair (each page keeps its own search state); track and composer rows
  sit on rounded card containers with the same spacing and surface colour as the library
  list; the composer and work-set detail pages use a collapsing `CollapsibleCommonTopBar`
  header with the name and track count. The composer's catalogue is now a single level —
  work headers over movement rows, single-track works as plain rows — with the
  movement/piece layer living in the new work-set detail page instead of a two-level tree.
- Music quiz: answer options are no longer truncated to a single line, so long classical titles
  (work, opus and movement) stay readable instead of collapsing into identical prefixes; the
  round body scrolls when its content outgrows the screen.
- Music quiz: the revealed answer keeps its title on one line and scrolls it sideways when it
  does not fit, so showing the answer never moves the candidate list below it.
- Music quiz: the round's hero is now a fixed 136 dp band — the note above the "Which song is
  this?" prompt while listening, the cover with the verdict and answer afterwards — so the
  candidate list starts at the same height in every state and hands a large share of the
  vertical space back to the options (five three-line titles now fit on one screen). The clip
  countdown, clock icon included, moved up into the top-right corner of the header, and only the
  options scroll, so it stays visible while browsing a long list. The hint line under the prompt
  and the "No points" line are gone; a correct answer shows its points next to the verdict.
- Music quiz answer options now show the artist and release year under the song title;
  unknown values are omitted, and the line disappears entirely when neither is known.
- The minimum Android version is now 10 (API 29) instead of 11 (API 30). On Android 10, tag
  edits and file deletion fall back to direct file access through legacy external storage
  (write access is requested together with read access during setup); Android 11+ behaviour
  is unchanged.
- The classical library moved out of the library tab strip into the new Music learning tab,
  so the library tabs are back to the user's own media only. Bottom-navigation labels are now
  localized (Home / Search / Library / Games / Learn); previously they were fixed English.
- Classical browsing shows only period classical now (877 of the 1032 built-in tracks);
  hymns, songs, jazz and other genres moved to the MIDI library.
- Music quiz: built-in MIDI pack tracks now feed only the "Built-in Classical" mode; every
  other mode draws questions and distractors exclusively from the user's own library (the
  same built-in exclusion as listening stats and AI picking).
- Music quiz: library classification is cached per track instead of for the whole library,
  so a rescan or a metadata edit reclassifies only the changed tracks (the kids-pool
  threshold is recomputed from the cached per-track evidence on every run), and the manual
  refresh reclassifies in parallel across CPU cores.

### Fixed
- Rhythm game: notes no longer tremble or drift at the start of a song. The game clock
  now holds at chart start until the first trustworthy audio-timestamp sample arrives
  (with a bounded grace fallback for pathological devices), fuses timestamp and
  playback-head readings and rejects implausible jumps, so the timeline is stable before
  the first note even on outputs whose playback head over-reports latency (deep-buffer
  speaker paths).
- The first-run setup welcome page no longer shows the "β Beta" badge; it was rendered
  unconditionally, including in release builds.

## [1.0.3] - 2026-11-18

### Added
- A sleep soundscape, titled 入梦 in Chinese and reachable from the home top bar between the
  streaming and settings buttons. Noise and nature tracks are stacked into one mix, each with
  its own volume, and the mix is mixed in software into a single stream.
- Procedural noise in four colors (white, pink, brown and deep brown) with per-track tone,
  motion and width controls that apply live while playing.
- Six natural recordings (rain, wind, thunder, campfire, ocean waves and crickets) that loop
  seamlessly and mix freely with the noise tracks.
- Master volume with a 0.7 ceiling for hearing protection, a sleep timer (15, 30, 45, 60 or 90
  minutes, or until stopped) that fades out over the last 30 seconds, and playback through a
  foreground service with a stop action, so the soundscape keeps playing with the screen off,
  pauses on audio focus loss and stops when headphones are unplugged.
- Scenes: three built-in presets (pure brown, pure pink and white mask) plus up to 20 saved
  mixes. Tapping a scene recalls it, long-pressing a saved one deletes it, the scene row scrolls
  horizontally with a visible swipe hint, and the last mix is restored on the next visit without
  playing.
- Starting a soundscape pauses music, and stopping it does not resume music.
- Similar Radio: when the queue is about to run dry, AI picks fifteen songs that continue from
  the last track, so playback can keep going without manual selection. It is switched on from
  the full player and requires a configured AI provider.
- A lyrics search mode backed by a local full-text index, plus a manual "Backfill lyrics index"
  entry in settings for songs whose lyrics were stored before indexing existed.
- A downgrade screen for the case where the on-disk database is newer than the installed app:
  it explains what happened and opens a temporary read-only copy, so a backup can still be
  exported instead of the app crashing on launch.
- Music Quiz game modes, switchable from a mode picker right above the start button (also offered
  above the actions on the results and error screens): Standard (unchanged), Kids, Classical and
  Endless. The last selected mode is restored on the next visit, and the setup screen shows the
  rule summary, the mode's own best records and the playable pool size for the chosen mode.
- Kids mode draws its questions from children's songs. Kids songs are found through a built-in
  bilingual catalog (classic nursery rhymes, animation themes, recent hits and movie songs) and,
  when the catalog cannot fill the pool, through keyword evidence in the title, artist/album and
  cached lyrics. Songs performed by kids-content labels (贝瓦儿歌, 贝乐虎儿歌 or any artist
  containing 儿歌) always qualify, and catalog matching tolerates punctuation differences in
  titles (commas, apostrophes, hyphens). Classical works are recognized by a bilingual piece
  catalog, composer/performer names (Bach, Chopin, Lang Lang, Richard Clayderman, …) and
  form/genre words; they are used by Classical mode and the same-source rule, not mixed into the
  kids pool. Too few matches shows a dedicated "too few kids songs" empty state instead of
  starting a run.
- Classical mode draws all ten rounds from classical tracks, and the same-source rule applies in
  every mode: when the answer is a classical work, all five options are classical, so the
  category never gives the answer away. Sub-pools that cannot form five distinct options drop out
  of question generation entirely.
- Endless mode keeps asking questions from the normal pool (dealing each song once per cycle) and
  can be settled at any time from the pause screen: the run is scored and saved like a completed
  one, and the results screen adds "most answered" to the records. Best score, best streak,
  completed runs and best rounds are kept per mode so different pools never overwrite each other.
- Music Quiz answer options show each song's cover art in front of the title.

### Changed
- Music games is now a fourth bottom tab; the home top bar no longer carries a game button, and
  the rhythm game keeps its song picker for recent, difficulty and saved-chart filtering.
- Home sections are unified into cards, and listening statistics moved into the search idle
  state, replacing the separate statistics subpage and its button.
- Daily mixes refresh on New Year's Day, and "Your Mix" is a discovery feed that stays disjoint
  from the daily mixes instead of repeating them.
- Settings buttons were removed from the search and library top bars.
- The launcher icon uses a refreshed fish mark (the debug build carries an amber variant).
- AI library sampling now includes each song's genre when building playlist prompts.

### Fixed
- Embedded and local-file lyrics are persisted to the database again, so they appear in lyrics
  search, translation and the lyrics view after a rescan.
- Accepting AI playlist suggestions no longer gives the user's queue away: enabling Similar Radio
  takes over the queue deliberately, and its toggle surfaces the missing-provider hint instead of
  silently doing nothing.
- Paging flows in the library are subscribed only by composed pages, avoiding duplicate collection
  after repeated tab switches.
- The debug suffix on the app name is kept in Chinese locales.
- The similar-radio and daily-mix refresh paths no longer read player state from the main thread.

## [1.0.2] - 2026-11-14

### Added
- A Music Quiz game under the music games hub: listen to a 10-second clip taken from a random
  position in one of your local songs and pick the correct title from five choices. Ten rounds
  per run, one answer per round, with a shared 10-second clip and answer timer.
- Scoring rewards speed: a correct answer earns 100 to 1000 points depending on the time left,
  a wrong answer or a timeout earns 0, and only a completed run can set a new best score and
  best streak. The results screen shows accuracy, longest streak, average answer time and a
  per-round review; review rows preview the full song with the quiz's own player, and selected
  songs can be collected into a new playlist.
- Distractors are chosen by title language, release decade and genre, so the five options stay
  plausible instead of giving the answer away. The library needs at least 10 playable local
  songs and 5 distinct titles, and the setup screen explains the reason when a run cannot start.
- Quiz playback is isolated from the main player: the mini player is hidden, the clip is never
  exposed as a media session title or notification, and cover art, artist and full title are
  revealed only after answering. Pausing stops both the clip and the timer; leaving a run
  discards it.
- A music games hub and a song picker for the rhythm game: browse the library with recent,
  difficulty and saved-chart filters before opening the setup screen, and reach the hub from
  the home screen's game button.

## [1.0.1] - 2026-11-11

### Changed
- The derive sheet now opens with the Classic (0.4.3-pisces.1) preset selected and collapsed
  into its summary, instead of the expanded custom controls.
- The Classic derivation ladder sits one step easier than the 0.4.3 build: Normal reproduces
  the old Easy (including the note-for-note 544-note legacy reproduction), Hard uses the old
  Normal density, and Easy is a new sparser step with one slot per two beats. The frozen
  0.4.3 replica itself is untouched — the ladder maps onto it. Beat-grid and Raw-onsets
  derivations keep their existing ladders.

## [1.0.0] - 2026-11-10

First release under the PiscesPlayer name, with its own package name
(`com.pisces312.piscesplayer`) and icon. It installs side by side with PixelPlayerOSS; moving a
library between the two uses the in-app backup and restore. The headline feature is a rhythm
game that turns any song in the library into a playable chart.

### Added
- A rhythm game with DSP chart generation. Any local song gets a master chart built from its
  audio, with one master chart per song and unlimited derived difficulties. Onsets that fire
  together become double or triple notes, and the 4-lane playfield scores osu!mania-style with
  a stable start, a one-second lead-in, and clock-drift recovery modelled on osu!'s
  InterpolatingFramedClock.
- Chart derivation from the master chart. Raw-onsets derivation only selects which onsets
  survive, so note times always match the audio; grid derivation re-places notes on a piecewise
  beat grid that tracks the song's real tempo changes instead of one fixed BPM. The 0.4.3-era
  grid behavior is frozen as a Classic mode, a melody-first note selection keeps melodic
  phrases from being crowded out by percussion, and every generation and derivation method
  explains itself behind an (i) button in English and Simplified Chinese.
- A play-logic picker on the rhythm game's Play buttons: before the run starts, a dialog offers
  to place the notes with the adaptive grid (current), an osu!-style single-BPM snap, the legacy
  piecewise estimator, or the chart exactly as stored. The run re-bakes the note times in
  memory — note count, lanes and the stored chart are untouched — and the run title records
  which logic was used, so two placements can be compared against the same song on the device.
- The rhythm game is reachable from a shortcut next to the lyrics chip in the full player and
  from every song info sheet.
- AI Q&A for the current song, reachable from the sparkle action in the full player. It opens a
  conversation sheet with the song attached as context by default, quick prompts for the song,
  artist, album, style and lyrics, streamed follow-up answers, and a one-tap switch to general
  AI without song context. The request sends only song metadata by default; stored lyrics are
  opt-in per question, and the snapshot keeps the conversation tied to the song it was opened
  for even if playback changes. It reuses the existing AI provider settings, diagnostics and
  token accounting.
- AI translation of the song title and the lyrics.
- Optional Japanese romaji reading for lyrics, with an on-demand dictionary download.
- Search and fill song metadata from MusicBrainz: on-demand matching for recording, release,
  and artist identifiers.
- An Updates screen (Settings → Updates, also linked from Settings → About) that looks for a
  newer build among this fork's GitHub releases and installs it over the running one. A daily
  check can notify when an update is available; it is off by default. Builds that cannot
  replace this one — a debug install, or one signed with another key — are reported instead,
  with a link to the release page. Includes a Simplified Chinese translation.
- The About screen shows the commit and build time of the running build.
- An add-to-playlist action in the full player, and a playlist rename action.

### Changed
- The app is renamed to PiscesPlayer with the package name `com.pisces312.piscesplayer` and a
  new icon, cleanly separating it from upstream PixelPlayerOSS builds; the license stays
  GPL-3.0-or-later with upstream attribution preserved in NOTICE.
- The rhythm game's playback delay can be stepped by ±10 ms without clamping, and notes
  refresh after a paused delay calibration.
- Derived charts are generated hardest-first so the easiest lands on top, and the generate and
  derive sheets open at full height with wavy progress bars.
- Release builds ship only the referenced material-icons-extended classes, shrinking the APK.
- The version line restarts at 1.0.0 for the renamed app.

### Fixed
- Navidrome/Subsonic and Jellyfin album artwork no longer gets stuck on the placeholder after a
  few songs. The player loaded cloud covers through the app's own artwork content provider,
  and concurrent loads could exhaust the image decoder slots that provider waited on, freezing
  every artwork load until the app restarted. Server error responses are also no longer cached
  as cover images, and concurrent cover downloads no longer corrupt each other's cache file
  (#138).
- System media controls can load album artwork through the app's artwork URI again. System UI
  reads that URI without connecting as a media controller, so it never received the per-track
  read grant and was refused on every track; privileged media surfaces (holders of
  `MEDIA_CONTENT_CONTROL`) may now open the artwork the session currently shows, while every
  other cover, and every other app, still needs the explicit grant. Stock Android fell back to
  the session bitmap, but System UIs without that fallback could show no artwork.
- Pressing back with the Now Playing screen or the Next up queue open inside a folder in the
  Folders tab now closes the player instead of leaving the folder
  (#136).
- Car head units keep showing the track title while an instrumental placeholder lyric is
  active, instead of dropping it.
- ReplayGain volume survives queue edits that cancel a crossfade
  (#133); Navidrome uses the
  server's created time for date added
  (#132); the compact library
  title pill no longer renders black
  (#134).
- FLAC playback works again when float output is enabled, by preferring extension decoders.

## [0.4.3-pisces.1] - 2026-09-19

Songs now carry the release date their own tags declare, so the year list can be sorted by it
and the song sheet can show it, and the car lyric title stops dropping itself on lines it used
to give up on.

### Added
- Release date support, database schema v12. The song sheet shows a Release date row for tracks whose tags carry a full date, and the year screen gained Release Date (Oldest First) and Release Date (Newest First) alongside its existing sort options. Every song already in the library is read once in the background at the end of the next sync, so no manual rescan is needed. Files that only carry a year keep the Year row they already had, and cloud tracks are left alone because their date cannot be read without downloading them.
- A source filter on the library's playlist tab, so AI-generated and hand-built playlists can be listed separately. The choice survives a restart, and each of the two empty states names which kind of playlist is missing instead of showing a blank page.
- Split long lines, under Settings > Playback > Bluetooth car. A lyric line wider than the head unit can display is cut into segments shown in turn; turning it off sends the line whole, for units that scroll a long title by themselves. On by default.
- Tapping the title in the full player opens the song sheet on its Details tab rather than on its first tab.

### Changed
- Tracks started from Quick play no longer reach listening statistics, recently played or the songs the AI samples from, since Quick play deliberately previews songs that stay outside the library. The notification and its controls are unchanged.
- The rating control in the player is raised as an overlay pill instead of expanding in place, so opening it no longer reflows the row around it, and the expanded pill is wider with tighter star spacing.

### Fixed
- The car lyric title fell back to the real track title far more often than the gaps in a song call for, which is what made a head unit alternate between lyrics and the song name. An untimed blank line in the file cleared the override unconditionally, so a marker that merely ends one lyric line and starts the next dropped the title as well. A blank marker now clears it only when it stands for an actual gap, which is what makes it mean intro, interlude or outro. On a real track with 44 blank markers this cut the fallbacks from 44 to 3.
- A segment of a long lyric line could sit on screen well past the line it belongs to, because nothing bounded how long a segment lasts. A segment now waits at most four seconds before the next one takes over.
- Dragging the position slider in the middle of a line could leave the car lyric title stuck until the following line, because the scheduler's wake-up key did not account for every input that decides when the next cue is due.
- The player could show the previous song after a track change: the shown state was derived from the app's own copy of the playback position rather than from the engine, which by then reported something else. The state is now read from the engine.
- The library sort sheet could not be scrolled, so on a short screen its last options could not be reached at all.
- Search history rows were taller than the 48 dp minimum touch target.
- Backup export left AI and Serendipity playlists out of the archive, so restoring a backup silently lost them.

## [0.4.2-pisces.1] - 2026-09-16

The current lyric line can be published as the media title, so a Bluetooth car head unit that
scrolls its title displays lyrics, and the AI side streams its answer so the model's thought
process is visible while it thinks and can be read back from a saved mix afterwards.

### Added
- Car lyric title, under Settings > Playback > Bluetooth car: the current lyric line is sent as the outgoing media title, so head units that can only render Title/Artist/Album/Genre, and scroll that title, show lyrics instead of a fixed track name. Off by default. The title is rewritten only while the switch is on, the current output is a Bluetooth A2DP device and the song has synced lyrics; every other case keeps the real track title, which is what keeps the override off the notification, the lock screen and the phone's own speakers. Plain un-timed lyrics cannot drive it, and cloud tracks rarely have any, since the lyrics sources in this build are local and LRCLIB.
- A title lead setting, 0 to 1 s in 0.1 s steps, that publishes every cue that many milliseconds early to compensate the AVRCP round trip and the head unit's redraw. It is greyed out while the switch is off, and moving it re-resolves the active cue immediately.
- Long lyric lines are split into evenly timed cues rather than truncated: a line wider than the head unit's roughly ten character title field is published in slices that stay in step with the vocals. Scheduling wakes on the next cue boundary instead of a fixed interval, so a paused or finished track arms no timer at all and the title is restored within one wake-up when the switch is turned off or the Bluetooth route disappears.
- The AI request is streamed, and the mix sheet shows the model's thought process while it thinks: a status line plus a collapsible card that follows the stream to the bottom and folds itself once the answer arrives.
- The thought process survives into the result phase, which also gained the vertical scrollbar its song list never had, and is stored with the playlist (schema v11) so it can be read back later from the AI prompt banner on the playlist screen, next to the prompt and the sampling that produced it.
- Serendipity has its own sampling settings, defaulting to a random slice of 200 songs, with the advanced controls reachable from the sheet.
- Long-pressing Serendipity generates a mix from the defaults in a single gesture.
- The AI prompt banner on a playlist opens a full read-out: the complete prompt, selectable for copying, the sampling that generation used, and the originally generated songs in order, each marked when it has since been removed from the mix or become unavailable.
- A Genres tab in the library, with name sorting, so browsing by genre no longer depends on the search screen.
- The search screen's idle state lists search history; tapping an entry re-runs it, and clearing asks for confirmation first.
- Double-tapping the already-selected bottom navigation entry scrolls Home, the library sublists and Stats back to the top. Search keeps focusing its search bar.
- Simplified Chinese is complete across screens, settings, components, and the import and logs resources, both of which were missing entirely.

### Changed
- The default AI mix length is 15 rather than 25, for both the describe and the Serendipity entries.
- The AI sheet opens fully expanded and its input phase scrolls, so the generate button is reachable with the keyboard up instead of sitting below the fold.
- The five-star row in the player can be collapsed again with its own chevron.
- Listening stats are computed on demand and cached per calendar period. Opening the page no longer rewrites and fsyncs the whole playback history, nor runs three full aggregations, which is what made audio stutter over Bluetooth while entering it. Finalizing a session remains the single place the file is written.

### Fixed
- The expanded rating row no longer crushes its fifth star on narrow screens: the five stars and the close button are laid out as equal-width square cells that shrink uniformly when width runs out, with tighter 2 dp gaps, and the close button now occupies one of the cells so every control keeps the same tap target.
- AI generation failed with network unreachable whenever thinking was enabled. The AI client was built from the app-wide HTTP client and only raised its call timeout, so the 8 second read timeout survived every request, and a model that stays silent while it thinks was cut off by it and classified as a network failure even though the request had been reaching the provider all along. Streaming keeps the connection busy, so the misclassification cannot happen any more.
- Deleting an AI playlist crashed its confirmation dialog: the title passed a plurals id to stringResource(), which resolves ids through getString() and was guaranteed to throw.
- Deleting a playlist left its song rows behind, because that table declares no foreign key to playlists. Both rows are now deleted in one transaction, and the songs themselves are untouched.
- Search could go permanently dead: a second PlayerViewModel was created inside a navigation route and its onCleared() tore down the app-wide search state holder, so after visiting the AI mix screen once, every search returned an empty list until the app was restarted.
- Deleting a song that is not the one playing no longer closes the full player, a multi-select delete always completes its callback, batch playlist deletion asks for confirmation, and revoking only the notification permission no longer forces the setup wizard to be completed again.
- Mini-player transport gestures: the skip dead zone is gone, dismissing the playlist takes 30% of the screen width instead of a short swipe, and a vertically dominant drag no longer skips a track. The lyrics overlay claims horizontal drags only, so vertical scrolling stays with the list.

## [0.4.1-pisces.1] - 2026-09-13

Second fork release. The AI side gets a second entry point that reads the moment, and a mix now
remembers the prompt it was generated from.

### Added
- Serendipity (`不期而遇` in Chinese): a zero-input AI playlist built from right now. The AI banner carries its own magic-wand pill next to the describe field; tapping it opens a sheet showing what it could read as chips (weekday and time of day, city weather, today's step count), the prompt it composed on the device, and two ways to change that prompt: Another line reshuffles the local wording without re-reading anything, and Let the AI rephrase it spends one small extra request, falling back to the local wording if the provider is unreachable. The prompt stays editable before generating, and a signal that could not be read is simply absent instead of being faked.
- A weather source setting with three options: device location (approximate location, permission requested on first use), a chosen city or district, or off. The chosen-city and off options never request the location permission and never read the phone's position. Turning coordinates into a place name is fully offline against a bundled gazetteer of roughly 8,900 places (every Chinese province, city and district, plus world cities above 100,000 residents), so only the forecast request itself goes online, and that endpoint (Open-Meteo) needs no API key.
- Step count is read on demand, only while the moment line is being composed, instead of listening to the step sensor in the background.
- AI playlists remember the prompt they were generated from and show it under the title on the playlist screen. Manually built playlists have no prompt and are unaffected.
- A rating segment in the player toggle row: tapping the star expands a five-star row so a song can be rated without leaving the player.
- A Year row in the song info sheet that opens that year's detail screen.
- Playing the whole top-songs list as a queue straight from listening stats.
- A Statistics section in settings with No limit on stats rankings, so song, artist and album rankings show everything instead of stopping at 100.
- The recent-mixes row became a compact card row with its own screen listing every generated mix, and a mix is named after the idea word that was tapped.

### Changed
- The AI banner's Start pill is renamed Describe (`说一句` in Chinese) and both pills moved onto their own line, so the banner title no longer has to wrap on a 360dp screen.
- The AI entry and Serendipity surfaces are localized in Simplified Chinese, including the idea chips, the sampling controls and the new weather settings.
- The song info sheet can also be opened by tapping the title in the full player.

### Fixed
- Lists no longer end underneath the mini player: the missing bottom space was added on the duplicate-songs screen, the year detail song list and the three stats ranking screens.

## [0.4.0-pisces.1] - 2026-09-11

The first stable release of the personal fork, rolling up the work carried on the fork branch
along with everything that landed on `main` afterwards.

### Added
- AI playlist generation: describe a mood or a moment in plain language and the pick is made from your own library. A dedicated AI settings category works with any OpenAI-compatible endpoint and ships three providers (Xiaomi MiMo, Volcano Engine Ark, and a custom provider with a user-supplied base URL), storing keys per provider and letting you set the API key and model id with a one-tap model list fetch and picker; Xiaomi MiMo Token Plan keys (`tp-`) and pay-as-you-go keys (`sk-`) resolve to their own endpoints automatically. The home AI Mix card opens the prompt sheet, built-in idea chips cover the common moods, and the library slice can be sampled from most-played tracks (stable results, so asking twice costs one request) or randomly (a fresh shuffle that reaches songs you never play) at 15, 25 or 40 songs. Results can be played immediately, trimmed song by song, or saved as a playlist named `yyyyMMdd-HHmm`, and a recently generated row lists the recent mixes.
- Third-party import for Poweramp `.poweramp-backup` files, behind a source picker built to accept more sources later. The parsed backup is previewed with song, playlist and matched counts, the match rate, and examples of unmatched songs before you commit. Matching combines title, artist, album and duration with file paths, and normalises external SD card volume-serial paths (`9C33-6BBD/...`) and multiple storage roots. Playlists, playback history, play counts, and favourites and ratings import independently, with an optional rating threshold that marks highly rated songs as favourites, and a result summary is reported per category.
- Five-star ratings stored as a database field instead of embedded file tags, so cloud tracks can be rated too. Un-favouriting a song keeps its rating, batch metadata edits no longer wipe unrelated tags, and an interactive star row was added to the song info sheet.
- A Years library tab bucketing songs by release year, with a year detail screen supporting play, shuffle and sort.
- Deletion protection, a switch that turns song deletion off by default so a mis-tap cannot destroy files.
- In-app diagnostic logs with an adjustable level (Verbose to Error, WARN by default in release builds), one-tap export and share backed by an in-memory ring buffer with a rolling file and logcat fallback, diagnostic logs attached to crash reports, and oversized logs truncated so copying or sharing cannot hang.
- AI provider configuration in backups, and a dedicated song-matching resolver for playlist restore.
- The changelog sheet now renders the repository `CHANGELOG.md` at runtime instead of a hardcoded list, so it tracks the file and stays current without hand-editing in two languages per release. Section titles stay localized, and entries are shown verbatim.

### Changed
- Listening stats were promoted from a home card to a top-level bottom-bar tab and rebuilt: recently played grouped by day with repeated songs merged and counted, absolute period browsing (today, week, month, year, all) with previous, next and reset to the current period, an overview card for listening duration, plays, songs and artists, and full top-song, top-artist and top-album rankings with a show-all view and sorting by plays or duration.
- Playback events now carry a play-count weight, opening the stats screen flushes the in-progress listening session so the song currently playing appears immediately, and zero-duration events imported from Poweramp are expanded by play count so imported history is not lost.
- Media3 upgraded to 1.11.0.
- Debug and release builds can coexist: the debug build has its own application id suffix, its own name, and a red launcher icon.
- Only `arm64-v8a` is built, with a uniform artifact name of `pixelplayeross-<abi>-<version>-<buildtype>.apk`.
- Release signing credentials fall back to the `KEY_*` environment variables when no keystore properties file is present, so passwords no longer need to touch the disk.
- Ten design documents covering the AI port, listening stats, Poweramp history alignment, playlist restore, audio output options, and the Media3 upgrade path.

### Fixed
- Playlist restore failing on release builds: the R8 keep rules did not cover `PendingSongRef` in `data.backup.restore`, so obfuscation renamed its fields and every string in the backup payload arrived null. A payload this build does not recognise now degrades to no metadata, which still accepts a direct-ID hit, instead of throwing.
- Favourites backups failing because of a duplicate Gson field name.
- The Media3 1.11.0 `onConnect` command-set regression that made playback state jump back after connecting.
- Jellyfin playlists no longer go missing on Jellyfin 10.10 and newer, where playlists can hold mixed content and audio playlists are often reported without a media type.
- Folder ancestry is compared using the path's own separator rather than the host `File.separatorChar`, so the result no longer depends on the operating system.
- Play counts are preserved when exporting and restoring playback history.

### Security
- AI request logging redacts `key`, `token` and `secret` URL parameters before anything is written to disk, and API keys never enter a log entry.
## [0.3.0] - 2026-08-15

### Added
- Optional ListenBrainz scrobbling, disabled by default. Connect a ListenBrainz account with a user token from the Accounts screen; listens that reach the ListenBrainz threshold (4 minutes or half the track, whichever is lower) queue offline and submit with retry, with per-source toggles for local files, Subsonic, and Jellyfin playback. Now-playing status is reported while scrobbling is enabled, and disconnecting deletes any queued listens. An optional custom server URL scrobbles to self-hosted ListenBrainz-compatible servers such as Maloja instead of listenbrainz.org.
- Offline downloads for Navidrome/Subsonic and Jellyfin tracks, with per-track progress, retry/removal actions, album downloads, app-private storage, and transparent local playback when a download is available, plus a dedicated download management screen.
- On-demand MusicBrainz enrichment with ranked result selection and local recording, release, and artist identifiers. Existing metadata is preserved except for missing or unknown values.
- Translations for twelve languages with an in-app language picker, including Turkish and Simplified Chinese (`zh-rCN`). The selected language now also applies to the login and external player screens.
- Audio bookmarks for saving your place inside long tracks such as mixes, audiobooks, and DJ sets.
- Offline natural-language playlist creation: describe the playlist you want and it is built locally, with no network calls.
- Passphrase-protected backups.
- Search in Settings, so a toggle can be found without digging through categories.
- Tempo-matched crossfades and smoother play/pause transitions.
- Opt-in performance recorder for debugging lag reports.

### Changed
- Listening-stats hour labels follow the system 12/24-hour clock format.
- Cached album art is size-capped and artwork extraction is skipped during library scans, reducing memory use and scan time.
- Alpha releases are skipped for docs-only and CI-only changes; the standalone phone APK workflows were dropped in favour of alpha releases.

### Fixed
- Cloud artwork now reaches media notifications, the lock screen, and external media controllers.
- Offline download cancellation races that could leave partial files or stuck progress.
- Restored queues no longer keep dead local proxy links.
- Cloud streams that cannot be resolved now surface a clear error instead of failing silently.
- Synced embedded lyrics are preferred, and the lyrics API rate limiter no longer throttles valid requests.
- Shuffle changes stay in sync with the media session.
- Folder filters stay in sync with the library, and comma-separated genre tags are split correctly.
- The song info sheet no longer overflows on small screens.
- Bookmark buttons that were still hardcoded English are localized.
- The themed launcher icon is restored.

### Security
- Credentials are redacted from debug network logs.

### Removed
- Android Auto media-library browsing and discovery. Standard MediaSession playback controls for notifications, lock screen, Bluetooth devices, and other system surfaces remain available.

## [0.2.0] - 2026-07-17

### Added
- Navidrome library selector for servers that expose more than one music library.
- The Artists tab and cloud albums are grouped by album artist.
- Source code and F-Droid links in the About screen, plus GitHub Sponsors metadata.

### Changed
- Material 3 Expressive pass: motion scheme, wavy progress indicators, and shape morphs.
- New app icon and redesigned header.
- Leaner R8 rules and regenerated baseline profiles.
- Coroutines, Flow, and Compose hygiene pass, plus dead-code and deprecated-API cleanup.
- Dependency bumps across Material 3, Compose, OkHttp, core-ktx, and lifecycle.

### Fixed
- Local server connections on Android 17.
- Plain HTTP is allowed for local Navidrome and Jellyfin servers, including Tailscale and VPN hosts.
- GitLab mirror repository guard after the organisation move.

### Security
- User-installed CAs are trusted so self-signed cloud servers work without disabling verification.
- State-changing media session commands are restricted to trusted clients, and artwork sharing stays private behind explicit URI grants.

## [0.1.0] - 2026-06-09

### Initial release
- First public FOSS release of PixelPlayerOSS, an OSS-focused Android music player.
- Includes local music playback, playlists, favorites, lyrics, listening stats, dynamic Material 3 theming, widgets, and backup/restore.
- Keeps self-hosted library support for Navidrome/Subsonic and Jellyfin, plus optional LRCLIB lyrics and Deezer artist artwork lookups.

### Removed for FOSS
- Removed non-FOSS and Google Play oriented integrations: Telegram, NetEase, QQ Music, Google Drive, Gemini, Cast, Wear OS, Play Store billing, Firebase, Crashlytics, and Google Play Services runtime dependencies.
- Removed public scrobbling integrations such as Last.fm and ListenBrainz; self-hosted Navidrome/Subsonic playback reporting remains scoped to the user's own server.
- Removed bundled translations and the in-app language selector for the first FOSS release; the initial source release ships with English resources only.
- Removed release paths that depended on local/private signing artifacts, dummy signing values, or app-store-only assumptions.

### Release readiness
- Added F-Droid metadata, Fastlane store metadata, dependency/license documentation, privacy notes, security notes, and contributor guidance.
- Release builds now stay unsigned when local signing keys are absent, and `pixelplayer.disableReleaseSigning=true` forces unsigned verification builds even on a maintainer machine.
- Documented third-party asset and dependency licenses, including native/binary Maven artifacts and JitPack source trails.

### Security and privacy
- The loopback cloud-stream proxy now requires a per-session token so other apps on the device cannot stream the user's cloud library by guessing local proxy URLs.
- Backup restore now ignores preference keys owned by dedicated module handlers, preventing crafted global-settings payloads from bypassing module validation.
- Release logging is tightened so HTTP request headers and remaining raw Android logs do not bypass the Timber release filter.

### App polish included in this FOSS release
- Added smart playlist persistence, duplicate-track scanning, playback speed control, clearer playback/sync failure messages, and retry actions on album/artist detail failures.
- Improved accessibility for toggle states and song row actions.
