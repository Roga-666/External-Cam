# Changelog

All notable changes to USB Camera Viewer. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions follow SemVer.

## [1.1.0] — unreleased

### Fixed
- "Plug in your camera" no longer appears after visiting Settings or the Gallery while the
  camera stays plugged in (the library fires a disconnect for its own close; now ignored when
  the device is still on the USB bus).
- Recording interrupted by unplugging or navigating away is saved with what was captured so
  far instead of failing.
- Photos are captured at the stream resolution (previously upscaled to screen size).
- Rotate/mirror are baked into captured photos; videos store rotation as playback metadata and
  bake mirror.
- Rotating the preview 90°/270° no longer stretches the picture.
- Dark scenes no longer falsely revert a resolution switch (liveness now detects frame change,
  not just brightness).
- Resolution switching is blocked while recording (it would freeze seconds of the file).
- Video encoder no longer crashes the app when stopping takes longer than expected.
- Second USB camera attaching no longer hijacks the active session.
- Media scan is anchored to the app's own album (no longer matches similarly named albums).

### Added
- Brightness and contrast sliders (UVC hardware controls), persisted across sessions.
- Pinch-zoom and pan on the live preview (double-tap toggles 2×; +/− buttons for
  accessibility); photos and videos capture the zoomed view.
- German, Spanish, French, and Portuguese translations.
- Predictive back gesture support.
- CI workflow (tests, detekt, ktlint, lint) and an instrumented smoke test.

### Known
- Google Play distribution blocked by the camera library's 4 KB-aligned native libraries
  (16 KB page-size requirement) — see `docs/16kb-page-size-plan.md`. Sideloading unaffected.

## [1.0.0] — 2026-07-22

Initial release: live UVC preview, photo/video capture with optional microphone audio,
resolution switching, rotate/mirror, gallery with share/delete, settings, open-source licenses.
