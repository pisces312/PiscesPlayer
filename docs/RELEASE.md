# Release Checklist

PiscesPlayer releases are tagged from `main` — `main` is the release starting point, not the
only branch development happens on. Feature branches are merged into `main` before a release
is cut, otherwise the tag would miss their work. Every release candidate then passes the local
checks below and a basic device smoke test.

## Versioning

Version values live in `gradle.properties`:

```properties
APP_VERSION_NAME=1.0.0
APP_VERSION_CODE=1000001
```

For every public release:

1. Update `APP_VERSION_NAME` to a plain semantic version (`X.Y.Z`).
2. Increment `APP_VERSION_CODE`.
3. Move the relevant `CHANGELOG.md` entries from `Unreleased` to the release version.
4. Tag the release as `v<APP_VERSION_NAME>`, for example `v1.0.0`.

## Required Local Checks

Run these before tagging:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:compileDebugKotlin
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:lintDebug
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease -Ppixelplayer.enableAbiSplits=false -Ppixelplayer.disableReleaseSigning=true
```

For split APK artifacts, use:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease -Ppixelplayer.enableAbiSplits=true
```

## Signing

Release signing is driven by `KEY_*` environment variables and nothing else. No
`keystore.properties` file is read, so no plaintext password ever has to touch the disk:

```sh
export KEY_STORE_LOCATION=/absolute/path/to/release.jks
export KEY_STORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
```

`KEY_STORE_LOCATION` is resolved against the repository root when it is relative. If any of
the four is unset, or the keystore file does not exist, release builds come out **unsigned
rather than failing** — always confirm with
`apksigner verify --print-certs -v <apk>`. There is no fallback to a properties file, and no
keystore is ever committed.

This repository has no CI: the upstream build workflows were removed when the project became
independent, and nothing here signs or publishes automatically. Releasing is a local build
followed by a local `gh release create`.

For unsigned local verification builds, pass `-Ppixelplayer.disableReleaseSigning=true` even when the environment variables are set.

## Device Smoke Test

Install the release candidate and verify:

1. First launch and setup complete.
2. Local library scan finds music.
3. Playback starts, pauses, resumes, skips, and survives backgrounding.
4. Full player opens and closes smoothly.
5. Widget controls still reach the playback service.
6. Navidrome and Jellyfin login screens open.
7. Backup export flow creates a file.

## In-App Updates

The Updates screen (`data/update/`) checks this fork's GitHub releases. Keep these formats stable:

- **Tags:** `v<X.Y.Z>`, for example `v1.0.0`.
- **APK assets:** `piscesplayer-<abi>-<X.Y.Z>-release.apk`, for example
  `piscesplayer-arm64-v8a-1.0.0-release.apk`.
- **Release visibility:** publish the release as a normal (non-draft) GitHub release.

The updater accepts both plain semantic versions and historical prerelease tags such as
`v<X.Y.Z>-alpha.<N>` or `v<X.Y.Z>-pisces.<N>`, but new releases should use plain `v<X.Y.Z>`.
It matches APK assets by ABI as a whole filename segment and installs only releases whose
package name and signing key match the running app.

## Publishing

1. Merge the feature branch being released into `main`, then ensure `main` is clean and pushed.
2. Create a tag: `git tag v<APP_VERSION_NAME>`.
3. Push the tag: `git push origin v<APP_VERSION_NAME>`.
4. Create a GitHub release from the tag.
5. Attach APK artifacts named `piscesplayer-<abi>-<APP_VERSION_NAME>-release.apk` and paste the changelog section.

## F-Droid Metadata

This fork is not published on F-Droid. The Fastlane metadata under
`fastlane/metadata/android/en-US` is still maintained for release-page presentation and any
future store submission, but no F-Droid build recipe is expected for `com.pisces312.piscesplayer`.
When preparing a release, still update `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
and check that `PRIVACY.md` matches the optional network services present in the app.
