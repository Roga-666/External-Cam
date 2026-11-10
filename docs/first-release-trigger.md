# One-time signed External Cam first release

This file triggers the signed `v0.1.0` release workflow on the first merge to `main`. The branch-based release job is scoped to changes to **this file only**, so later ordinary source changes will not create releases unexpectedly.

Release gates: run unit tests and static checks, require all four signing secrets, build and verify the signed APK, and attach it to the `v0.1.0` GitHub Release. A failure must not publish an unsigned APK.

For future versions, increment `versionCode` and push a new release tag such as `v0.1.1`, reusing the same permanent signing key.

Device compatibility still requires actual USB camera testing, and the current native AUSBC library does not support 16 KB pages.
