# README Showcase — Design Spec

Date: 2026-07-22
Status: approved

## 1. Logo asset

- `docs/assets/logo.svg`: SVG recreation of the launcher icon — bezel ring
  (r30→r22, linear gradient `#C4E7FF`→`#4FA8DD`), aperture ring (r22→r16,
  `#16232D`), pupil (r16, radial `#2E6E96`→`#081520`), glint (center
  (49,47) r4.5, `#E8F6FF` @ .92), on rounded-square dark radial background
  (`#152430`→`#0B0F12`), 108-unit viewBox to mirror the vector drawables.
- Referenced from README via `<img src="docs/assets/logo.svg" width="128">`
  in a centered `<p align="center">` header.

## 2. LICENSE

- MIT, `LICENSE` at repo root, copyright `2026 muh`.
- README License section: MIT note + link, plus pointer to the in-app
  Settings → Open-source licenses screen for bundled third-party licenses
  (AUSBC Apache-2.0, libuvc BSD-3, libusb LGPL-2.1, etc.).

## 3. README structure (full rewrite of README.md)

Order:
1. Centered header: logo, H1 "USB Camera Viewer", tagline, badge row —
   Release workflow status, latest release, license MIT, API 26+, Kotlin.
   Badges via shields.io + GitHub badge URLs with `muh/usbcameraviewer`
   placeholder org/repo path and an HTML comment telling the owner to
   update after pushing to GitHub.
2. Features (concise bullets, current truth: preview, capture, gallery,
   settings, offline, dark UI).
3. Screenshots — 3-slot placeholder table pointing at
   `docs/assets/screenshots/{viewer,gallery,settings}.png`, HTML comment
   noting they're filled after the hardware pass.
4. Getting started — requirements (Android 8+, USB-C OTG host support,
   UVC-class camera), install (Release APK from GitHub Releases or
   `./build.sh`), first-run flow (plug in → allow → stream).
5. Usage — viewer controls, photo/video capture, gallery, settings.
6. Troubleshooting / FAQ — camera not detected (OTG, cable, UVC class),
   permission re-prompt, videos silent (by design), phone unsupported.
7. Build & development — build.sh usage, quality gates, signing setup,
   tag-release CI flow (folds the existing "Releases" section content in,
   keeps secrets table).
8. Architecture — mermaid flowchart (Compose UI → ViewModels →
   CameraController ← AusbcCameraController → libausbc; capture path
   CaptureStore → MediaStore; gallery MediaRepository → MediaStore) +
   short package map. Keeps AUSBC 3.2.7 pin explanation.
9. Hardware testing — checklist pointer, unrun-template caveat.
10. Known limitations — carried over unchanged in substance.
11. Contributing — brief: gates must pass, conventional commits.
12. License — per §2.

No content may contradict the code: video-only recording, preview-only
rotate/mirror, single device, libausbc 3.2.7, no INTERNET permission.

## Out of scope

- Actual screenshots (need hardware)
- GitHub org/repo rename automation, social preview image
