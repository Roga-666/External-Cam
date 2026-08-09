# Hardware Test Checklist

Run on a physical phone with a UVC endoscope before every release.
Device: ____________  Camera: ____________  Date: ________

> Status: partial pass 2026-07-22 on SM-A346B (connection, capture, resolution,
> button, A/V). Everything marked **(NEW 1.1)** below is unverified on hardware.
> Always `./build.sh debug` from clean + uninstall/install — incremental installs
> have served stale APKs (see memory/build notes).

## Connection
- [ ] Plug in while app closed → system offers to open app → preview
- [ ] Plug in while app open on NoDevice → preview without restart
- [ ] Deny USB permission → PermissionNeeded → Grant → preview
- [ ] Unplug during preview → NoDevice; replug → preview
- [ ] **(NEW 1.1)** Open Settings while streaming → back → preview resumes (no
      "Plug in your camera")
- [ ] **(NEW 1.1)** Open Gallery while streaming → back → preview resumes
- [ ] Phone without OTG (or OTG disabled) → clear unsupported message

## Streaming
- [ ] Resolution picker lists modes; switching changes preview
- [ ] **(NEW 1.1)** Resolution chip is disabled while recording
- [ ] Rotate cycles 0/90/180/270; mirror flips horizontally
- [ ] **(NEW 1.1)** At 90°/270° the picture keeps its aspect (no stretch/overflow)
- [ ] **(NEW 1.1)** Brightness/contrast sliders (tune icon) visibly change the
      image; values persist across app restart
- [ ] **(NEW 1.1)** Pinch-zoom + pan; double-tap toggles 2×; +/− buttons zoom;
      indicator shows level; resets on detach
- [ ] Screen stays on while streaming (setting on); dims when off

## Capture
- [ ] Photo → shutter feedback → appears in in-app gallery AND system gallery
- [ ] **(NEW 1.1)** Photo taken while rotated/mirrored/zoomed matches the preview
      (orientation and crop baked in; not upscaled/blurry)
- [ ] Video 10s+ → timer counts → file plays with correct duration
- [ ] Video with "Record microphone audio" ON has sound, correct duration
- [ ] **(NEW 1.1)** Video recorded while mirrored/zoomed shows mirror/zoom;
      rotation applied by the player (orientation metadata)
- [ ] **(NEW 1.1)** Unplug mid-recording → partial file SAVED (snackbar
      "Recording saved"), playable
- [ ] **(NEW 1.1)** Navigate to Gallery mid-recording → recording finalized and
      saved, viewer resumes on return
- [ ] Filename prefix from settings is used
- [ ] Physical camera button captures (photo mode) with flash + haptic

## Gallery
- [ ] Grid thumbnails for photos and videos (play badge)
- [ ] Zoom/pan photo; share opens sheet; delete confirms and removes
- [ ] Delete last item → empty state

## Lifecycle
- [ ] Background app while streaming → returns and reconnects cleanly
- [ ] Rotate phone → no crash, preview continues, letterbox recomputes
- [ ] **(NEW 1.1)** Predictive back gesture animates (Android 15+)
- [ ] **(NEW 1.1)** Second USB camera attached while streaming → session keeps
      first camera
- [ ] **(NEW 1.1)** Device language German/Spanish/French/Portuguese → UI translated
- [ ] Release build (minified): full pass of all above
