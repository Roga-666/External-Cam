# 16 KB page-size support — status and options

**Status (verified 2026-08-09):** every AUSBC 3.2.7 arm64 native library ships with 4 KB-aligned
ELF LOAD segments (`readelf -lW` on `libuvc.so`, `libusb100.so`, `libUVCCamera.so`,
`libjpeg-turbo1500.so`, `libnativelib.so` → `Align 0x1000`). This cannot be fixed by repacking
the APK — the alignment is baked into the ELF program headers at link time.

**Impact:** the app will not run on devices booted with 16 KB pages, and Google Play rejects
updates targeting Android 15+ without 16 KB support. Sideloading on today's 4 KB devices is
unaffected.

## Options

1. **Rebuild AUSBC natives from source** (preferred, ~1–2 days).
   The jiangdg/AndroidUSBCamera repo contains the `libuvc`/`libusb`/`libUVCCamera` NDK sources.
   Clone at tag 3.2.7, build `libuvc` + `libnative` modules with a current NDK (r28 defaults to
   16 KB `max-page-size`; older NDKs need `-Wl,-z,max-page-size=16384` in
   `APP_LDFLAGS`/CMake), and consume the rebuilt AARs locally (`libs/` flat-dir) instead of
   JitPack. Java/Kotlin layers stay on the published 3.2.7 artifacts, so app-side behavior is
   unchanged. Verify with `readelf -lW` (Align 0x4000) and boot on a 16 KB emulator image.

2. **Replace the UVC stack.** No maintained drop-in exists today; herohan/UVCAndroid and forks
   carry the same aging natives. Reassess before investing.

3. **Stay sideload-only.** Zero work; drop Play ambitions until option 1 or upstream fixes it.

The `ReceiverFlagContext` wrapper and the reflective `mUvcCamera` access must be re-verified
against any rebuilt or replaced artifacts (see memory/build notes).
