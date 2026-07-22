# Icon + Build Script + Release CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modern layered adaptive launcher icon (with Android 13 monochrome), a local `build.sh` for debug/release APKs, and a GitHub Actions workflow that builds and attaches a (optionally signed) release APK when a `v*` tag is pushed.

**Architecture:** Icon is pure VectorDrawable resources (gradients via `aapt:attr`), no raster assets. Signing is one shared plumbing in `app/build.gradle.kts` (keystore.properties → env vars → unsigned fallback) consumed identically by `build.sh` locally and the CI workflow.

**Tech Stack:** VectorDrawable adaptive icons, Bash, GitHub Actions (setup-java, setup-gradle, softprops/action-gh-release).

## Global Constraints

- Env for local gradle runs: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew ...` (system default Java 25 unsupported by Gradle 8.14)
- Icon safe zone: all foreground content inside circle center (54,54) radius 33 in the 108-unit viewport
- Palette: cyan-blues `#C4E7FF`/`#7FD0FF` family on dark `#0B0F12` family (existing app theme)
- Signing env var names exactly: `UCV_KEYSTORE_FILE`, `UCV_KEYSTORE_PASSWORD`, `UCV_KEY_ALIAS`, `UCV_KEY_PASSWORD`; properties file name exactly `keystore.properties` (repo root)
- CI secrets names exactly: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- Missing keystore must NEVER fail a build — release falls back to unsigned
- All quality gates stay green: `test detekt ktlintCheck lint`
- Commit after each task; trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

## File Structure

```
app/src/main/res/drawable/ic_launcher_foreground.xml   — replaced (layered lens)
app/src/main/res/drawable/ic_launcher_monochrome.xml   — new (themed-icon silhouette)
app/src/main/res/drawable/ic_launcher_background.xml   — new (radial gradient)
app/src/main/res/values/ic_launcher_background.xml     — deleted (flat color no longer used)
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml     — background ref + <monochrome>
app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml — same
app/build.gradle.kts                                   — signingConfigs.release
.gitignore                                             — keystore exclusions
build.sh                                               — new
.github/workflows/release.yml                          — new
README.md                                              — Releases section
```

---

### Task 1: Layered adaptive icon + monochrome

**Files:**
- Modify: `app/src/main/res/drawable/ic_launcher_foreground.xml` (replace content)
- Create: `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- Create: `app/src/main/res/drawable/ic_launcher_background.xml`
- Delete: `app/src/main/res/values/ic_launcher_background.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`

**Interfaces:**
- Consumes: nothing
- Produces: `@drawable/ic_launcher_background`, `@drawable/ic_launcher_monochrome` (referenced only from the two mipmap XMLs)

- [ ] **Step 1: Replace foreground vector**

`app/src/main/res/drawable/ic_launcher_foreground.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- Bezel ring: r30 -> r22, lit top-left -->
    <path
        android:fillType="evenOdd"
        android:pathData="M54,24a30,30 0 1,0 0,60a30,30 0 1,0 0,-60zM54,32a22,22 0 1,1 0,44a22,22 0 1,1 0,-44z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="34"
                android:startY="28"
                android:endX="76"
                android:endY="82"
                android:startColor="#C4E7FF"
                android:endColor="#4FA8DD" />
        </aapt:attr>
    </path>

    <!-- Aperture ring: r22 -> r16, dark separation -->
    <path
        android:fillColor="#16232D"
        android:fillType="evenOdd"
        android:pathData="M54,32a22,22 0 1,0 0,44a22,22 0 1,0 0,-44zM54,38a16,16 0 1,1 0,32a16,16 0 1,1 0,-32z" />

    <!-- Lens pupil: r16, deep radial -->
    <path android:pathData="M54,38a16,16 0 1,0 0,32a16,16 0 1,0 0,-32z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="49"
                android:centerY="45"
                android:gradientRadius="24"
                android:startColor="#2E6E96"
                android:endColor="#081520" />
        </aapt:attr>
    </path>

    <!-- Highlight glint -->
    <path
        android:fillAlpha="0.92"
        android:fillColor="#E8F6FF"
        android:pathData="M44,42a4.5,4.5 0 1,0 9,0a4.5,4.5 0 1,0 -9,0z" />
</vector>
```

Outermost radius 30 < 33 safe-zone radius — nothing clips under any launcher mask.

- [ ] **Step 2: Create monochrome layer**

`app/src/main/res/drawable/ic_launcher_monochrome.xml` (system tints it; color value irrelevant, shape only):
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Ring silhouette r30 -> r20 -->
    <path
        android:fillColor="#FFFFFF"
        android:fillType="evenOdd"
        android:pathData="M54,24a30,30 0 1,0 0,60a30,30 0 1,0 0,-60zM54,34a20,20 0 1,1 0,40a20,20 0 1,1 0,-40z" />
    <!-- Pupil dot r10 -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M54,44a10,10 0 1,0 0,20a10,10 0 1,0 0,-20z" />
</vector>
```

- [ ] **Step 3: Create gradient background, delete flat color**

`app/src/main/res/drawable/ic_launcher_background.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="54"
                android:centerY="46"
                android:gradientRadius="78"
                android:startColor="#152430"
                android:endColor="#0B0F12" />
        </aapt:attr>
    </path>
</vector>
```

Delete `app/src/main/res/values/ic_launcher_background.xml` (the `<color>` resource it held is replaced by the drawable above; same resource name in a different type would be confusing).

- [ ] **Step 4: Wire adaptive icons**

Both `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` AND `ic_launcher_round.xml` become:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

- [ ] **Step 5: Verify build + lint**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL, no new lint errors (unused-resource warnings must not appear — the old color resource was deleted, not orphaned).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: layered gradient launcher icon with Android 13 monochrome layer"
```

---

### Task 2: Release signing plumbing + build.sh

**Files:**
- Modify: `app/build.gradle.kts` (signingConfigs + release buildType wiring)
- Modify: `.gitignore`
- Create: `build.sh` (executable)

**Interfaces:**
- Consumes: nothing
- Produces: signing resolution order used verbatim by Task 3's CI — `keystore.properties` keys `storeFile`,`storePassword`,`keyAlias`,`keyPassword`; env fallbacks `UCV_KEYSTORE_FILE`,`UCV_KEYSTORE_PASSWORD`,`UCV_KEY_ALIAS`,`UCV_KEY_PASSWORD`; unsigned release when neither present. APK output paths: `app/build/outputs/apk/release/app-release.apk` (signed) or `app-release-unsigned.apk` (unsigned).

- [ ] **Step 1: Add signing config**

In `app/build.gradle.kts` — add at top (after plugins block):
```kotlin
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(property: String, envVar: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(envVar)
```

Inside `android { }`, before `buildTypes`:
```kotlin
    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "UCV_KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "UCV_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "UCV_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "UCV_KEY_PASSWORD")
            }
        }
    }
```

In `buildTypes.release`, add:
```kotlin
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
```

- [ ] **Step 2: Ignore keystores**

Append to `.gitignore`:
```
keystore.properties
*.jks
*.keystore
```

- [ ] **Step 3: Create build.sh**

`build.sh` (repo root):
```bash
#!/usr/bin/env bash
# Local APK build for USB Camera Viewer.
# Usage: ./build.sh [debug|release] [--install] [--gates]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

usage() {
    echo "Usage: $0 [debug|release] [--install] [--gates]" >&2
    echo "  debug|release  variant to build (default: debug)" >&2
    echo "  --install      adb install -r the resulting APK" >&2
    echo "  --gates        run test/detekt/ktlint/lint first" >&2
    exit 1
}

VARIANT="debug"
INSTALL=0
GATES=0
for arg in "$@"; do
    case "$arg" in
        debug|release) VARIANT="$arg" ;;
        --install) INSTALL=1 ;;
        --gates) GATES=1 ;;
        -h|--help) usage ;;
        *) echo "Unknown argument: $arg" >&2; usage ;;
    esac
done

# Gradle 8.14 supports JDK 17-23; system default may be newer.
jdk_ok() { [[ -x "$1/bin/java" ]] && "$1/bin/java" -version 2>&1 | head -1 | grep -qE 'version "(1[7-9]|2[0-3])[."]'; }
if ! jdk_ok "${JAVA_HOME:-/nonexistent}"; then
    for candidate in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk; do
        if jdk_ok "$candidate"; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
jdk_ok "${JAVA_HOME:-/nonexistent}" || { echo "ERROR: no JDK 17-23 found. Set JAVA_HOME." >&2; exit 1; }
echo "JAVA_HOME=$JAVA_HOME"

if [[ "$GATES" == 1 ]]; then
    ./gradlew test detekt ktlintCheck lint
fi

if [[ "$VARIANT" == "release" ]]; then
    ./gradlew :app:assembleRelease
    APK="app/build/outputs/apk/release/app-release.apk"
    [[ -f "$APK" ]] || APK="app/build/outputs/apk/release/app-release-unsigned.apk"
    if [[ -f keystore.properties || -n "${UCV_KEYSTORE_FILE:-}" ]]; then
        echo "Signing: release keystore"
    else
        echo "Signing: UNSIGNED (create keystore.properties or set UCV_KEYSTORE_FILE)"
    fi
else
    ./gradlew :app:assembleDebug
    APK="app/build/outputs/apk/debug/app-debug.apk"
fi

[[ -f "$APK" ]] || { echo "ERROR: expected APK not found: $APK" >&2; exit 1; }
echo "APK: $APK ($(du -h "$APK" | cut -f1))"

if [[ "$INSTALL" == 1 ]]; then
    adb install -r "$APK"
fi
```

Then: `chmod +x build.sh`

- [ ] **Step 4: Verify — unsigned release path**

Run: `./build.sh release`
Expected: script picks java-21, builds, prints `Signing: UNSIGNED (...)` and `APK: app/build/outputs/apk/release/app-release-unsigned.apk (...)`.

- [ ] **Step 5: Verify — signed path end-to-end with throwaway keystore**

```bash
keytool -genkeypair -keystore /tmp/ucv-test.jks -storepass testtest -keypass testtest \
  -alias test -dname CN=test -keyalg RSA -validity 1
printf 'storeFile=/tmp/ucv-test.jks\nstorePassword=testtest\nkeyAlias=test\nkeyPassword=testtest\n' > keystore.properties
./build.sh release
```
Expected: `Signing: release keystore`, APK path `app-release.apk` (no `-unsigned`).
Cleanup: `rm keystore.properties /tmp/ucv-test.jks` and confirm `git status` shows no keystore files (gitignore works).

- [ ] **Step 6: Verify debug + gates still green**

Run: `./build.sh debug` then `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew detekt ktlintCheck lint test`
Expected: debug APK printed; all gates green.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: release signing plumbing and local build.sh"
```

---

### Task 3: Release workflow + README

**Files:**
- Create: `.github/workflows/release.yml`
- Modify: `README.md` (new "Releases" section)

**Interfaces:**
- Consumes: signing env vars + APK paths from Task 2 (exact names in its Produces block)
- Produces: GitHub Release with `usb-camera-viewer-<tag>.apk` on `v*` tag push

- [ ] **Step 1: Create workflow**

`.github/workflows/release.yml`:
```yaml
name: Release

on:
  push:
    tags: ["v*"]

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - uses: gradle/actions/setup-gradle@v4

      - name: Quality gates
        run: ./gradlew test detekt ktlintCheck lint

      # Secrets cannot be used in `if:` directly; test via env indirection.
      - name: Decode signing keystore (optional)
        env:
          KEYSTORE_B64: ${{ secrets.KEYSTORE_B64 }}
        run: |
          if [ -n "$KEYSTORE_B64" ]; then
            echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/release.jks"
            echo "UCV_KEYSTORE_FILE=$RUNNER_TEMP/release.jks" >> "$GITHUB_ENV"
            echo "Keystore decoded - APK will be signed."
          else
            echo "No KEYSTORE_B64 secret - building UNSIGNED APK."
          fi

      - name: Build release APK
        env:
          UCV_KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          UCV_KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          UCV_KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew :app:assembleRelease

      - name: Collect APK
        run: |
          APK=app/build/outputs/apk/release/app-release.apk
          [ -f "$APK" ] || APK=app/build/outputs/apk/release/app-release-unsigned.apk
          cp "$APK" "usb-camera-viewer-${GITHUB_REF_NAME}.apk"
          ls -lh usb-camera-viewer-*.apk

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: usb-camera-viewer-*.apk
          generate_release_notes: true
```

- [ ] **Step 2: Validate workflow syntax**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('yaml ok')"`
(plus `actionlint .github/workflows/release.yml` if actionlint is installed — skip silently if not)
Expected: `yaml ok`, no actionlint errors.

- [ ] **Step 3: README Releases section**

Append to `README.md` (before "Known limitations"):
```markdown
## Releases

Local build:

    ./build.sh debug              # debug APK
    ./build.sh release            # release APK (signed if keystore configured)
    ./build.sh release --gates    # run test/detekt/ktlint/lint first
    ./build.sh debug --install    # build + adb install

Release signing (optional — builds are unsigned without it): create
`keystore.properties` at the repo root (git-ignored):

    storeFile=/path/to/release.jks
    storePassword=...
    keyAlias=...
    keyPassword=...

CI: pushing a tag matching `v*` (e.g. `git tag v1.0.0 && git push origin v1.0.0`)
runs quality gates and attaches `usb-camera-viewer-<tag>.apk` to a GitHub
Release. To get a *signed* CI APK, add repository secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_B64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without secrets the workflow still succeeds and attaches an unsigned APK.
A 512 px Play Store icon export is not generated (vector-only sources);
render `ic_launcher_foreground`/`_background` at 512 px when needed.
```

- [ ] **Step 4: Final verification**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew detekt ktlintCheck lint test :app:assembleRelease`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: tag-triggered release workflow and release docs"
```

## Plan Self-Review Notes

- Spec coverage: §1 → Task 1; §2 → Task 2 steps 1-2; §3 → Task 2 step 3; §4 → Task 3. Play-512 note → Task 3 README. No gaps.
- Signing names consistent across Task 2 Produces and Task 3 workflow (UCV_* env, secret names, APK paths).
- Workflow `if:` secrets pitfall handled via env indirection (documented inline).
