# F-Droid (historical, upstream only)

Archived notes about the **upstream** PixelPlayerOSS F-Droid listing. PiscesPlayer is an
independent fork (`com.pisces312.piscesplayer`), is **not** published on F-Droid, and ships
signed GitHub releases only. **Nothing in this file is an instruction for this fork.**

The upstream listing was <https://f-droid.org/packages/com.lostf1sh.pixelplayeross/>, fed by
Fastlane metadata and an official recipe in `fdroiddata`
(<https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.lostf1sh.pixelplayeross.yml>).
That recipe used `AutoUpdateMode: Version` with `UpdateCheckMode: Tags`, built
`:app:assembleRelease` with `pixelplayer.enableAbiSplits=false` and
`pixelplayer.disableReleaseSigning=true`, and stripped the `foojay` toolchain resolver plus the
`-XX:+ParallelRefParsingEnabled` JVM argument with `sed`. F-Droid signed those builds with its
own key, so they could not update over the project-key GitHub builds. The listing also required
GPL-3.0, no Firebase / GMS / billing / ads / Cast / Wear OS dependencies, opt-in ListenBrainz
scrobbling, and a documented privacy policy.

The local mirror `metadata/com.lostf1sh.pixelplayeross.yml` was removed on 2026-11-14: it was
an executable upstream recipe whose package name, author, URLs and 0.1.0-0.3.0 version codes
no longer apply here. Upstream attribution is carried by `NOTICE`, `PROVENANCE.md`, the README
licence block, the About screen and the fastlane description instead.

The sections below are retained because their licence / native-library / JitPack evidence is
still relevant to GPL-3.0 redistribution of **this** fork.

## Asset Licenses

General third-party notices are tracked in `THIRD_PARTY_NOTICES.md`; a short copy is also bundled at `app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md`.

The dependency license review table is tracked in `docs/DEPENDENCY_LICENSES.md`.

| Asset | Source | License evidence |
| --- | --- | --- |
| `app/src/main/res/font/gflex_variable.ttf` | Google Sans Flex, `https://fonts.google.com/specimen/Google+Sans+Flex/license?preview.script=Latn` | Google Fonts license page declares SIL Open Font License 1.1; OFL text is included at `app/src/main/assets/licenses/OFL.txt`. |
| `app/src/main/res/font/genre_variable.ttf` | Roboto Flex, `https://github.com/googlefonts/roboto-flex` | SIL Open Font License 1.1 text is included at `app/src/main/assets/licenses/OFL.txt`. |

## Native And Binary Maven Artifacts

These artifacts can produce native `.so` files in the APK. Keep the source/license trail current when versions change.

The project source is GPL-3.0-licensed. Release APKs also include `org.jellyfin.media3:media3-ffmpeg-decoder`, whose Maven POM declares GPL-3.0 — consistent with the source license. Preserve the source/license trail below.

| Artifact | Native library seen in APK | License/source evidence |
| --- | --- | --- |
| `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` | `libffmpegJNI.so` | Maven POM declares GPL-3.0 and SCM `https://github.com/jellyfin/jellyfin-androidx-media`. |
| `io.github.kyant0:taglib:1.0.6` | `libtaglib.so` | Maven POM declares Apache-2.0 and SCM `https://github.com/Kyant0/taglib`. |
| `androidx.graphics:graphics-shapes:1.1.0` | `libandroidx.graphics.path.so` | Google Maven POM declares Apache-2.0 and SCM `https://android.googlesource.com/platform/frameworks/support`. |

## JitPack Dependencies

`settings.gradle.kts` allows JitPack only for these groups:

| Group | Why it is allowed | License/source evidence |
| --- | --- | --- |
| `com.github.racra` | Direct dependency `libs.smooth.corner.rect.android.compose`. | POM and upstream repository declare MIT License: `https://github.com/racra/smooth-corner-rect-android-compose`. |
| `com.github.philburk` | Transitive dependency `com.github.philburk:jsyn` required by `androidx.media3:media3-exoplayer-midi`. | POM declares Apache-2.0 and SCM `https://github.com/philburk/jsyn`. |
