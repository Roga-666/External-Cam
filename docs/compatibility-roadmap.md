# External Cam compatibility roadmap

## Phase 1 — safe groundwork (this branch)

- Independent package/application ID and branding without migrating the source namespace
- USB diagnostic display including VID/PID and video-interface detection (including composite devices)
- Unit tests for the UVC device-descriptor heuristic
- Signed-only GitHub Releases and clear versionCode progression

## Phase 2 — camera opening and negotiation

- USB device selector for multiple connected cameras
- Record attempted format (MJPEG/YUYV), resolution, and error messages per camera
- Harden reconnect behavior after app resume, USB detaches, and broken hubs
- Test default format negotiation and recovery with physical cameras

## Phase 3 — native/modern Android compatibility

- Build and test native `libusb`, `libuvc`, and related binaries with 16 KB ELF alignment
- Verify on real hardware or a 16 KB-page emulator; do not assume packaging changes alone solve it
- Assess Camera2 external-camera path where Android exposes devices through Camera2, with direct UVC fallback
- Explore support for proprietary devices only when their protocol and redistribution terms permit

## Hardware validation matrix

For each tested camera, record Android version, USB VID/PID, interface classes, formats, supported resolutions, USB hub/power conditions, startup time, capture, recording, unplug/replug, and failures. At present these are planned checks, not claims of verified improvements.

**Privacy:** descriptors are displayed locally and are not transmitted. No INTERNET permission is requested.
