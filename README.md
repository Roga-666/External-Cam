# External Cam

**External Cam** is a free, offline Android USB camera viewer for UVC-compatible webcams, endoscopes, microscopes, borescopes, and USB video capture cards. This project is forked from [USB Camera Viewer by muh](https://github.com/13/usbcameraviewer) and retains the original MIT license and attribution.

[![CI](https://github.com/Roga-666/External-Cam/actions/workflows/ci.yml/badge.svg)](https://github.com/Roga-666/External-Cam/actions/workflows/ci.yml)

## Features

- USB-C OTG camera preview, automatic connection detection and reconnect handling
- Photo capture, MP4 recording (optional phone microphone), in-app gallery
- Resolution selection, rotate/mirror, pinch-to-zoom and pan, brightness/contrast
- **USB diagnostics:** Settings displays connected USB devices, vendor/product IDs, and whether Android detects a UVC video interface. You can refresh the list after plugging in a device.
- **Accessible offline troubleshooting (v0.1.2):** Gallery and Settings icons appear even on the "Plug in your camera" screen. The empty state has a USB diagnostics button, and the diagnostics section is the first section in Settings. No camera connection is needed to open diagnostics.
- **Improved composite-camera detection (v0.1.1):** Probe each USB interface directly, including valid class-0 UVC devices previously ignored by the bundled AUSBC camera filter. Also rescan devices already connected at startup or after returning to the app.
- Fully offline; no analytics, ads, or internet permission

USB diagnostics can identify a device but **cannot guarantee** that the bundled UVC engine can stream from it. This initial version still uses AUSBC 3.2.7.

## Installation and updates

The independent Android application ID is `com.roga666.externalcam`, so External Cam installs beside the original USB Camera Viewer. Starting with External Cam v0.1.0 (`versionCode` 1), all published updates must increment `versionCode` and be signed with the **same permanent release keystore**.

**GitHub Releases are intentionally blocked until the signing secrets are configured.** This prevents publishing unsigned APKs that cannot update cleanly.

**Recommended on Vegapunk:** the App-Store repository includes a dedicated one-time setup helper for this eighth app, `scripts/setup-external-cam-signing.sh`. Run it from `~/App-Store` after pulling the latest changes. The script creates or reuses `~/roga-android-signing/External-Cam-release.p12`, then securely installs the required four GitHub Actions secrets into **this** repository without exposing your password in the chat.

| Repository secret | Purpose |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | Permanent PKCS12 signing keystore |
| `ANDROID_RELEASE_STORE_PASSWORD` | Keystore password |
| `ANDROID_RELEASE_KEY_ALIAS` | Key alias (normally `release`) |
| `ANDROID_RELEASE_KEY_PASSWORD` | Alias password |

The workflow also accepts the older `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` aliases as a fallback.

**Do not commit or share signing keys or passwords. Back up the local `.p12` and its password separately.** These are needed for every future in-place APK update.

A deliberate `versionName` and `versionCode` increase in `app/build.gradle.kts`, merged to `main`, automatically triggers tests, the signed release build, and a new GitHub Release. The release workflow also supports manual `v*` tags. Never reuse an existing version or signing key.

Local build: `./gradlew :app:assembleDebug` for development, or `./build.sh release --gates` with a local keystore configured. **Debug APKs use a different signature and cannot update installed release APKs.**

## Camera compatibility

Android 8.0+ with USB host/OTG and a UVC-compatible USB camera is required for the current camera backend. A USB-C *data* cable/OTG adapter and sufficient USB power are essential. Open **Settings → USB diagnostics** to inspect the USB device and check for a video interface (class 14). Composite UVC devices can advertise class 0 or 239 at the device level.

Some proprietary cameras and capture devices do not support UVC and will need additional drivers. Some UVC cameras advertise formats or resolutions they cannot actually stream. The app first tries MJPEG and falls back to YUYV if format setup fails. If MJPEG starts but produces no usable frames, External Cam also retries that resolution explicitly in YUYV before reverting to the previous working resolution.

### Known limitations

- The current AUSBC 3.2.7 native binaries were built for 4 KB memory pages, not 16 KB. They must be rebuilt or replaced before proper support for 16 KB-page devices and compliant Play distribution.
- Only one camera is selected automatically; a multiple-camera chooser is planned.
- Live preview and capture quality depend on the UVC hardware and device-supported formats.
- The original source namespace `com.muh.usbcameraviewer` remains internal for now to avoid risky all-at-once refactoring. The install ID is uniquely `com.roga666.externalcam`.

See [the hardware checklist](docs/hardware-test-checklist.md) and [the compatibility roadmap](docs/compatibility-roadmap.md). Actual camera compatibility requires testing on physical Android devices.

## License and credits

MIT licensed. Based on [13/usbcameraviewer](https://github.com/13/usbcameraviewer) by muh. The original [MIT copyright notice](LICENSE) remains intact. Third-party native components and their license texts are listed in the app under Settings → Open-source licenses.
