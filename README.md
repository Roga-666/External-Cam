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
    ./gradlew :app:assembleRelease   # minified release (configure signing first)
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
