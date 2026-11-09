# External Cam

**External Cam** is a free, offline Android USB camera viewer for UVC-compatible webcams, endoscopes, microscopes, borescopes, and USB video capture cards. This project is forked from [USB Camera Viewer by muh](https://github.com/13/usbcameraviewer) and retains the original MIT license and attribution.

[![CI](https://github.com/Roga-666/External-Cam/actions/workflows/ci.yml/badge.svg)](https://github.com/Roga-666/External-Cam/actions/workflows/ci.yml)

## Features

- USB-C OTG camera preview, automatic connection detection and reconnect handling
- Photo capture, MP4 recording (optional phone microphone), in-app gallery
- Resolution selection, rotate/mirror, pinch-to-zoom and pan, brightness/contrast
- **USB diagnostics:** Settings displays connected USB devices, vendor/product IDs, and whether Android detects a UVC video interface. You can refresh the list after plugging in a device.
- Fully offline; no analytics, ads, or internet permission

USB diagnostics can identify a device but **cannot guarantee** that the bundled UVC engine can stream from it. This initial version still uses AUSBC 3.2.7.

## Installation and updates

The independent Android application ID is `com.roga666.externalcam`, so External Cam installs beside the original USB Camera Viewer. Starting with External Cam v0.1.0 (`versionCode` 1), all published updates must increment `versionCode` and be signed with the **same permanent release keystore**.

**GitHub Releases are intentionally blocked until the signing secrets are configured.** This prevents publishing unsigned APKs that cannot update cleanly.

Create and securely back up a keystore **on your own computer** (never commit it or share its passwords):

```bash
keytool -genkeypair -v -keystore external-cam-release.jks -alias externalcam -keyalg RSA -keysize 3072 -validity 10000
```

In GitHub repository **Settings → Secrets and variables → Actions**, configure these four repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_B64` | Base64 encoding of the `.jks` file (one line) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (`externalcam` in the example) |
| `KEY_PASSWORD` | Alias/key password |

To generate the Base64 value locally on Linux, run `base64 -w0 external-cam-release.jks` and copy the output directly into the GitHub secret. **Back up the .jks and passwords offline:** loss of this key means installed release users cannot receive normal updates signed by a replacement key.

A tag `v*` (for example `v0.1.0`) triggers tests, builds and verifies a signed release APK, and attaches it as `External-Cam-v0.1.0.apk`.

Local build: `./gradlew :app:assembleDebug` for development, or `./build.sh release --gates` with a local keystore configured. **Debug APKs use a different signature and cannot update installed release APKs.**

## Camera compatibility

Android 8.0+ with USB host/OTG and a UVC-compatible USB camera is required for the current camera backend. A USB-C *data* cable/OTG adapter and sufficient USB power are essential. Open **Settings → USB diagnostics** to inspect the USB device and check for a video interface (class 14). Composite UVC devices can advertise class 0 or 239 at the device level.

Some proprietary cameras and capture devices do not support UVC and will need additional drivers. Some UVC cameras advertise formats or resolutions they cannot actually stream. The app already tries MJPEG and YUYV for resolution changes and reverts on failed frame verification.

### Known limitations

- The current AUSBC 3.2.7 native binaries were built for 4 KB memory pages, not 16 KB. They must be rebuilt or replaced before proper support for 16 KB-page devices and compliant Play distribution.
- Only one camera is selected automatically; a multiple-camera chooser is planned.
- Live preview and capture quality depend on the UVC hardware and device-supported formats.
- The original source namespace `com.muh.usbcameraviewer` remains internal for now to avoid risky all-at-once refactoring. The install ID is uniquely `com.roga666.externalcam`.

See [the hardware checklist](docs/hardware-test-checklist.md) and [the compatibility roadmap](docs/compatibility-roadmap.md). Actual camera compatibility requires testing on physical Android devices.

## License and credits

MIT licensed. Based on [13/usbcameraviewer](https://github.com/13/usbcameraviewer) by muh. The original [MIT copyright notice](LICENSE) remains intact. Third-party native components and their license texts are listed in the app under Settings → Open-source licenses.
