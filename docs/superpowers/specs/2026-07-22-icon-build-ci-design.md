# Icon, Build Script, Release CI — Design Spec

Date: 2026-07-22
Status: approved

## 1. Launcher icon (refined lens motif)

- Replace `ic_launcher_foreground.xml` with a layered lens design: outer
  bezel ring with linear/radial gradient depth, inner aperture ring, dark
  pupil, off-center highlight glint. All strokes/fills inside the 66dp
  safe-zone circle (center 54, radius 33 in 108-viewport units) — fixes
  the current cable-clipping issue.
- New `ic_launcher_monochrome.xml` (single-tone silhouette of the same
  motif) referenced via `<monochrome>` in `ic_launcher.xml` and
  `ic_launcher_round.xml` — Android 13+ themed icons.
- Background: `drawable/ic_launcher_background.xml` radial gradient
  (deep blue-black center `#101B24` to `#0B0F12` edge) replacing the flat
  color resource.
- Palette stays on app theme: cyan-blue `#7FD0FF` / `#C4E7FF` on dark.
- Play Store 512px PNG export: out of scope; README note only.

## 2. Release signing plumbing

`app/build.gradle.kts` gains `signingConfigs.release` resolved in order:
1. `keystore.properties` at repo root (git-ignored): `storeFile`,
   `storePassword`, `keyAlias`, `keyPassword`.
2. Env vars: `UCV_KEYSTORE_FILE`, `UCV_KEYSTORE_PASSWORD`,
   `UCV_KEY_ALIAS`, `UCV_KEY_PASSWORD`.
3. Neither present → release builds unsigned (no failure).

`keystore.properties` and `*.jks`/`*.keystore` added to `.gitignore`.

## 3. build.sh (repo root)

`./build.sh [debug|release] [--install] [--gates]`
- Bash, `set -euo pipefail`, cd-safe (resolves own dir).
- JDK auto-detect: `$JAVA_HOME` if valid, else first of
  `/usr/lib/jvm/java-21-openjdk`, `java-17-openjdk` that exists; error
  with clear message otherwise.
- `--gates` runs `test detekt ktlintCheck lint` first.
- Builds variant, prints APK path + human size; `--install` runs
  `adb install -r`.
- Release: notes signed/unsigned status based on signing inputs.

## 4. GitHub Actions release workflow

`.github/workflows/release.yml`, trigger `push: tags: ['v*']`:
1. Checkout, Temurin JDK 21, `gradle/actions/setup-gradle` cache.
2. Gates: `./gradlew test detekt ktlintCheck lint`.
3. If secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD` present: decode keystore to file, export UCV_* env vars.
   Absent → skip (unsigned APK).
4. `./gradlew :app:assembleRelease`, copy APK to
   `usb-camera-viewer-<tag>.apk`.
5. `softprops/action-gh-release` creates Release for the tag with APK
   attached, auto-generated notes.
- Repo currently has no remote; workflow activates on first push to
  GitHub. README gains "Releases" section documenting tag flow + secrets.

## Out of scope

- Play Store listing assets / 512px raster export
- AAB (bundle) output — APK only for now
- F-Droid metadata
