# USB Camera Viewer

Modern Android viewer for USB (UVC) endoscopes and borescopes. By muh.

## Features

- Live preview over USB-C OTG — auto-launches when a camera is plugged in
- Photo capture and MP4 video recording
- In-app gallery: zoomable viewer, share, delete
- Resolution picker, rotate/mirror, recording timer
- Dark, camera-first UI (Jetpack Compose, Material 3)
- Fully offline — no INTERNET permission, no analytics

## Requirements

- Android 8.0+ (API 26), phone with USB host (OTG) support
- A UVC-class USB camera (most cheap endoscopes are UVC)

## Build

    ./gradlew :app:assembleDebug     # debug APK
    ./gradlew :app:assembleRelease   # minified release (unsigned unless signing configured)
    ./gradlew test detekt ktlintCheck lint

## Architecture

Single-module MVVM. Compose UI ← StateFlow ← ViewModels; all UVC access
behind `CameraController` (implemented by `AusbcCameraController`, using
[AUSBC] `libausbc` **3.2.7**). Captures publish to MediaStore
(`Pictures/USB Camera Viewer`, `Movies/USB Camera Viewer`).
See `docs/superpowers/specs/` for the design spec and
`docs/hardware-test-checklist.md` for the manual test pass.

[AUSBC]: https://github.com/jiangdongguo/AndroidUSBCamera

### Why 3.2.7, not the newer 3.3.x

`libausbc:3.3.3`'s libuvc/libnative artifacts don't publish cleanly on
JitPack, so the app is pinned to `libausbc:3.2.7` — the newest release whose
full module set actually resolves. The 3.2.7 API differs from newer
releases in a few places that matter here: `USBMonitor` and friends live in
`com.serenegiant.*` (not `com.jiangdg.usb.*`), there's no `CameraUVC`
subclass (`MultiCameraClient.Camera` is used directly), and
`CameraRequest.Builder` only exposes preview dimensions plus AF/AE flags —
see the doc comment on `AusbcCameraController` for the full list. Two
demo-only transitive dependencies pulled in by `libausbc` (`immersionbar`
and `webpdecoder`) are excluded in `app/build.gradle.kts` since neither is
published on the repos this project allows and no app code references them.

## Releases

Local build:

    ./build.sh debug              # debug APK
    ./build.sh release            # release APK (signed if keystore configured)
    ./build.sh release --gates    # run test/detekt/ktlint/lint first
    ./build.sh debug --install    # build + adb install

Release signing (optional — builds are unsigned without it): create
`keystore.properties` at the repo root (git-ignored):

    storeFile=/path/to/release.jks
    storePassword=...
    keyAlias=...
    keyPassword=...

CI: pushing a tag matching `v*` (e.g. `git tag v1.0.0 && git push origin v1.0.0`)
runs quality gates and attaches `usb-camera-viewer-<tag>.apk` to a GitHub
Release. To get a *signed* CI APK, add repository secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_B64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without secrets the workflow still succeeds and attaches an unsigned APK.
A 512 px Play Store icon export is not generated (vector-only sources);
render `ic_launcher_foreground`/`_background` at 512 px when needed.

## Known limitations

- **Recording is video-only.** AUSBC 3.2.7's `captureVideoStart` takes no
  audio toggle and no bit-rate parameter, so the Settings screen's "record
  audio" switch and video-quality picker have no effect on the actual
  recording — the app logs a warning (`AusbcCameraController`) and records
  video with no audio track at the library's default bit rate. Wiring these
  up will need a newer AUSBC release (or a custom encoder path).
- **Rotate/mirror only affect the live preview, not captures.** 3.2.7 has no
  render-side rotation or flip, so `setRotation`/`setMirror` transform the
  preview `View` itself (rotation/scaleX). Captured photos and videos are
  saved unrotated and unmirrored regardless of the on-screen preview
  orientation.
- UVC camera controls (brightness/focus) are not exposed yet (v2).
- **Single device only.** The first attached UVC device wins; there is no
  device chooser, so with multiple cameras connected the app streams whichever
  one the system reports first.
- **No shutter flash animation.** There is no screen flash on capture; the
  updated gallery thumbnail in the bottom-left corner is the capture feedback.
- **Microphone permission is no longer requested.** Recording is video-only
  (see above), so the app declares no `RECORD_AUDIO` permission and never
  prompts for the microphone.

## Quality gates

`detekt` and `ktlint` are configured with a small set of documented,
narrowly-scoped suppressions (Compose naming conventions, a few
project-specific thresholds) rather than broad rule disables — see
`config/detekt/detekt.yml`, `.editorconfig`, and inline `@Suppress`
annotations for the reasoning at each site.

## Hardware testing

This repo's automated suite is unit/Robolectric tests only — there is no
instrumented (on-device) test in CI. Before every release, run the manual
pass in `docs/hardware-test-checklist.md` on a physical phone with a real
UVC camera; it has not yet been run end-to-end against hardware as part of
this project's task series (no device was available), so treat it as a
template to fill in, not a completed report.
