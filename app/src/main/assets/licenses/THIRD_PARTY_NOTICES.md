# Third-Party Notices

PiscesPlayer is licensed under the GNU General Public License v3.0 (GPL-3.0) and includes third-party open-source components.

Bundled fonts:

| Asset | Source | License |
| --- | --- | --- |
| Google Sans Flex (`gflex_variable.ttf`) | `https://fonts.google.com/specimen/Google+Sans+Flex/license?preview.script=Latn` | SIL Open Font License 1.1 |
| Roboto Flex (`genre_variable.ttf`) | `https://github.com/googlefonts/roboto-flex` | SIL Open Font License 1.1 |

Bundled data:

| Asset | Source | License |
| --- | --- | --- |
| `cities.json` (China) | `https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov` | MIT |
| `cities.json` (rest of the world) | GeoNames `cities15000`, `https://download.geonames.org/export/dump/` | CC-BY 4.0 |

Bundled audio:

| Asset | Source | License |
| --- | --- | --- |
| Sleep soundscape field recordings (`soundscape_*.ogg`: rain, wind, thunder, campfire, ocean, crickets) | `https://github.com/awr1001/natures-music-android` (© Aaron Roberts / App-O-Matic) | CC BY 4.0, `https://creativecommons.org/licenses/by/4.0/` |

The recordings are trimmed, loudness-normalized and re-encoded; see `THIRD_PARTY_NOTICES.md` in the source repository for the per-file table.

Native runtime artifacts:

| Artifact | Native library | License/source evidence |
| --- | --- | --- |
| `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` | `libffmpegJNI.so` | GPL-3.0, `https://github.com/jellyfin/jellyfin-androidx-media` |
| `io.github.kyant0:taglib:1.0.6` | `libtaglib.so` | Apache-2.0, `https://github.com/Kyant0/taglib` |
| `androidx.graphics:graphics-shapes:1.1.0` | `libandroidx.graphics.path.so` | Apache-2.0, AOSP/AndroidX source |

See `THIRD_PARTY_NOTICES.md` in the source repository for the fuller dependency review notes.
