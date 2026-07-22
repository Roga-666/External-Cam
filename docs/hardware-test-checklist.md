# Hardware Test Checklist

Run on a physical phone with a UVC endoscope before every release.
Device: ____________  Camera: ____________  Date: ________

> Status: this checklist is a **template**, not a completed report. The full
> hardware pass has not yet been run (no physical device/USB camera was
> available during development); every box below is unchecked until someone
> runs this on real hardware. See `README.md` → Known limitations for the
> behaviors already known to differ from a "full" UVC pipeline (video-only
> recording, preview-only rotate/mirror).

## Connection
- [ ] Plug in while app closed → system offers to open app → preview
- [ ] Plug in while app open on NoDevice → preview without restart
- [ ] Deny USB permission → PermissionNeeded → Grant → preview
- [ ] Unplug during preview → NoDevice; replug → preview
- [ ] Phone without OTG (or OTG disabled) → clear unsupported message

## Streaming
- [ ] Resolution picker lists modes; switching changes preview
- [ ] Rotate cycles 0/90/180/270; mirror flips horizontally
- [ ] Screen stays on while streaming (setting on); dims when off

## Capture
- [ ] Photo → shutter feedback → appears in in-app gallery AND system gallery
- [ ] Video 10s+ → timer counts → file plays with correct duration
- [ ] Recorded video has no audio track (expected — AUSBC 3.2.7 records
      video-only; the Settings "record audio" switch has no effect on the
      file, see README)
- [ ] Rotate/mirror while streaming does NOT change the orientation of a
      photo/video captured afterwards (expected — preview-only transform,
      see README)
- [ ] Unplug mid-recording → partial file saved and playable
- [ ] Filename prefix from settings is used

## Gallery
- [ ] Grid thumbnails for photos and videos (play badge)
- [ ] Zoom/pan photo; share opens sheet; delete confirms and removes
- [ ] Delete last item → empty state

## Lifecycle
- [ ] Background app while streaming → returns and reconnects cleanly
- [ ] Rotate phone → no crash, preview continues
- [ ] Release build (minified): full pass of all above
