# USB Camera Viewer — Design Spec

Date: 2026-07-22
Author: brainstormed with Claude
Status: approved

## 1. Product

Android app for USB UVC endoscopes/borescopes ("USB Camera Viewer" by muh).
Plug a UVC camera into the phone via USB-C OTG, auto-detect it, show live
preview, capture photos and videos, browse captures in an in-app gallery.

- `applicationId`: `com.muh.usbcameraviewer`
- App name: **USB Camera Viewer**
- Fully offline: no network permission, no analytics, no ads.
- Dark camera-first UI: the image is the hero.

## 2. Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.x |
| UI | Jetpack Compose + Material 3, single Activity, edge-to-edge |
| Architecture | MVVM — ViewModel + StateFlow + sealed UI states, single module |
| DI | Hilt |
| Async | Kotlin Coroutines / Flow |
| UVC camera | AUSBC 3.x (jiangdongguo/AndroidUSBCamera), wrapped behind our own `CameraController` interface |
| Image loading | Coil (gallery thumbnails) |
| Build | Gradle KTS, version catalog (`libs.versions.toml`) |
| SDK | minSdk 26, targetSdk 35 |
| Lint/quality | Android lint, detekt, ktlint |

Rationale for AUSBC: most Android phones ship no HAL support for external
USB cameras, so Camera2 is unreliable for endoscopes. AUSBC wraps
libusb/libuvc via JNI and handles attach/permission/preview/record.
The `CameraController` interface isolates the app from the library so it
can be swapped (e.g. for herohan/UVCAndroid) without touching features.

## 3. Screens

### 3.1 Viewer (home)

Full-bleed live preview (`AspectRatioTextureView` via `AndroidView`),
overlay controls:

- Shutter button (photo) / record button (video), photo–video mode toggle
- Resolution/format picker (list negotiated from device)
- Rotate (0/90/180/270) and mirror toggles
- Gallery shortcut (last-capture thumbnail)
- Settings entry

UI states (sealed):

- `NoDevice` — illustrated "plug in your camera" + OTG help text
- `PermissionNeeded` — explain + re-trigger system USB permission dialog
- `Connecting` — progress
- `Streaming` — live preview + controls
- `Error(type)` — readable message + retry action

### 3.2 Gallery

- Grid of captures (photos + videos, newest first), Coil thumbnails
- Full-screen pager: pinch-zoom photos, play videos, share, delete (with confirm)
- Backed by MediaStore query scoped to the app's own collections

### 3.3 Settings

- Default resolution behavior (remember last / always ask / fixed)
- Video quality (bitrate preset)
- Record audio toggle (default off)
- Filename prefix (default `UCV`)
- Keep screen on while streaming (default on)
- About: version, developer "muh", open-source licenses

## 4. USB device flow

- Manifest: `USB_DEVICE_ATTACHED` intent filter + `device_filter.xml`
  matching USB video class (class 14) — app offers to launch on plug-in.
- Runtime flow: attach event → request USB permission (system dialog) →
  open device → enumerate formats → negotiate (prefer MJPEG, fall back to
  YUYV) → start stream.
- Detach at any point → graceful teardown → `NoDevice` state.
- Hot re-plug supported without app restart.
- Multiple devices: pick first UVC device; if more than one, simple chooser.

## 5. Capture

- **Photos**: JPEG via MediaStore into `Pictures/USB Camera Viewer/`.
  On API 29+ scoped storage — no permission needed for own collections.
  On API 26–28, `WRITE_EXTERNAL_STORAGE` (manifest `maxSdkVersion="28"`)
  requested at runtime before first capture.
- **Videos**: MP4 (H.264) via AUSBC recorder into `Movies/USB Camera Viewer/`.
  Optional mic audio; requesting RECORD_AUDIO only when user enables audio.
- Filenames: `<prefix>_yyyyMMdd_HHmmss.jpg|mp4`.
- Capture feedback: shutter flash animation + thumbnail update; recording
  shows elapsed-time chip.

## 6. Errors and edge cases

| Case | Handling |
|---|---|
| Phone lacks USB host/OTG | Detect via `PackageManager.FEATURE_USB_HOST`; explain clearly |
| USB permission denied | `PermissionNeeded` state with retry |
| Unsupported camera / no UVC formats | Typed error, suggest checking camera class |
| Camera busy or open failure | Retry action, teardown cleanly |
| Detach mid-recording | Stop and finalize MP4 safely, keep partial file, notify |
| Low storage | Pre-check before record; warn |
| App backgrounded while streaming | Stop preview, release device; resume on foreground |

All failures map to a typed `CameraError` and user-readable message with a
recovery action. No raw exceptions surfaced to UI.

## 7. Testing

- **Unit tests**: ViewModels and connection/capture state machines against
  a fake `CameraController`. Filename generation, settings repository.
- **Compose UI tests**: each Viewer state renders correct controls;
  gallery empty/populated states.
- **Manual hardware checklist** (docs/hardware-test-checklist.md):
  attach/detach cycles, permission deny/grant, capture both modes,
  mid-record detach, re-plug, rotation.
- CI-ready: `./gradlew lint detekt ktlint test` green.

## 8. Deliverables

- Complete buildable project (debug + release; release minified with
  ProGuard/R8 rules for AUSBC/JNI)
- Adaptive launcher icon
- README (features, screenshots placeholder, build instructions, hardware
  compatibility notes)
- Hardware test checklist doc
- This spec + implementation plan under `docs/superpowers/`

## Out of scope (v1)

- UVC controls (brightness/contrast/focus) — v2 candidate
- Multi-camera simultaneous view
- Photo editing/annotation
- Play Store listing assets
