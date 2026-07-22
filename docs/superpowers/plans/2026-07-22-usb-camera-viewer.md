# USB Camera Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Production-ready Android app that shows live video from a USB UVC endoscope, captures photos/videos to MediaStore, and browses them in an in-app gallery.

**Architecture:** Single-module MVVM. Jetpack Compose UI observes `StateFlow<CameraUiState>` from ViewModels. All UVC access goes through a `CameraController` interface; the real implementation wraps the AUSBC library, a `FakeCameraController` drives unit tests. Capture publishes via a `CaptureStore` interface backed by MediaStore.

**Tech Stack:** Kotlin 2.x, Jetpack Compose + Material 3, Hilt, Coroutines/Flow, AUSBC (libausbc) for UVC, DataStore Preferences, Coil, Navigation Compose, JUnit4 + Turbine + Robolectric.

## Global Constraints

- `applicationId`: `com.muh.usbcameraviewer`; app name "USB Camera Viewer"; developer "muh"
- minSdk 26, targetSdk 35, compileSdk 35
- Single Gradle module `:app`, Gradle KTS + version catalog
- NO `INTERNET` permission — app is fully offline
- Dark-only camera-first UI (no light theme)
- UVC library: `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3` via JitPack, used ONLY inside `camera/AusbcCameraController.kt`
- Capture collections: `Pictures/USB Camera Viewer/`, `Movies/USB Camera Viewer/`; filenames `<prefix>_yyyyMMdd_HHmmss.jpg|mp4`, default prefix `UCV`
- Versions in `libs.versions.toml` are floors — if dependency resolution fails, bump to latest stable, don't downgrade
- Conventional commit messages; commit at end of every task
- Tasks marked **HARDWARE CHECKPOINT** need a real UVC camera; run the manual steps or explicitly defer them to the final hardware pass. AUSBC API names were written from library docs/memory — if a symbol doesn't resolve, check the actual `libausbc` 3.3.3 sources (JitPack artifact) and adapt the call site, not the architecture.

## File Structure

```
app/src/main/java/com/muh/usbcameraviewer/
  App.kt                          — @HiltAndroidApp application
  MainActivity.kt                 — single activity, edge-to-edge, sets AppNav
  navigation/AppNav.kt            — NavHost: viewer / gallery / settings
  ui/theme/Theme.kt               — dark M3 color scheme + typography
  camera/CameraModels.kt          — Resolution, CaptureMode, CameraError, CameraUiState, CameraEvent
  camera/CameraController.kt      — interface (library-agnostic)
  camera/AusbcCameraController.kt — AUSBC-backed implementation (only file touching AUSBC)
  camera/CameraModule.kt          — Hilt binding
  capture/Filenames.kt            — timestamped names (pure)
  capture/CaptureStore.kt         — interface + MediaStoreCaptureStore + Hilt module
  viewer/ViewerViewModel.kt       — connection + capture state machine
  viewer/ViewerScreen.kt          — all viewer states + overlay controls
  gallery/MediaItem.kt            — data class
  gallery/MediaRepository.kt      — interface + MediaStoreMediaRepository + Hilt module
  gallery/GalleryViewModel.kt
  gallery/GalleryScreen.kt        — grid + full-screen pager + share/delete
  settings/AppSettings.kt         — data class + enums
  settings/SettingsRepository.kt  — DataStore-backed
  settings/SettingsModule.kt      — Hilt DataStore provider
  settings/SettingsViewModel.kt
  settings/SettingsScreen.kt
app/src/main/res/xml/device_filter.xml — UVC device match
app/src/test/java/com/muh/usbcameraviewer/
  MainDispatcherRule.kt
  camera/FakeCameraController.kt
  capture/FilenamesTest.kt
  settings/SettingsRepositoryTest.kt
  viewer/ViewerViewModelTest.kt
  gallery/GalleryViewModelTest.kt
  viewer/ViewerScreenTest.kt      — Robolectric Compose
docs/hardware-test-checklist.md
```

---

### Task 1: Project scaffold

**Files:**
- Create: `.gitignore`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/java/com/muh/usbcameraviewer/App.kt`, `app/src/main/java/com/muh/usbcameraviewer/MainActivity.kt`, `app/src/main/java/com/muh/usbcameraviewer/ui/theme/Theme.kt`

**Interfaces:**
- Consumes: nothing
- Produces: buildable app shell; `UcvTheme(content)` composable; Hilt wired (`App`, `@AndroidEntryPoint MainActivity`)

- [ ] **Step 1: Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.11.1` (or copy wrapper from any AGP 8.7-compatible project). Expected: `gradlew`, `gradle/wrapper/*` exist.

- [ ] **Step 2: Write root config files**

`.gitignore`:
```
*.iml
.gradle/
/local.properties
.idea/
.DS_Store
build/
/captures
.externalNativeBuild
.cxx
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "usbcameraviewer"
include(":app")
```

`build.gradle.kts` (root):
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

- [ ] **Step 3: Write version catalog**

`gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
ksp = "2.1.0-1.0.29"
hilt = "2.54"
hiltNavigationCompose = "1.2.0"
composeBom = "2025.01.00"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
navigationCompose = "2.8.5"
coroutines = "1.10.1"
datastore = "1.1.1"
coil = "2.7.0"
ausbc = "3.3.3"
coreKtx = "1.15.0"
junit = "4.13.2"
turbine = "1.2.0"
robolectric = "4.14.1"
androidxTestExtJunit = "1.2.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons = { group = "androidx.compose.material", name = "material-icons-extended" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
coil-video = { group = "io.coil-kt", name = "coil-video", version.ref = "coil" }
ausbc = { group = "com.github.jiangdongguo.AndroidUSBCamera", name = "libausbc", version.ref = "ausbc" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 4: Write app module build file**

`app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.muh.usbcameraviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.muh.usbcameraviewer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

`app/proguard-rules.pro` (empty for now, filled in Task 11):
```
# Rules added in release-polish task.
```

- [ ] **Step 5: Manifest, resources, Application, Activity, theme**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".App"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.UsbCameraViewer">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Note: no `<uses-permission android:name="android.permission.INTERNET"/>` — ever. Launcher icon uses the default generated mipmap until Task 11; copy `mipmap-*/ic_launcher*` from a fresh Android Studio template project, or temporarily set `android:icon="@android:drawable/sym_def_app_icon"` if building without template assets.

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">USB Camera Viewer</string>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.UsbCameraViewer" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">#0B0F12</item>
    </style>
</resources>
```

`App.kt`:
```kotlin
package com.muh.usbcameraviewer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application()
```

`MainActivity.kt`:
```kotlin
package com.muh.usbcameraviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import com.muh.usbcameraviewer.ui.theme.UcvTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UcvTheme {
                Text("USB Camera Viewer") // replaced by AppNav in Task 5
            }
        }
    }
}
```

`ui/theme/Theme.kt`:
```kotlin
package com.muh.usbcameraviewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD0FF),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004C6C),
    onPrimaryContainer = Color(0xFFC4E7FF),
    secondary = Color(0xFFB5C9D7),
    onSecondary = Color(0xFF20333D),
    background = Color(0xFF0B0F12),
    onBackground = Color(0xFFE1E6EA),
    surface = Color(0xFF11161A),
    onSurface = Color(0xFFE1E6EA),
    surfaceVariant = Color(0xFF1A2228),
    onSurfaceVariant = Color(0xFFBFC8CE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun UcvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
```

- [ ] **Step 6: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If AGP/Kotlin/KSP version conflict, bump per Global Constraints.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: project scaffold — Compose, Hilt, dark theme, buildable shell"
```

---

### Task 2: Camera domain — models, controller interface, fake, filenames

**Files:**
- Create: `app/src/main/java/com/muh/usbcameraviewer/camera/CameraModels.kt`
- Create: `app/src/main/java/com/muh/usbcameraviewer/camera/CameraController.kt`
- Create: `app/src/main/java/com/muh/usbcameraviewer/capture/Filenames.kt`
- Test: `app/src/test/java/com/muh/usbcameraviewer/camera/FakeCameraController.kt`
- Test: `app/src/test/java/com/muh/usbcameraviewer/capture/FilenamesTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces (used by every later task):
  - `Resolution(width: Int, height: Int)`
  - `CaptureMode { PHOTO, VIDEO }`
  - `CameraError` sealed: `NoUsbHostSupport`, `PermissionDenied`, `OpenFailed(reason: String)`, `UnsupportedDevice`, `RecordingFailed(reason: String)`
  - `CameraUiState` sealed: `NoDevice`, `PermissionNeeded(deviceName)`, `Connecting(deviceName)`, `Streaming(...)`, `Error(error)`
  - `CameraEvent` sealed: `Attached(deviceName)`, `Detached`, `PermissionGranted(deviceName)`, `PermissionDenied(deviceName)`, `Opened(deviceName, resolution, availableResolutions)`, `OpenFailed(reason)`
  - `interface CameraController` — full signature below
  - `Filenames.photo(prefix, instant, zone): String`, `Filenames.video(...): String`
  - `FakeCameraController` with `emit(event)` and recorded call lists

- [ ] **Step 1: Write failing FilenamesTest**

`app/src/test/java/com/muh/usbcameraviewer/capture/FilenamesTest.kt`:
```kotlin
package com.muh.usbcameraviewer.capture

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FilenamesTest {

    private val instant = Instant.parse("2026-07-22T14:05:09Z")
    private val zone = ZoneId.of("UTC")

    @Test
    fun `photo name uses prefix and timestamp`() {
        assertEquals("UCV_20260722_140509.jpg", Filenames.photo("UCV", instant, zone))
    }

    @Test
    fun `video name uses prefix and timestamp`() {
        assertEquals("UCV_20260722_140509.mp4", Filenames.video("UCV", instant, zone))
    }

    @Test
    fun `custom prefix respected`() {
        assertEquals("scope_20260722_140509.jpg", Filenames.photo("scope", instant, zone))
    }
}
```

- [ ] **Step 2: Run test, verify fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.capture.FilenamesTest"`
Expected: FAIL — `Unresolved reference: Filenames` (compile error counts as red).

- [ ] **Step 3: Implement Filenames**

`app/src/main/java/com/muh/usbcameraviewer/capture/Filenames.kt`:
```kotlin
package com.muh.usbcameraviewer.capture

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Filenames {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun photo(prefix: String, instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "${prefix}_${FORMAT.format(instant.atZone(zone))}.jpg"

    fun video(prefix: String, instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "${prefix}_${FORMAT.format(instant.atZone(zone))}.mp4"
}
```

- [ ] **Step 4: Run test, verify passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.capture.FilenamesTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Write camera models**

`app/src/main/java/com/muh/usbcameraviewer/camera/CameraModels.kt`:
```kotlin
package com.muh.usbcameraviewer.camera

data class Resolution(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x${height}"
}

enum class CaptureMode { PHOTO, VIDEO }

sealed interface CameraError {
    data object NoUsbHostSupport : CameraError
    data object PermissionDenied : CameraError
    data class OpenFailed(val reason: String) : CameraError
    data object UnsupportedDevice : CameraError
    data class RecordingFailed(val reason: String) : CameraError
}

sealed interface CameraUiState {
    data object NoDevice : CameraUiState
    data class PermissionNeeded(val deviceName: String) : CameraUiState
    data class Connecting(val deviceName: String) : CameraUiState
    data class Streaming(
        val deviceName: String,
        val resolution: Resolution,
        val availableResolutions: List<Resolution>,
        val rotationDegrees: Int = 0,
        val mirrored: Boolean = false,
        val captureMode: CaptureMode = CaptureMode.PHOTO,
        val isRecording: Boolean = false,
        val recordingSeconds: Long = 0,
        val lastCaptureUri: String? = null,
    ) : CameraUiState
    data class Error(val error: CameraError) : CameraUiState
}

sealed interface CameraEvent {
    data class Attached(val deviceName: String) : CameraEvent
    data object Detached : CameraEvent
    data class PermissionGranted(val deviceName: String) : CameraEvent
    data class PermissionDenied(val deviceName: String) : CameraEvent
    data class Opened(
        val deviceName: String,
        val resolution: Resolution,
        val availableResolutions: List<Resolution>,
    ) : CameraEvent
    data class OpenFailed(val reason: String) : CameraEvent
}
```

- [ ] **Step 6: Write CameraController interface**

`app/src/main/java/com/muh/usbcameraviewer/camera/CameraController.kt`:
```kotlin
package com.muh.usbcameraviewer.camera

import java.io.File
import kotlinx.coroutines.flow.SharedFlow

/**
 * Library-agnostic UVC camera facade. The only implementation talking to
 * AUSBC is AusbcCameraController; tests use FakeCameraController.
 *
 * `preview` is Any to keep this interface (and unit tests) free of Android
 * View types; the real implementation requires an AspectRatioTextureView.
 */
interface CameraController {
    val events: SharedFlow<CameraEvent>

    /** Register USB attach/detach monitoring. Idempotent. */
    fun startMonitoring()

    /** Unregister monitoring and release everything. */
    fun stopMonitoring()

    /** Re-trigger the system USB permission dialog for the pending device. */
    fun requestPermission()

    /** Open the pending device rendering into [preview]; null preferred = device default. */
    fun open(preview: Any, preferred: Resolution?)

    /** Close the camera stream, keep monitoring. */
    fun close()

    fun setResolution(resolution: Resolution)
    fun setRotation(degrees: Int)
    fun setMirror(mirrored: Boolean)

    /** Capture a JPEG into [target]. */
    suspend fun capturePhoto(target: File): Result<File>

    /** Start MP4 recording into [target]. */
    suspend fun startRecording(target: File, withAudio: Boolean, bitRate: Int): Result<Unit>

    /** Stop recording, return finalized file. Safe to call after detach. */
    suspend fun stopRecording(): Result<File>
}
```

- [ ] **Step 7: Write FakeCameraController (test fixture)**

`app/src/test/java/com/muh/usbcameraviewer/camera/FakeCameraController.kt`:
```kotlin
package com.muh.usbcameraviewer.camera

import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeCameraController : CameraController {

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
    override val events = _events

    val openCalls = mutableListOf<Pair<Any, Resolution?>>()
    val resolutionCalls = mutableListOf<Resolution>()
    val rotationCalls = mutableListOf<Int>()
    val mirrorCalls = mutableListOf<Boolean>()
    val recordingStarts = mutableListOf<Triple<File, Boolean, Int>>()
    var monitoring = false
    var permissionRequests = 0
    var closed = false

    var photoResult: (File) -> Result<File> = { Result.success(it.apply { writeText("jpeg") }) }
    var startRecordingResult: Result<Unit> = Result.success(Unit)
    var stopRecordingResult: Result<File>? = null // null = succeed with last started file

    fun emit(event: CameraEvent) {
        check(_events.tryEmit(event)) { "event buffer full" }
    }

    override fun startMonitoring() { monitoring = true }
    override fun stopMonitoring() { monitoring = false }
    override fun requestPermission() { permissionRequests++ }
    override fun open(preview: Any, preferred: Resolution?) { openCalls += preview to preferred }
    override fun close() { closed = true }
    override fun setResolution(resolution: Resolution) { resolutionCalls += resolution }
    override fun setRotation(degrees: Int) { rotationCalls += degrees }
    override fun setMirror(mirrored: Boolean) { mirrorCalls += mirrored }

    override suspend fun capturePhoto(target: File): Result<File> = photoResult(target)

    override suspend fun startRecording(target: File, withAudio: Boolean, bitRate: Int): Result<Unit> {
        recordingStarts += Triple(target, withAudio, bitRate)
        return startRecordingResult
    }

    override suspend fun stopRecording(): Result<File> =
        stopRecordingResult ?: Result.success(recordingStarts.last().first.apply { writeText("mp4") })
}
```

- [ ] **Step 8: Verify everything compiles and tests pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: camera domain models, CameraController interface, fake, filename generator"
```

---

### Task 3: Settings repository (DataStore)

**Files:**
- Create: `app/src/main/java/com/muh/usbcameraviewer/settings/AppSettings.kt`
- Create: `app/src/main/java/com/muh/usbcameraviewer/settings/SettingsRepository.kt`
- Create: `app/src/main/java/com/muh/usbcameraviewer/settings/SettingsModule.kt`
- Test: `app/src/test/java/com/muh/usbcameraviewer/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `Resolution` (Task 2)
- Produces:
  - `enum VideoQuality(val bitRate: Int) { LOW(2_000_000), MEDIUM(6_000_000), HIGH(12_000_000) }`
  - `enum ResolutionPolicy { REMEMBER_LAST, ALWAYS_ASK }`
  - `data class AppSettings(resolutionPolicy, lastResolution: Resolution?, videoQuality, recordAudio: Boolean, filenamePrefix: String, keepScreenOn: Boolean)`
  - `class SettingsRepository(dataStore)` — `val settings: Flow<AppSettings>`; suspend setters `setResolutionPolicy`, `setLastResolution`, `setVideoQuality`, `setRecordAudio`, `setFilenamePrefix`, `setKeepScreenOn`

- [ ] **Step 1: Write AppSettings model**

`app/src/main/java/com/muh/usbcameraviewer/settings/AppSettings.kt`:
```kotlin
package com.muh.usbcameraviewer.settings

import com.muh.usbcameraviewer.camera.Resolution

enum class VideoQuality(val bitRate: Int) {
    LOW(2_000_000),
    MEDIUM(6_000_000),
    HIGH(12_000_000),
}

enum class ResolutionPolicy { REMEMBER_LAST, ALWAYS_ASK }

data class AppSettings(
    val resolutionPolicy: ResolutionPolicy = ResolutionPolicy.REMEMBER_LAST,
    val lastResolution: Resolution? = null,
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val recordAudio: Boolean = false,
    val filenamePrefix: String = "UCV",
    val keepScreenOn: Boolean = true,
)
```

- [ ] **Step 2: Write failing repository test**

`app/src/test/java/com/muh/usbcameraviewer/settings/SettingsRepositoryTest.kt`:
```kotlin
package com.muh.usbcameraviewer.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.muh.usbcameraviewer.camera.Resolution
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher + Job())

    private fun repo(): SettingsRepository {
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, "test.preferences_pb") },
        )
        return SettingsRepository(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults returned when store empty`() = runTest(dispatcher) {
        val settings = repo().settings.first()
        assertEquals(AppSettings(), settings)
    }

    @Test
    fun `set and read back all fields`() = runTest(dispatcher) {
        val repo = repo()
        repo.setResolutionPolicy(ResolutionPolicy.ALWAYS_ASK)
        repo.setLastResolution(Resolution(1920, 1080))
        repo.setVideoQuality(VideoQuality.HIGH)
        repo.setRecordAudio(true)
        repo.setFilenamePrefix("scope")
        repo.setKeepScreenOn(false)

        val s = repo.settings.first()
        assertEquals(ResolutionPolicy.ALWAYS_ASK, s.resolutionPolicy)
        assertEquals(Resolution(1920, 1080), s.lastResolution)
        assertEquals(VideoQuality.HIGH, s.videoQuality)
        assertEquals(true, s.recordAudio)
        assertEquals("scope", s.filenamePrefix)
        assertEquals(false, s.keepScreenOn)
    }

    @Test
    fun `blank prefix falls back to default`() = runTest(dispatcher) {
        val repo = repo()
        repo.setFilenamePrefix("   ")
        assertEquals("UCV", repo.settings.first().filenamePrefix)
    }
}
```

- [ ] **Step 3: Run test, verify fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.settings.SettingsRepositoryTest"`
Expected: FAIL — `Unresolved reference: SettingsRepository`.

- [ ] **Step 4: Implement SettingsRepository**

`app/src/main/java/com/muh/usbcameraviewer/settings/SettingsRepository.kt`:
```kotlin
package com.muh.usbcameraviewer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.muh.usbcameraviewer.camera.Resolution
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val resolutionPolicy = stringPreferencesKey("resolution_policy")
        val lastResolutionW = intPreferencesKey("last_resolution_w")
        val lastResolutionH = intPreferencesKey("last_resolution_h")
        val videoQuality = stringPreferencesKey("video_quality")
        val recordAudio = booleanPreferencesKey("record_audio")
        val filenamePrefix = stringPreferencesKey("filename_prefix")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            resolutionPolicy = p[Keys.resolutionPolicy]
                ?.let { runCatching { ResolutionPolicy.valueOf(it) }.getOrNull() }
                ?: defaults.resolutionPolicy,
            lastResolution = p[Keys.lastResolutionW]?.let { w ->
                p[Keys.lastResolutionH]?.let { h -> Resolution(w, h) }
            },
            videoQuality = p[Keys.videoQuality]
                ?.let { runCatching { VideoQuality.valueOf(it) }.getOrNull() }
                ?: defaults.videoQuality,
            recordAudio = p[Keys.recordAudio] ?: defaults.recordAudio,
            filenamePrefix = p[Keys.filenamePrefix]?.takeIf { it.isNotBlank() }
                ?: defaults.filenamePrefix,
            keepScreenOn = p[Keys.keepScreenOn] ?: defaults.keepScreenOn,
        )
    }

    suspend fun setResolutionPolicy(value: ResolutionPolicy) =
        dataStore.edit { it[Keys.resolutionPolicy] = value.name }

    suspend fun setLastResolution(value: Resolution) = dataStore.edit {
        it[Keys.lastResolutionW] = value.width
        it[Keys.lastResolutionH] = value.height
    }

    suspend fun setVideoQuality(value: VideoQuality) =
        dataStore.edit { it[Keys.videoQuality] = value.name }

    suspend fun setRecordAudio(value: Boolean) =
        dataStore.edit { it[Keys.recordAudio] = value }

    suspend fun setFilenamePrefix(value: String) =
        dataStore.edit { it[Keys.filenamePrefix] = value.trim() }

    suspend fun setKeepScreenOn(value: Boolean) =
        dataStore.edit { it[Keys.keepScreenOn] = value }
}
```

Note the blank-prefix test passes because the read side falls back on blank, and the write side trims.

`app/src/main/java/com/muh/usbcameraviewer/settings/SettingsModule.kt`:
```kotlin
package com.muh.usbcameraviewer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}
```

- [ ] **Step 5: Run test, verify passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.settings.SettingsRepositoryTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: DataStore-backed settings repository with defaults and validation"
```

---

### Task 4: ViewerViewModel — connection state machine

**Files:**
- Create: `app/src/main/java/com/muh/usbcameraviewer/viewer/ViewerViewModel.kt`
- Test: `app/src/test/java/com/muh/usbcameraviewer/MainDispatcherRule.kt`
- Test: `app/src/test/java/com/muh/usbcameraviewer/viewer/ViewerViewModelTest.kt`

**Interfaces:**
- Consumes: `CameraController`, `CameraEvent`, `CameraUiState`, `FakeCameraController` (Task 2); `SettingsRepository` (Task 3)
- Produces (Task 5 UI and Task 7 build on these):
  - `ViewerViewModel(controller, settingsRepository)` with `val state: StateFlow<CameraUiState>`
  - `fun onPreviewReady(preview: Any)`, `onPermissionRetry()`, `onRetryAfterError()`, `onSelectResolution(Resolution)`, `onRotate()`, `onToggleMirror()`
  - Capture methods (`onShutter`, `onToggleCaptureMode`) are added in Task 7 — NOT here (YAGNI).

- [ ] **Step 1: Write MainDispatcherRule**

`app/src/test/java/com/muh/usbcameraviewer/MainDispatcherRule.kt`:
```kotlin
package com.muh.usbcameraviewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

- [ ] **Step 2: Write failing ViewerViewModel tests**

`app/src/test/java/com/muh/usbcameraviewer/viewer/ViewerViewModelTest.kt`:
```kotlin
package com.muh.usbcameraviewer.viewer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.muh.usbcameraviewer.MainDispatcherRule
import com.muh.usbcameraviewer.camera.CameraEvent
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.FakeCameraController
import com.muh.usbcameraviewer.camera.Resolution
import com.muh.usbcameraviewer.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ViewerViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeCameraController()
    private val res720 = Resolution(1280, 720)
    private val res1080 = Resolution(1920, 1080)

    private fun settingsRepo(): SettingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(mainDispatcher.dispatcher + Job()),
            produceFile = { File(tmp.root, "test.preferences_pb") },
        ),
    )

    private fun vm() = ViewerViewModel(fake, settingsRepo())

    @Test
    fun `starts monitoring and begins in NoDevice`() = runTest {
        val vm = vm()
        assertTrue(fake.monitoring)
        assertEquals(CameraUiState.NoDevice, vm.state.value)
    }

    @Test
    fun `attach grant open reaches Streaming`() = runTest {
        val vm = vm()
        vm.state.test {
            assertEquals(CameraUiState.NoDevice, awaitItem())
            fake.emit(CameraEvent.Attached("Endoscope"))
            assertEquals(CameraUiState.Connecting("Endoscope"), awaitItem())
            fake.emit(CameraEvent.PermissionGranted("Endoscope"))
            vm.onPreviewReady(preview = "texture-view")
            fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720, res1080)))
            val streaming = awaitItem() as CameraUiState.Streaming
            assertEquals(res720, streaming.resolution)
            assertEquals(listOf(res720, res1080), streaming.availableResolutions)
        }
        assertEquals(1, fake.openCalls.size)
    }

    @Test
    fun `permission denied shows PermissionNeeded and retry re-requests`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionDenied("Endoscope"))
        assertEquals(CameraUiState.PermissionNeeded("Endoscope"), vm.state.value)
        vm.onPermissionRetry()
        assertEquals(1, fake.permissionRequests)
    }

    @Test
    fun `detach from any state returns to NoDevice`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
        fake.emit(CameraEvent.Detached)
        assertEquals(CameraUiState.NoDevice, vm.state.value)
    }

    @Test
    fun `open failure surfaces Error and retry closes then reopens permission flow`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.OpenFailed("libusb error"))
        val error = vm.state.value as CameraUiState.Error
        vm.onRetryAfterError()
        assertTrue(fake.closed)
        assertEquals(1, fake.permissionRequests)
    }

    @Test
    fun `selecting resolution forwards to controller and persists`() = runTest {
        val settings = settingsRepo()
        val vm = ViewerViewModel(fake, settings)
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720, res1080)))
        vm.onSelectResolution(res1080)
        assertEquals(listOf(res1080), fake.resolutionCalls)
    }

    @Test
    fun `preferred resolution from settings passed to open`() = runTest {
        val settings = settingsRepo()
        settings.setLastResolution(res1080)
        val vm = ViewerViewModel(fake, settings)
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        vm.onPreviewReady(preview = "texture-view")
        assertEquals(res1080, fake.openCalls.single().second)
    }

    @Test
    fun `rotate cycles 0-90-180-270-0 and mirror toggles`() = runTest {
        val vm = vm()
        fake.emit(CameraEvent.Attached("Endoscope"))
        fake.emit(CameraEvent.PermissionGranted("Endoscope"))
        fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
        vm.onRotate()
        vm.onRotate()
        assertEquals(listOf(90, 180), fake.rotationCalls)
        vm.onToggleMirror()
        assertEquals(listOf(true), fake.mirrorCalls)
        val s = vm.state.value as CameraUiState.Streaming
        assertEquals(180, s.rotationDegrees)
        assertTrue(s.mirrored)
    }
}
```

- [ ] **Step 3: Run tests, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.viewer.ViewerViewModelTest"`
Expected: FAIL — `Unresolved reference: ViewerViewModel`.

- [ ] **Step 4: Implement ViewerViewModel**

`app/src/main/java/com/muh/usbcameraviewer/viewer/ViewerViewModel.kt`:
```kotlin
package com.muh.usbcameraviewer.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muh.usbcameraviewer.camera.CameraController
import com.muh.usbcameraviewer.camera.CameraError
import com.muh.usbcameraviewer.camera.CameraEvent
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.Resolution
import com.muh.usbcameraviewer.settings.ResolutionPolicy
import com.muh.usbcameraviewer.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val controller: CameraController,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<CameraUiState>(CameraUiState.NoDevice)
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { controller.events.collect(::onEvent) }
        controller.startMonitoring()
    }

    private fun onEvent(event: CameraEvent) {
        when (event) {
            is CameraEvent.Attached -> _state.value = CameraUiState.Connecting(event.deviceName)
            is CameraEvent.PermissionGranted -> _state.value = CameraUiState.Connecting(event.deviceName)
            is CameraEvent.PermissionDenied -> _state.value = CameraUiState.PermissionNeeded(event.deviceName)
            is CameraEvent.Opened -> _state.value = CameraUiState.Streaming(
                deviceName = event.deviceName,
                resolution = event.resolution,
                availableResolutions = event.availableResolutions,
            )
            is CameraEvent.OpenFailed -> _state.value =
                CameraUiState.Error(CameraError.OpenFailed(event.reason))
            CameraEvent.Detached -> _state.value = CameraUiState.NoDevice
        }
    }

    /** Called by the AndroidView factory once the preview view exists. */
    fun onPreviewReady(preview: Any) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val preferred = settings.lastResolution
                .takeIf { settings.resolutionPolicy == ResolutionPolicy.REMEMBER_LAST }
            controller.open(preview, preferred)
        }
    }

    fun onPermissionRetry() = controller.requestPermission()

    fun onRetryAfterError() {
        controller.close()
        _state.value = CameraUiState.NoDevice
        controller.requestPermission()
    }

    fun onSelectResolution(resolution: Resolution) {
        controller.setResolution(resolution)
        viewModelScope.launch { settingsRepository.setLastResolution(resolution) }
    }

    fun onRotate() {
        _state.update { s ->
            if (s !is CameraUiState.Streaming) return
            val degrees = (s.rotationDegrees + 90) % 360
            controller.setRotation(degrees)
            s.copy(rotationDegrees = degrees)
        }
    }

    fun onToggleMirror() {
        _state.update { s ->
            if (s !is CameraUiState.Streaming) return
            val mirrored = !s.mirrored
            controller.setMirror(mirrored)
            s.copy(mirrored = mirrored)
        }
    }

    override fun onCleared() {
        controller.close()
        controller.stopMonitoring()
    }
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.viewer.ViewerViewModelTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: ViewerViewModel connection state machine with full unit coverage"
```

---

### Task 5: Viewer UI + navigation

**Files:**
- Create: `app/src/main/java/com/muh/usbcameraviewer/viewer/ViewerScreen.kt`
- Create: `app/src/main/java/com/muh/usbcameraviewer/navigation/AppNav.kt`
- Modify: `app/src/main/java/com/muh/usbcameraviewer/MainActivity.kt` (replace placeholder Text with AppNav)
- Create: `app/src/main/res/values/strings.xml` additions
- Test: `app/src/test/java/com/muh/usbcameraviewer/viewer/ViewerScreenTest.kt`

**Interfaces:**
- Consumes: `ViewerViewModel` (Task 4), `CameraUiState` (Task 2), `UcvTheme` (Task 1)
- Produces:
  - `ViewerScreen(onOpenGallery: () -> Unit, onOpenSettings: () -> Unit, viewModel: ViewerViewModel = hiltViewModel())`
  - Stateless `ViewerContent(state, callbacks...)` — testable without Hilt
  - `AppNav()` — NavHost with routes `"viewer"`, `"gallery"`, `"settings"` (gallery/settings are placeholder Text screens until Tasks 9/10)
  - `PreviewSurfaceFactory` typealias `(android.content.Context) -> android.view.View` — Task 6 provides the real AUSBC view factory via Hilt; until then a plain `TextureView` factory is bound. Streaming UI hands the created view to `onPreviewReady`.

Compose test note: Robolectric compose tests run with `@RunWith(AndroidJUnit4::class)` + `@Config(sdk = [34])` and `createComposeRule()`; they test `ViewerContent` (stateless), never the Hilt ViewModel.

- [ ] **Step 1: Add UI strings**

Merge into `app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">USB Camera Viewer</string>
    <string name="no_device_title">Plug in your camera</string>
    <string name="no_device_body">Connect a USB endoscope with a USB-C (OTG) adapter. The app starts automatically when a camera is detected.</string>
    <string name="permission_needed_title">USB permission needed</string>
    <string name="permission_needed_body">Allow access to %1$s to see its video.</string>
    <string name="grant_permission">Grant permission</string>
    <string name="connecting_to">Connecting to %1$s…</string>
    <string name="error_open_failed">Could not open the camera.</string>
    <string name="error_no_usb_host">This phone does not support USB OTG host mode.</string>
    <string name="error_unsupported_device">This USB device is not a supported UVC camera.</string>
    <string name="error_recording_failed">Recording failed: %1$s</string>
    <string name="retry">Retry</string>
    <string name="cd_shutter">Take photo</string>
    <string name="cd_record_start">Start recording</string>
    <string name="cd_record_stop">Stop recording</string>
    <string name="cd_toggle_mode">Switch photo/video mode</string>
    <string name="cd_rotate">Rotate preview</string>
    <string name="cd_mirror">Mirror preview</string>
    <string name="cd_open_gallery">Open gallery</string>
    <string name="cd_open_settings">Open settings</string>
    <string name="cd_pick_resolution">Choose resolution</string>
</resources>
```

- [ ] **Step 2: Write failing Compose tests**

`app/src/test/java/com/muh/usbcameraviewer/viewer/ViewerScreenTest.kt`:
```kotlin
package com.muh.usbcameraviewer.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muh.usbcameraviewer.camera.CameraError
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.Resolution
import com.muh.usbcameraviewer.ui.theme.UcvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ViewerScreenTest {

    @get:Rule val compose = createComposeRule()

    private val streaming = CameraUiState.Streaming(
        deviceName = "Endoscope",
        resolution = Resolution(1280, 720),
        availableResolutions = listOf(Resolution(1280, 720), Resolution(1920, 1080)),
    )

    private fun setContent(state: CameraUiState, callbacks: ViewerCallbacks = ViewerCallbacks()) {
        compose.setContent { UcvTheme { ViewerContent(state = state, callbacks = callbacks) } }
    }

    @Test
    fun noDevice_showsPlugInHelp() {
        setContent(CameraUiState.NoDevice)
        compose.onNodeWithText("Plug in your camera").assertIsDisplayed()
    }

    @Test
    fun permissionNeeded_grantButtonInvokesRetry() {
        var retried = false
        setContent(
            CameraUiState.PermissionNeeded("Endoscope"),
            ViewerCallbacks(onPermissionRetry = { retried = true }),
        )
        compose.onNodeWithText("Grant permission").performClick()
        assertTrue(retried)
    }

    @Test
    fun streaming_showsShutterAndControls() {
        setContent(streaming)
        compose.onNodeWithContentDescription("Take photo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rotate preview").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open gallery").assertIsDisplayed()
        compose.onNodeWithContentDescription("Choose resolution").assertIsDisplayed()
    }

    @Test
    fun error_showsMessageAndRetry() {
        var retried = false
        setContent(
            CameraUiState.Error(CameraError.OpenFailed("x")),
            ViewerCallbacks(onRetryAfterError = { retried = true }),
        )
        compose.onNodeWithText("Could not open the camera.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}
```

- [ ] **Step 3: Run tests, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.viewer.ViewerScreenTest"`
Expected: FAIL — `Unresolved reference: ViewerContent` / `ViewerCallbacks`.

- [ ] **Step 4: Implement ViewerScreen**

`app/src/main/java/com/muh/usbcameraviewer/viewer/ViewerScreen.kt`:
```kotlin
package com.muh.usbcameraviewer.viewer

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muh.usbcameraviewer.R
import com.muh.usbcameraviewer.camera.CameraError
import com.muh.usbcameraviewer.camera.CameraUiState
import com.muh.usbcameraviewer.camera.Resolution

data class ViewerCallbacks(
    val onPreviewReady: (Any) -> Unit = {},
    val onPermissionRetry: () -> Unit = {},
    val onRetryAfterError: () -> Unit = {},
    val onSelectResolution: (Resolution) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onToggleMirror: () -> Unit = {},
    val onShutter: () -> Unit = {},          // wired in Task 7
    val onToggleCaptureMode: () -> Unit = {}, // wired in Task 7
    val onOpenGallery: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val previewFactory: (android.content.Context) -> android.view.View = { TextureView(it) },
)

@Composable
fun ViewerScreen(
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ViewerContent(
        state = state,
        callbacks = ViewerCallbacks(
            onPreviewReady = viewModel::onPreviewReady,
            onPermissionRetry = viewModel::onPermissionRetry,
            onRetryAfterError = viewModel::onRetryAfterError,
            onSelectResolution = viewModel::onSelectResolution,
            onRotate = viewModel::onRotate,
            onToggleMirror = viewModel::onToggleMirror,
            onOpenGallery = onOpenGallery,
            onOpenSettings = onOpenSettings,
            previewFactory = viewModel.previewFactory, // added in Task 6; see note below
        ),
    )
}

@Composable
fun ViewerContent(state: CameraUiState, callbacks: ViewerCallbacks) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (state) {
            CameraUiState.NoDevice -> CenteredMessage(
                icon = { Icon(Icons.Filled.Usb, contentDescription = null, Modifier.size(64.dp)) },
                title = stringResource(R.string.no_device_title),
                body = stringResource(R.string.no_device_body),
            )
            is CameraUiState.PermissionNeeded -> CenteredMessage(
                title = stringResource(R.string.permission_needed_title),
                body = stringResource(R.string.permission_needed_body, state.deviceName),
                action = {
                    Button(onClick = callbacks.onPermissionRetry) {
                        Text(stringResource(R.string.grant_permission))
                    }
                },
            )
            is CameraUiState.Connecting -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.connecting_to, state.deviceName),
                    Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                // Preview surface must exist before open(); create it now.
                PreviewSurface(callbacks)
            }
            is CameraUiState.Streaming -> StreamingContent(state, callbacks)
            is CameraUiState.Error -> CenteredMessage(
                title = state.error.message(),
                body = "",
                action = {
                    Button(onClick = callbacks.onRetryAfterError) {
                        Text(stringResource(R.string.retry))
                    }
                },
            )
        }
    }
}

@Composable
private fun CameraError.message(): String = when (this) {
    CameraError.NoUsbHostSupport -> stringResource(R.string.error_no_usb_host)
    CameraError.PermissionDenied -> stringResource(R.string.permission_needed_title)
    is CameraError.OpenFailed -> stringResource(R.string.error_open_failed)
    CameraError.UnsupportedDevice -> stringResource(R.string.error_unsupported_device)
    is CameraError.RecordingFailed -> stringResource(R.string.error_recording_failed, reason)
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    icon: @Composable () -> Unit = {},
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        if (body.isNotEmpty()) {
            Text(
                body,
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.padding(top = 24.dp)) { action() }
    }
}

@Composable
private fun PreviewSurface(callbacks: ViewerCallbacks) {
    AndroidView(
        factory = { context ->
            callbacks.previewFactory(context).also(callbacks.onPreviewReady)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun StreamingContent(state: CameraUiState.Streaming, callbacks: ViewerCallbacks) {
    Box(Modifier.fillMaxSize()) {
        PreviewSurface(callbacks)

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var resMenu by remember { mutableStateOf(false) }
                Box {
                    AssistChip(
                        onClick = { resMenu = true },
                        label = { Text(state.resolution.toString()) },
                        modifier = Modifier.semantics {
                            contentDescription = "Choose resolution"
                        },
                    )
                    DropdownMenu(expanded = resMenu, onDismissRequest = { resMenu = false }) {
                        state.availableResolutions.forEach { res ->
                            DropdownMenuItem(
                                text = { Text(res.toString()) },
                                onClick = {
                                    resMenu = false
                                    callbacks.onSelectResolution(res)
                                },
                            )
                        }
                    }
                }
                Row {
                    IconButton(onClick = callbacks.onRotate) {
                        Icon(
                            Icons.Filled.Rotate90DegreesCw,
                            contentDescription = stringResource(R.string.cd_rotate),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = callbacks.onToggleMirror) {
                        Icon(
                            Icons.Filled.Flip,
                            contentDescription = stringResource(R.string.cd_mirror),
                            tint = if (state.mirrored) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    IconButton(onClick = callbacks.onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                            tint = Color.White,
                        )
                    }
                }
            }

            // Bottom bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, start = 32.dp, end = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = callbacks.onOpenGallery) {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = stringResource(R.string.cd_open_gallery),
                        tint = Color.White,
                    )
                }
                FilledIconButton(
                    onClick = callbacks.onShutter,
                    modifier = Modifier
                        .size(72.dp)
                        .semantics { contentDescription = "Take photo" },
                    shape = CircleShape,
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                }
                IconButton(onClick = callbacks.onToggleCaptureMode) {
                    Icon(
                        Icons.Filled.Flip, // replaced with Videocam/PhotoCamera toggle in Task 7
                        contentDescription = stringResource(R.string.cd_toggle_mode),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
```

Task 6 note referenced above: until Task 6 adds `viewModel.previewFactory`, keep the `ViewerCallbacks` default (`TextureView(it)`) and OMIT the `previewFactory = viewModel.previewFactory` line from `ViewerScreen` — add it in Task 6.

- [ ] **Step 5: Implement AppNav, update MainActivity**

`app/src/main/java/com/muh/usbcameraviewer/navigation/AppNav.kt`:
```kotlin
package com.muh.usbcameraviewer.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muh.usbcameraviewer.viewer.ViewerScreen

object Routes {
    const val VIEWER = "viewer"
    const val GALLERY = "gallery"
    const val SETTINGS = "settings"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.VIEWER) {
        composable(Routes.VIEWER) {
            ViewerScreen(
                onOpenGallery = { nav.navigate(Routes.GALLERY) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.GALLERY) { Text("Gallery — Task 9") }
        composable(Routes.SETTINGS) { Text("Settings — Task 10") }
    }
}
```

In `MainActivity.kt` replace `Text("USB Camera Viewer")` with `AppNav()` (import `com.muh.usbcameraviewer.navigation.AppNav`).

- [ ] **Step 6: Run all tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (Filenames, Settings, ViewerViewModel, ViewerScreen).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: viewer UI with all connection states, overlay controls, navigation"
```

---

### Task 6: AUSBC camera controller + USB manifest wiring — HARDWARE CHECKPOINT

**Files:**
- Create: `app/src/main/java/com/muh/usbcameraviewer/camera/AusbcCameraController.kt`
- Create: `app/src/main/java/com/muh/usbcameraviewer/camera/CameraModule.kt`
- Create: `app/src/main/res/xml/device_filter.xml`
- Modify: `app/src/main/AndroidManifest.xml` (USB intent filter, features)
- Modify: `app/build.gradle.kts` (add AUSBC dependency)
- Modify: `app/src/main/java/com/muh/usbcameraviewer/viewer/ViewerViewModel.kt` (expose `previewFactory`)
- Modify: `app/src/main/java/com/muh/usbcameraviewer/viewer/ViewerScreen.kt` (use `viewModel.previewFactory`)

**Interfaces:**
- Consumes: `CameraController`, `CameraEvent`, `Resolution` (Task 2)
- Produces:
  - `AusbcCameraController` — the ONLY class importing `com.jiangdg.*`
  - Hilt binding `CameraController -> AusbcCameraController`
  - `PreviewFactory` (`@Singleton class` with `fun create(context: Context): View` returning `AspectRatioTextureView`), injected into `ViewerViewModel` as `val previewFactory: (Context) -> View`

AUSBC API disclaimer (Global Constraints): if any `com.jiangdg` symbol below doesn't resolve, open the JitPack sources for `libausbc:3.3.3` and adapt names — the architecture (events flow, single wrapper class) must not change.

- [ ] **Step 1: Add dependency**

In `app/build.gradle.kts` dependencies: `implementation(libs.ausbc)`.
Run: `./gradlew :app:assembleDebug` — expected `BUILD SUCCESSFUL` (JitPack fetch may take a minute).

- [ ] **Step 2: USB manifest wiring**

`app/src/main/res/xml/device_filter.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- UVC: device-level video class -->
    <usb-device class="14" />
    <!-- Composite devices: Miscellaneous + Interface Association Descriptor -->
    <usb-device class="239" subclass="2" protocol="1" />
</resources>
```

Manifest changes in `app/src/main/AndroidManifest.xml`:
```xml
<uses-feature android:name="android.hardware.usb.host" android:required="false" />

<!-- inside <activity android:name=".MainActivity"> after the LAUNCHER filter -->
<intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
</intent-filter>
<meta-data
    android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
    android:resource="@xml/device_filter" />
```

Also add `android:launchMode="singleTask"` to the activity so replug doesn't stack duplicate activities.

- [ ] **Step 3: Implement AusbcCameraController**

`app/src/main/java/com/muh/usbcameraviewer/camera/AusbcCameraController.kt`:
```kotlin
package com.muh.usbcameraviewer.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.view.View
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class AusbcCameraController @Inject constructor(
    @ApplicationContext private val context: Context,
) : CameraController {

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CameraEvent> = _events

    private var client: MultiCameraClient? = null
    private var camera: CameraUVC? = null
    private var pendingDevice: UsbDevice? = null
    private var ctrlBlock: USBMonitor.UsbControlBlock? = null
    private var recordingFile: File? = null

    private fun UsbDevice.displayName(): String =
        productName?.takeIf { it.isNotBlank() } ?: "USB Camera"

    override fun startMonitoring() {
        if (client != null) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            _events.tryEmit(CameraEvent.OpenFailed("no-usb-host"))
            return
        }
        client = MultiCameraClient(context, object : MultiCameraClient.IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                pendingDevice = device
                _events.tryEmit(CameraEvent.Attached(device.displayName()))
                client?.requestPermission(device)
            }

            override fun onDetachDec(device: UsbDevice?) {
                if (device == pendingDevice) {
                    teardownCamera()
                    pendingDevice = null
                    _events.tryEmit(CameraEvent.Detached)
                }
            }

            override fun onConnectDev(device: UsbDevice?, block: USBMonitor.UsbControlBlock?) {
                device ?: return
                ctrlBlock = block
                _events.tryEmit(CameraEvent.PermissionGranted(device.displayName()))
            }

            override fun onDisConnectDec(device: UsbDevice?, block: USBMonitor.UsbControlBlock?) {
                teardownCamera()
                _events.tryEmit(CameraEvent.Detached)
            }

            override fun onCancelDev(device: UsbDevice?) {
                device ?: return
                _events.tryEmit(CameraEvent.PermissionDenied(device.displayName()))
            }
        })
        client?.register()
    }

    override fun stopMonitoring() {
        teardownCamera()
        client?.unRegister()
        client?.destroy()
        client = null
    }

    override fun requestPermission() {
        val device = pendingDevice ?: return
        client?.requestPermission(device)
    }

    override fun open(preview: Any, preferred: Resolution?) {
        val device = pendingDevice
        val block = ctrlBlock
        if (device == null || block == null) {
            _events.tryEmit(CameraEvent.OpenFailed("no device or control block"))
            return
        }
        val view = preview as? AspectRatioTextureView
            ?: run {
                _events.tryEmit(CameraEvent.OpenFailed("preview is not AspectRatioTextureView"))
                return
            }
        val cam = CameraUVC(context, device)
        cam.setUsbControlBlock(block)
        cam.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(
                self: MultiCameraClient.ICamera,
                code: ICameraStateCallBack.State,
                msg: String?,
            ) {
                when (code) {
                    ICameraStateCallBack.State.OPENED -> {
                        val sizes = cam.getAllPreviewSizes()
                            .map { Resolution(it.width, it.height) }
                            .distinct()
                            .sortedByDescending { it.width * it.height }
                        val current = cam.getCameraRequest()
                            ?.let { Resolution(it.previewWidth, it.previewHeight) }
                            ?: sizes.firstOrNull()
                            ?: Resolution(0, 0)
                        _events.tryEmit(
                            CameraEvent.Opened(device.displayName(), current, sizes),
                        )
                    }
                    ICameraStateCallBack.State.CLOSED -> Unit
                    ICameraStateCallBack.State.ERROR ->
                        _events.tryEmit(CameraEvent.OpenFailed(msg ?: "camera error"))
                }
            }
        })
        val request = CameraRequest.Builder()
            .setPreviewWidth(preferred?.width ?: 1280)
            .setPreviewHeight(preferred?.height ?: 720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setAspectRatioShow(true)
            .setRawPreviewData(false)
            .create()
        cam.openCamera(view, request)
        camera = cam
    }

    override fun close() = teardownCamera()

    private fun teardownCamera() {
        runCatching { camera?.captureVideoStop() }
        runCatching { camera?.closeCamera() }
        camera = null
    }

    override fun setResolution(resolution: Resolution) {
        camera?.updateResolution(resolution.width, resolution.height)
    }

    override fun setRotation(degrees: Int) {
        // AUSBC render rotation. RotateType supports 0/90/180/270.
        val type = when (degrees) {
            90 -> com.jiangdg.ausbc.utils.bus.RotateType.ANGLE_90
            180 -> com.jiangdg.ausbc.utils.bus.RotateType.ANGLE_180
            270 -> com.jiangdg.ausbc.utils.bus.RotateType.ANGLE_270
            else -> com.jiangdg.ausbc.utils.bus.RotateType.ANGLE_0
        }
        camera?.setRotateType(type)
    }

    override fun setMirror(mirrored: Boolean) {
        // AUSBC horizontal flip via RotateType FLIP; if the installed version
        // lacks a flip rotate type, apply scaleX = -1 on the TextureView instead.
        (camera?.getSurfaceView() as? View)?.scaleX = if (mirrored) -1f else 1f
    }

    override suspend fun capturePhoto(target: File): Result<File> =
        suspendCancellableCoroutine { cont ->
            val cam = camera ?: run {
                cont.resume(Result.failure(IllegalStateException("camera not open")))
                return@suspendCancellableCoroutine
            }
            cam.captureImage(
                object : ICaptureCallBack {
                    override fun onBegin() = Unit
                    override fun onComplete(path: String?) {
                        if (cont.isActive) cont.resume(
                            if (path != null) Result.success(File(path))
                            else Result.failure(IllegalStateException("null capture path")),
                        )
                    }
                    override fun onError(error: String?) {
                        if (cont.isActive) cont.resume(
                            Result.failure(IllegalStateException(error ?: "capture failed")),
                        )
                    }
                },
                target.absolutePath,
            )
        }

    override suspend fun startRecording(target: File, withAudio: Boolean, bitRate: Int): Result<Unit> {
        val cam = camera ?: return Result.failure(IllegalStateException("camera not open"))
        recordingFile = target
        return suspendCancellableCoroutine { cont ->
            cam.captureVideoStart(
                object : ICaptureCallBack {
                    override fun onBegin() {
                        if (cont.isActive) cont.resume(Result.success(Unit))
                    }
                    override fun onComplete(path: String?) = Unit // fires at stop
                    override fun onError(error: String?) {
                        if (cont.isActive) cont.resume(
                            Result.failure(IllegalStateException(error ?: "record failed")),
                        )
                    }
                },
                target.absolutePath,
            )
        }
    }

    override suspend fun stopRecording(): Result<File> {
        val file = recordingFile
            ?: return Result.failure(IllegalStateException("not recording"))
        runCatching { camera?.captureVideoStop() }
        recordingFile = null
        return if (file.exists() && file.length() > 0) Result.success(file)
        else Result.failure(IllegalStateException("recording file missing or empty"))
    }
}
```

Note: audio-track enabling and bitrate on AUSBC's recorder are configured via `CameraRequest.Builder` / capture call options depending on library version — check `captureVideoStart` overloads in the 3.3.3 sources and pass `withAudio`/`bitRate` where supported; if unsupported, record video-only and log a warning (audio remains a settings toggle that simply has no effect until supported — document in README).

- [ ] **Step 4: Hilt module + preview factory**

`app/src/main/java/com/muh/usbcameraviewer/camera/CameraModule.kt`:
```kotlin
package com.muh.usbcameraviewer.camera

import android.content.Context
import android.view.View
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PreviewFactory

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    @Singleton
    abstract fun bindCameraController(impl: AusbcCameraController): CameraController

    companion object {
        @Provides
        @Singleton
        @PreviewFactory
        fun providePreviewFactory(): (Context) -> View = { AspectRatioTextureView(it) }
    }
}
```

In `ViewerViewModel`, add the injected factory and expose it:
```kotlin
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val controller: CameraController,
    private val settingsRepository: SettingsRepository,
    @PreviewFactory val previewFactory: (android.content.Context) -> android.view.View,
) : ViewModel() {
```

Update `ViewerViewModelTest` construction sites: `ViewerViewModel(fake, settingsRepo(), { TextureView(it) })` won't work in plain JVM — instead pass `{ throw UnsupportedOperationException() }` (tests never invoke the factory) — concretely: `ViewerViewModel(fake, settingsRepo()) { error("no view in unit test") }`.

In `ViewerScreen`, pass `previewFactory = viewModel.previewFactory` in the `ViewerCallbacks(...)` construction (the line noted in Task 5).

- [ ] **Step 5: Run unit tests + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 6: HARDWARE CHECKPOINT — manual verification**

Install on a phone (`./gradlew :app:installDebug`), plug UVC endoscope via OTG:
1. App offers to launch on plug-in (or launches if chosen as default).
2. System USB permission dialog appears; grant → live preview appears.
3. Deny instead → PermissionNeeded screen; "Grant permission" re-prompts.
4. Unplug during preview → NoDevice screen. Replug → preview returns without app restart.
5. Resolution picker lists device modes; switching works.
6. Rotate and mirror visibly transform the preview.

Record results in `docs/hardware-test-checklist.md` (created in Task 11; keep notes until then).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: AUSBC-backed CameraController, USB auto-launch, hardware-verified preview"
```

---

### Task 7: Capture pipeline — photos, video recording, MediaStore publish

**Files:**
- Create: `app/src/main/java/com/muh/usbcameraviewer/capture/CaptureStore.kt`
- Modify: `app/src/main/java/com/muh/usbcameraviewer/viewer/ViewerViewModel.kt`
- Modify: `app/src/main/java/com/muh/usbcameraviewer/viewer/ViewerScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml` (legacy storage + audio permissions)
- Test: modify `app/src/test/java/com/muh/usbcameraviewer/viewer/ViewerViewModelTest.kt` (add capture tests + fake store)

**Interfaces:**
- Consumes: `CameraController.capturePhoto/startRecording/stopRecording` (Task 2), `Filenames` (Task 2), `SettingsRepository` (Task 3), `ViewerViewModel` (Task 4)
- Produces:
  - `interface CaptureStore { fun tempFile(displayName: String): File; suspend fun publishPhoto(src: File, displayName: String): String; suspend fun publishVideo(src: File, displayName: String): String }` (returned strings are content-URI strings)
  - `MediaStoreCaptureStore` + Hilt binding
  - ViewModel additions: `onShutter()`, `onToggleCaptureMode()`, `val messages: SharedFlow<String>` (transient errors), recording ticker
  - Streaming state now carries `isRecording`, `recordingSeconds`, `lastCaptureUri` (fields already exist since Task 2)

- [ ] **Step 1: Manifest permissions**

Add to `AndroidManifest.xml`:
```xml
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 2: Implement CaptureStore**

`app/src/main/java/com/muh/usbcameraviewer/capture/CaptureStore.kt`:
```kotlin
package com.muh.usbcameraviewer.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CaptureStore {
    /** Scratch file in app cache the camera writes into before publishing. */
    fun tempFile(displayName: String): File

    /** Publish to Pictures/USB Camera Viewer; returns content URI string. Deletes [src]. */
    suspend fun publishPhoto(src: File, displayName: String): String

    /** Publish to Movies/USB Camera Viewer; returns content URI string. Deletes [src]. */
    suspend fun publishVideo(src: File, displayName: String): String
}

private const val ALBUM = "USB Camera Viewer"

@Singleton
class MediaStoreCaptureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : CaptureStore {

    override fun tempFile(displayName: String): File =
        File(context.cacheDir, displayName)

    override suspend fun publishPhoto(src: File, displayName: String): String =
        publish(
            src = src,
            displayName = displayName,
            mimeType = "image/jpeg",
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativeDir = "${Environment.DIRECTORY_PICTURES}/$ALBUM",
        )

    override suspend fun publishVideo(src: File, displayName: String): String =
        publish(
            src = src,
            displayName = displayName,
            mimeType = "video/mp4",
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativeDir = "${Environment.DIRECTORY_MOVIES}/$ALBUM",
        )

    private suspend fun publish(
        src: File,
        displayName: String,
        mimeType: String,
        collection: Uri,
        relativeDir: String,
    ): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        relativeDir.substringBefore('/'),
                    ),
                    ALBUM,
                ).apply { mkdirs() }
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore insert failed for $displayName")
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: error("openOutputStream failed for $uri")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
        src.delete()
        uri.toString()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureModule {
    @Binds
    @Singleton
    abstract fun bindCaptureStore(impl: MediaStoreCaptureStore): CaptureStore
}
```

- [ ] **Step 3: Write failing capture tests**

Add to `ViewerViewModelTest.kt` — new fixture and tests:
```kotlin
// imports to add:
// import com.muh.usbcameraviewer.capture.CaptureStore
// import com.muh.usbcameraviewer.camera.CaptureMode
// import kotlinx.coroutines.test.advanceTimeBy
// import org.junit.Assert.assertEquals / assertFalse / assertNull as needed

class FakeCaptureStore(private val root: File) : CaptureStore {
    val published = mutableListOf<String>()
    override fun tempFile(displayName: String): File = File(root, displayName)
    override suspend fun publishPhoto(src: File, displayName: String): String =
        "content://fake/photo/$displayName".also { published += it }
    override suspend fun publishVideo(src: File, displayName: String): String =
        "content://fake/video/$displayName".also { published += it }
}
```

Update the `vm()` helper: `ViewerViewModel(fake, settingsRepo(), FakeCaptureStore(tmp.root)) { error("no view in unit test") }` (constructor parameter order: controller, settingsRepository, captureStore, previewFactory).

New tests:
```kotlin
@Test
fun `shutter in photo mode captures and publishes, updates thumbnail`() = runTest {
    val vm = vm()
    fake.emit(CameraEvent.Attached("Endoscope"))
    fake.emit(CameraEvent.PermissionGranted("Endoscope"))
    fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
    vm.onShutter()
    val s = vm.state.value as CameraUiState.Streaming
    assertTrue(s.lastCaptureUri!!.startsWith("content://fake/photo/UCV_"))
    assertTrue(s.lastCaptureUri!!.endsWith(".jpg"))
}

@Test
fun `toggle mode then shutter starts recording, ticker counts, shutter again stops and publishes`() = runTest {
    val vm = vm()
    fake.emit(CameraEvent.Attached("Endoscope"))
    fake.emit(CameraEvent.PermissionGranted("Endoscope"))
    fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
    vm.onToggleCaptureMode()
    assertEquals(CaptureMode.VIDEO, (vm.state.value as CameraUiState.Streaming).captureMode)
    vm.onShutter()
    assertTrue((vm.state.value as CameraUiState.Streaming).isRecording)
    mainDispatcher.dispatcher.scheduler.advanceTimeBy(3_050)
    assertEquals(3L, (vm.state.value as CameraUiState.Streaming).recordingSeconds)
    vm.onShutter()
    val s = vm.state.value as CameraUiState.Streaming
    assertFalse(s.isRecording)
    assertTrue(s.lastCaptureUri!!.endsWith(".mp4"))
    assertEquals(1, fake.recordingStarts.size)
}

@Test
fun `photo capture failure emits message, stays streaming`() = runTest {
    fake.photoResult = { Result.failure(IllegalStateException("boom")) }
    val vm = vm()
    fake.emit(CameraEvent.Attached("Endoscope"))
    fake.emit(CameraEvent.PermissionGranted("Endoscope"))
    fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
    vm.messages.test {
        vm.onShutter()
        assertTrue(awaitItem().isNotBlank())
    }
    assertTrue(vm.state.value is CameraUiState.Streaming)
    assertNull((vm.state.value as CameraUiState.Streaming).lastCaptureUri)
}

@Test
fun `detach while recording stops and publishes partial file`() = runTest {
    val vm = vm()
    fake.emit(CameraEvent.Attached("Endoscope"))
    fake.emit(CameraEvent.PermissionGranted("Endoscope"))
    fake.emit(CameraEvent.Opened("Endoscope", res720, listOf(res720)))
    vm.onToggleCaptureMode()
    vm.onShutter() // start recording
    fake.emit(CameraEvent.Detached)
    assertEquals(CameraUiState.NoDevice, vm.state.value)
    // stopRecording was invoked so the partial file got finalized+published
    assertEquals(1, fake.recordingStarts.size)
}
```

Note: ticker test needs `MainDispatcherRule(StandardTestDispatcher())`? No — keep `UnconfinedTestDispatcher`; its scheduler still supports `advanceTimeBy`, and `delay` in the ticker suspends on the virtual clock. If the ticker test flakes, switch that single test to `runTest(mainDispatcher.dispatcher.scheduler)` semantics per kotlinx-coroutines-test docs.

- [ ] **Step 4: Run tests, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.viewer.ViewerViewModelTest"`
Expected: FAIL — `onShutter`/`onToggleCaptureMode`/`messages` unresolved.

- [ ] **Step 5: Implement capture in ViewerViewModel**

Additions to `ViewerViewModel.kt`:
```kotlin
// new constructor param after settingsRepository:
//   private val captureStore: CaptureStore,
// new imports: CaptureStore, CaptureMode, Filenames, Instant, Job, delay,
//   MutableSharedFlow, asSharedFlow

private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
val messages = _messages.asSharedFlow()

private var tickerJob: Job? = null

fun onToggleCaptureMode() {
    _state.update { s ->
        if (s !is CameraUiState.Streaming || s.isRecording) return
        s.copy(captureMode = if (s.captureMode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO)
    }
}

fun onShutter() {
    val s = _state.value as? CameraUiState.Streaming ?: return
    when {
        s.captureMode == CaptureMode.PHOTO -> capturePhoto()
        s.isRecording -> stopRecording(publish = true)
        else -> startRecording()
    }
}

private fun capturePhoto() {
    viewModelScope.launch {
        val prefix = settingsRepository.settings.first().filenamePrefix
        val name = Filenames.photo(prefix, java.time.Instant.now())
        controller.capturePhoto(captureStore.tempFile(name))
            .mapCatching { captureStore.publishPhoto(it, name) }
            .onSuccess { uri ->
                _state.update { s ->
                    if (s is CameraUiState.Streaming) s.copy(lastCaptureUri = uri) else s
                }
            }
            .onFailure { _messages.tryEmit("Photo failed: ${it.message}") }
    }
}

private fun startRecording() {
    viewModelScope.launch {
        val settings = settingsRepository.settings.first()
        val name = Filenames.video(settings.filenamePrefix, java.time.Instant.now())
        controller.startRecording(
            captureStore.tempFile(name),
            withAudio = settings.recordAudio,
            bitRate = settings.videoQuality.bitRate,
        ).onSuccess {
            _state.update { s ->
                if (s is CameraUiState.Streaming) s.copy(isRecording = true, recordingSeconds = 0) else s
            }
            tickerJob?.cancel()
            tickerJob = viewModelScope.launch {
                while (true) {
                    delay(1_000)
                    _state.update { s ->
                        if (s is CameraUiState.Streaming && s.isRecording)
                            s.copy(recordingSeconds = s.recordingSeconds + 1)
                        else s
                    }
                }
            }
        }.onFailure { _messages.tryEmit("Recording failed: ${it.message}") }
    }
}

private fun stopRecording(publish: Boolean) {
    tickerJob?.cancel()
    tickerJob = null
    viewModelScope.launch {
        controller.stopRecording()
            .mapCatching { file -> captureStore.publishVideo(file, file.name) }
            .onSuccess { uri ->
                _state.update { s ->
                    if (s is CameraUiState.Streaming)
                        s.copy(isRecording = false, recordingSeconds = 0, lastCaptureUri = uri)
                    else s
                }
            }
            .onFailure {
                _messages.tryEmit("Recording failed: ${it.message}")
                _state.update { s ->
                    if (s is CameraUiState.Streaming) s.copy(isRecording = false, recordingSeconds = 0) else s
                }
            }
    }
}
```

And extend `onEvent` Detached branch:
```kotlin
CameraEvent.Detached -> {
    val wasRecording = (_state.value as? CameraUiState.Streaming)?.isRecording == true
    if (wasRecording) stopRecording(publish = true)
    _state.value = CameraUiState.NoDevice
}
```

- [ ] **Step 6: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.viewer.ViewerViewModelTest"`
Expected: PASS (12 tests).

- [ ] **Step 7: Wire capture UI**

`ViewerScreen.kt` changes:
1. `ViewerScreen`: pass `onShutter = viewModel::onShutter`, `onToggleCaptureMode = viewModel::onToggleCaptureMode` into `ViewerCallbacks`. Collect `viewModel.messages` into a `SnackbarHost`:
```kotlin
val snackbar = remember { SnackbarHostState() }
LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
// wrap ViewerContent in a Box and add SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
```
2. In `StreamingContent` bottom bar: shutter icon = `Icons.Filled.PhotoCamera` in PHOTO mode; in VIDEO mode `Icons.Filled.FiberManualRecord` tinted `Color(0xFFFF5252)`, contentDescription "Start recording"/"Stop recording" per `state.isRecording`. Mode-toggle icon = `Icons.Filled.Videocam` (PHOTO mode) / `Icons.Filled.PhotoCamera` (VIDEO mode), contentDescription from `cd_toggle_mode`.
3. Recording chip top-center when `state.isRecording`:
```kotlin
AssistChip(
    onClick = {},
    label = { Text(formatElapsed(state.recordingSeconds)) },
    leadingIcon = {
        Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color(0xFFFF5252))
    },
)
```
with
```kotlin
internal fun formatElapsed(seconds: Long): String =
    "%02d:%02d".format(seconds / 60, seconds % 60)
```
4. Gallery button: if `state.lastCaptureUri != null` show `AsyncImage(model = state.lastCaptureUri, ...)` (Coil) clipped to CircleShape, else the `PhotoLibrary` icon.
5. Legacy storage permission (API 26–28): wrap shutter action —
```kotlin
val storagePermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted -> if (granted) callbacks.onShutter() }
val context = LocalContext.current
val shutter = {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
    ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    else callbacks.onShutter()
}
```
Use `shutter` for the shutter button onClick. Same pattern with `RECORD_AUDIO` before starting a recording when `recordAudio` setting is on — read the setting via a `recordAudioEnabled: Boolean` field added to `ViewerCallbacks` and populated in `ViewerScreen` from `viewModel.recordAudioEnabled` (a `StateFlow<Boolean>` derived in the ViewModel: `settingsRepository.settings.map { it.recordAudio }.stateIn(viewModelScope, SharingStarted.Eagerly, false)`).

Add a Compose test to `ViewerScreenTest`:
```kotlin
@Test
fun streaming_recording_showsElapsedChip() {
    setContent(streaming.copy(captureMode = CaptureMode.VIDEO, isRecording = true, recordingSeconds = 83))
    compose.onNodeWithText("01:23").assertIsDisplayed()
}
```
(import `com.muh.usbcameraviewer.camera.CaptureMode`)

- [ ] **Step 8: Run all tests + build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 9: HARDWARE CHECKPOINT — capture verification**

On device with endoscope: take photo → appears in system gallery under Pictures/USB Camera Viewer; record 10s video → plays back; detach mid-record → partial file exists and plays.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: photo and video capture with MediaStore publish, recording timer, permissions"
```

---

### Task 8: Gallery data — repository + ViewModel

**Files:**
- Create: `app/src/main/java/com/muh/usbcameraviewer/gallery/MediaItem.kt`
- Create: `app/src/main/java/com/muh/usbcameraviewer/gallery/MediaRepository.kt`
- Create: `app/src/main/java/com/muh/usbcameraviewer/gallery/GalleryViewModel.kt`
- Test: `app/src/test/java/com/muh/usbcameraviewer/gallery/GalleryViewModelTest.kt`

**Interfaces:**
- Consumes: nothing new (Android MediaStore APIs)
- Produces:
  - `data class MediaItem(val uri: String, val isVideo: Boolean, val displayName: String, val dateAddedEpochSeconds: Long)`
  - `interface MediaRepository { suspend fun loadItems(): List<MediaItem>; suspend fun delete(item: MediaItem): Boolean }`
  - `MediaStoreMediaRepository` + Hilt binding (`GalleryModule` inside MediaRepository.kt)
  - `GalleryViewModel(repo)` — `val state: StateFlow<GalleryUiState>`, `fun refresh()`, `fun delete(item)`
  - `sealed interface GalleryUiState { Loading; Empty; data class Content(items) }`

- [ ] **Step 1: Write MediaItem + repository interface**

`app/src/main/java/com/muh/usbcameraviewer/gallery/MediaItem.kt`:
```kotlin
package com.muh.usbcameraviewer.gallery

data class MediaItem(
    val uri: String,
    val isVideo: Boolean,
    val displayName: String,
    val dateAddedEpochSeconds: Long,
)
```

`app/src/main/java/com/muh/usbcameraviewer/gallery/MediaRepository.kt`:
```kotlin
package com.muh.usbcameraviewer.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface MediaRepository {
    /** All app captures, newest first. */
    suspend fun loadItems(): List<MediaItem>

    /** True if the row was deleted. */
    suspend fun delete(item: MediaItem): Boolean
}

private const val ALBUM = "USB Camera Viewer"

@Singleton
class MediaStoreMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaRepository {

    override suspend fun loadItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        val photos = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        val videos = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        (photos + videos).sortedByDescending { it.dateAddedEpochSeconds }
    }

    private fun query(collection: Uri, isVideo: Boolean): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("%$ALBUM%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/$ALBUM/%")
        }
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                items += MediaItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString(),
                    isVideo = isVideo,
                    displayName = cursor.getString(nameCol),
                    dateAddedEpochSeconds = cursor.getLong(dateCol),
                )
            }
        }
        return items
    }

    override suspend fun delete(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.delete(Uri.parse(item.uri), null, null) > 0
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GalleryModule {
    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaStoreMediaRepository): MediaRepository
}
```

- [ ] **Step 2: Write failing GalleryViewModel tests**

`app/src/test/java/com/muh/usbcameraviewer/gallery/GalleryViewModelTest.kt`:
```kotlin
package com.muh.usbcameraviewer.gallery

import com.muh.usbcameraviewer.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeMediaRepository : MediaRepository {
    val items = mutableListOf<MediaItem>()
    var deleteResult = true
    val deleted = mutableListOf<MediaItem>()
    override suspend fun loadItems(): List<MediaItem> = items.toList()
    override suspend fun delete(item: MediaItem): Boolean {
        deleted += item
        if (deleteResult) items.remove(item)
        return deleteResult
    }
}

class GalleryViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val repo = FakeMediaRepository()
    private val photo = MediaItem("content://1", false, "UCV_20260722_120000.jpg", 100)
    private val video = MediaItem("content://2", true, "UCV_20260722_120100.mp4", 200)

    @Test
    fun `empty store shows Empty`() = runTest {
        val vm = GalleryViewModel(repo)
        assertEquals(GalleryUiState.Empty, vm.state.value)
    }

    @Test
    fun `items load newest first as given by repo`() = runTest {
        repo.items += listOf(video, photo)
        val vm = GalleryViewModel(repo)
        val content = vm.state.value as GalleryUiState.Content
        assertEquals(listOf(video, photo), content.items)
    }

    @Test
    fun `delete removes item and refreshes`() = runTest {
        repo.items += listOf(video, photo)
        val vm = GalleryViewModel(repo)
        vm.delete(video)
        assertEquals(listOf(video), repo.deleted)
        val content = vm.state.value as GalleryUiState.Content
        assertEquals(listOf(photo), content.items)
    }

    @Test
    fun `deleting last item transitions to Empty`() = runTest {
        repo.items += photo
        val vm = GalleryViewModel(repo)
        vm.delete(photo)
        assertTrue(vm.state.value is GalleryUiState.Empty)
    }
}
```

- [ ] **Step 3: Run tests, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.gallery.GalleryViewModelTest"`
Expected: FAIL — `Unresolved reference: GalleryViewModel`.

- [ ] **Step 4: Implement GalleryViewModel**

`app/src/main/java/com/muh/usbcameraviewer/gallery/GalleryViewModel.kt`:
```kotlin
package com.muh.usbcameraviewer.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface GalleryUiState {
    data object Loading : GalleryUiState
    data object Empty : GalleryUiState
    data class Content(val items: List<MediaItem>) : GalleryUiState
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val items = repository.loadItems()
            _state.value = if (items.isEmpty()) GalleryUiState.Empty
            else GalleryUiState.Content(items)
        }
    }

    fun delete(item: MediaItem) {
        viewModelScope.launch {
            repository.delete(item)
            refresh()
        }
    }
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.muh.usbcameraviewer.gallery.GalleryViewModelTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: gallery repository over MediaStore and GalleryViewModel with tests"
```

---

### Task 9: Gallery UI — grid, pager, share, delete

**Files:**
- Create: `app/src/main/java/com/muh/usbcameraviewer/gallery/GalleryScreen.kt`
- Modify: `app/src/main/java/com/muh/usbcameraviewer/navigation/AppNav.kt` (replace placeholder)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `GalleryViewModel`, `GalleryUiState`, `MediaItem` (Task 8)
- Produces: `GalleryScreen(onBack: () -> Unit, viewModel: GalleryViewModel = hiltViewModel())`

- [ ] **Step 1: Add strings**

Merge into `strings.xml`:
```xml
<string name="gallery_title">Gallery</string>
<string name="gallery_empty">No captures yet. Photos and videos you take appear here.</string>
<string name="cd_back">Back</string>
<string name="cd_share">Share</string>
<string name="cd_delete">Delete</string>
<string name="cd_video_badge">Video</string>
<string name="delete_confirm_title">Delete capture?</string>
<string name="delete_confirm_body">%1$s will be removed permanently.</string>
<string name="delete">Delete</string>
<string name="cancel">Cancel</string>
```

- [ ] **Step 2: Implement GalleryScreen**

`app/src/main/java/com/muh/usbcameraviewer/gallery/GalleryScreen.kt`:
```kotlin
package com.muh.usbcameraviewer.gallery

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.input.pointer.pointerInput  // NOTE: actual package is androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.net.toUri  // NOTE: use androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.muh.usbcameraviewer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                GalleryUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                GalleryUiState.Empty -> Text(
                    stringResource(R.string.gallery_empty),
                    Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                is GalleryUiState.Content -> {
                    GalleryGrid(items = s.items, onOpen = { viewerIndex = it })
                    viewerIndex?.let { index ->
                        FullScreenViewer(
                            items = s.items,
                            startIndex = index.coerceAtMost(s.items.lastIndex),
                            onClose = { viewerIndex = null },
                            onDelete = viewModel::delete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryGrid(items: List<MediaItem>, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items.size) { index ->
            val item = items[index]
            Box(
                Modifier
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .clickable { onOpen(index) },
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.uri)
                        .apply { if (item.isVideo) decoderFactory(VideoFrameDecoder.Factory()) }
                        .crossfade(true)
                        .build(),
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                if (item.isVideo) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = stringResource(R.string.cd_video_badge),
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenViewer(
    items: List<MediaItem>,
    startIndex: Int,
    onClose: () -> Unit,
    onDelete: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    var confirmDelete by remember { mutableStateOf<MediaItem?>(null) }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            if (item.isVideo) VideoPlayer(item) else ZoomableImage(item)
        }
        val current = items[pagerState.currentPage.coerceAtMost(items.lastIndex)]
        androidx.compose.foundation.layout.Row(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (current.isVideo) "video/mp4" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(current.uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, null))
            }) {
                Icon(Icons.Filled.Share, stringResource(R.string.cd_share), tint = Color.White)
            }
            IconButton(onClick = { confirmDelete = current }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.cd_delete), tint = Color.White)
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = Color.White,
            )
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body, item.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDelete(item)
                    if (items.size == 1) onClose()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ZoomableImage(item: MediaItem) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    AsyncImage(
        model = item.uri,
        contentDescription = item.displayName,
        modifier = Modifier
            .fillMaxSize()
            .androidx.compose.ui.input.pointer.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offsetX += pan.x
                    offsetY += pan.y
                    if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
    )
}

@Composable
private fun VideoPlayer(item: MediaItem) {
    // Zero-dependency playback: show video frame + play button that hands
    // off to the system player. Keeps app offline-only and small.
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.uri)
                .decoderFactory(VideoFrameDecoder.Factory())
                .build(),
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.parse(item.uri), "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            },
            modifier = Modifier.align(Alignment.Center).size(80.dp),
        ) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = item.displayName,
                tint = Color.White,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

Import fixes flagged inline above: `pointerInput` comes from `androidx.compose.ui.input.pointer.pointerInput` used as a normal modifier extension (`.pointerInput(Unit) { ... }` after importing it); delete the two bogus `// NOTE` imports and use correct ones.

- [ ] **Step 3: Wire navigation**

In `AppNav.kt` replace the gallery placeholder:
```kotlin
composable(Routes.GALLERY) { GalleryScreen(onBack = { nav.popBackStack() }) }
```
(import `com.muh.usbcameraviewer.gallery.GalleryScreen`)

Also make gallery refresh when returning to it: `GalleryViewModel.refresh()` already runs in `init`; additionally call `viewModel.refresh()` from a `LaunchedEffect(Unit)` in `GalleryScreen` so re-navigation with a retained VM refreshes.

- [ ] **Step 4: Build + test**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 5: HARDWARE CHECKPOINT — gallery manual check**

Capture a photo and a video, open gallery: grid shows both (video has play badge), pager zooms photo, share sheet opens, delete removes with confirmation, empty state appears after deleting all.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: in-app gallery with grid, zoomable pager, share and delete"
```

---

### Task 10: Settings screen + keep-screen-on

**Files:**
- Create: `app/src/main/java/com/muh/usbcameraviewer/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/muh/usbcameraviewer/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/muh/usbcameraviewer/navigation/AppNav.kt`
- Modify: `app/src/main/java/com/muh/usbcameraviewer/viewer/ViewerScreen.kt` (keep-screen-on)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `SettingsRepository`, `AppSettings`, enums (Task 3)
- Produces: `SettingsScreen(onBack)`; `SettingsViewModel` exposing `val settings: StateFlow<AppSettings>` + setter passthroughs; `ViewerCallbacks.keepScreenOn: Boolean`

- [ ] **Step 1: Add strings**

```xml
<string name="settings_title">Settings</string>
<string name="settings_resolution_policy">Startup resolution</string>
<string name="settings_resolution_remember">Remember last used</string>
<string name="settings_resolution_ask">Ask every time</string>
<string name="settings_video_quality">Video quality</string>
<string name="settings_quality_low">Low (2 Mbps)</string>
<string name="settings_quality_medium">Medium (6 Mbps)</string>
<string name="settings_quality_high">High (12 Mbps)</string>
<string name="settings_record_audio">Record microphone audio</string>
<string name="settings_filename_prefix">Filename prefix</string>
<string name="settings_keep_screen_on">Keep screen on while viewing</string>
<string name="settings_about">About</string>
<string name="settings_about_body">USB Camera Viewer %1$s — by muh. Fully offline; no data leaves your device. UVC support via the open-source AUSBC library (Apache-2.0).</string>
```

- [ ] **Step 2: SettingsViewModel**

`app/src/main/java/com/muh/usbcameraviewer/settings/SettingsViewModel.kt`:
```kotlin
package com.muh.usbcameraviewer.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setResolutionPolicy(v: ResolutionPolicy) = viewModelScope.launch { repository.setResolutionPolicy(v) }
    fun setVideoQuality(v: VideoQuality) = viewModelScope.launch { repository.setVideoQuality(v) }
    fun setRecordAudio(v: Boolean) = viewModelScope.launch { repository.setRecordAudio(v) }
    fun setFilenamePrefix(v: String) = viewModelScope.launch { repository.setFilenamePrefix(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { repository.setKeepScreenOn(v) }
}
```

- [ ] **Step 3: SettingsScreen**

`app/src/main/java/com/muh/usbcameraviewer/settings/SettingsScreen.kt`:
```kotlin
package com.muh.usbcameraviewer.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muh.usbcameraviewer.BuildConfig
import com.muh.usbcameraviewer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel(stringResource(R.string.settings_resolution_policy))
            RadioRow(
                label = stringResource(R.string.settings_resolution_remember),
                selected = settings.resolutionPolicy == ResolutionPolicy.REMEMBER_LAST,
                onSelect = { viewModel.setResolutionPolicy(ResolutionPolicy.REMEMBER_LAST) },
            )
            RadioRow(
                label = stringResource(R.string.settings_resolution_ask),
                selected = settings.resolutionPolicy == ResolutionPolicy.ALWAYS_ASK,
                onSelect = { viewModel.setResolutionPolicy(ResolutionPolicy.ALWAYS_ASK) },
            )
            HorizontalDivider()

            SectionLabel(stringResource(R.string.settings_video_quality))
            RadioRow(
                stringResource(R.string.settings_quality_low),
                settings.videoQuality == VideoQuality.LOW,
            ) { viewModel.setVideoQuality(VideoQuality.LOW) }
            RadioRow(
                stringResource(R.string.settings_quality_medium),
                settings.videoQuality == VideoQuality.MEDIUM,
            ) { viewModel.setVideoQuality(VideoQuality.MEDIUM) }
            RadioRow(
                stringResource(R.string.settings_quality_high),
                settings.videoQuality == VideoQuality.HIGH,
            ) { viewModel.setVideoQuality(VideoQuality.HIGH) }
            HorizontalDivider()

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_record_audio)) },
                trailingContent = {
                    Switch(checked = settings.recordAudio, onCheckedChange = viewModel::setRecordAudio)
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_keep_screen_on)) },
                trailingContent = {
                    Switch(checked = settings.keepScreenOn, onCheckedChange = viewModel::setKeepScreenOn)
                },
            )
            HorizontalDivider()

            var prefix by remember(settings.filenamePrefix) { mutableStateOf(settings.filenamePrefix) }
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                label = { Text(stringResource(R.string.settings_filename_prefix)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
            LaunchedEffect(prefix) {
                kotlinx.coroutines.delay(400) // debounce
                if (prefix != settings.filenamePrefix) viewModel.setFilenamePrefix(prefix)
            }
            HorizontalDivider()

            SectionLabel(stringResource(R.string.settings_about))
            Text(
                stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
```

Note: `BuildConfig` requires `buildFeatures { buildConfig = true }` in `app/build.gradle.kts` android block — add it.

- [ ] **Step 4: Wire navigation + keep-screen-on**

`AppNav.kt`: `composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }`.

Keep-screen-on in `ViewerScreen.kt`: ViewModel gains
```kotlin
val keepScreenOn: StateFlow<Boolean> = settingsRepository.settings
    .map { it.keepScreenOn }
    .stateIn(viewModelScope, SharingStarted.Eagerly, true)
```
and in `ViewerScreen`:
```kotlin
val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
val view = LocalView.current
DisposableEffect(keepScreenOn, state is CameraUiState.Streaming) {
    view.keepScreenOn = keepScreenOn && state is CameraUiState.Streaming
    onDispose { view.keepScreenOn = false }
}
```

ALWAYS_ASK policy behavior: in `ViewerContent`, when streaming begins (first composition of `StreamingContent` for a device) and policy is ALWAYS_ASK, auto-open the resolution dropdown once. Implement: `ViewerCallbacks.autoShowResolutionPicker: Boolean` populated from ViewModel (`settings.resolutionPolicy == ALWAYS_ASK`), and in `StreamingContent`: `var resMenu by remember { mutableStateOf(callbacks.autoShowResolutionPicker) }`.

- [ ] **Step 5: Build + test**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: settings screen with persistence, keep-screen-on, about section"
```

---

### Task 11: Production polish — icon, ProGuard, README, hardware checklist, quality gates

**Files:**
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/values/ic_launcher_background.xml` (or colors)
- Modify: `app/proguard-rules.pro`
- Modify: root `build.gradle.kts` + `gradle/libs.versions.toml` (detekt, ktlint)
- Create: `README.md`, `docs/hardware-test-checklist.md`

**Interfaces:**
- Consumes: everything
- Produces: shippable release build + docs

- [ ] **Step 1: Adaptive launcher icon**

`app/src/main/res/values/ic_launcher_background.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0B0F12</color>
</resources>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml` (lens/scope motif):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- outer lens ring -->
    <path
        android:fillColor="#7FD0FF"
        android:pathData="M54,26a28,28 0 1,0 0,56a28,28 0 1,0 0,-56zM54,34a20,20 0 1,1 0,40a20,20 0 1,1 0,-40z" />
    <!-- inner pupil -->
    <path
        android:fillColor="#C4E7FF"
        android:pathData="M54,44a10,10 0 1,0 0,20a10,10 0 1,0 0,-20z" />
    <!-- cable -->
    <path
        android:fillColor="#7FD0FF"
        android:pathData="M50,80h8v14h-8z" />
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

Duplicate as `ic_launcher_round.xml` with the same content; set `android:roundIcon="@mipmap/ic_launcher_round"` in the manifest `<application>`. Delete template mipmap PNGs if any.

- [ ] **Step 2: ProGuard rules**

`app/proguard-rules.pro`:
```
# AUSBC / libuvc JNI — native callbacks reference these by name
-keep class com.jiangdg.** { *; }
-dontwarn com.jiangdg.**

# Keep USB monitor callbacks invoked reflectively
-keepclassmembers class * implements com.jiangdg.usb.USBMonitor$OnDeviceConnectListener { *; }

# Kotlin coroutines debug metadata
-dontwarn kotlinx.coroutines.**
```

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL (unsigned release; signing config is user-specific, out of scope).

- [ ] **Step 3: Quality gates — detekt + ktlint**

Root `build.gradle.kts` plugins block additions + catalog:
```toml
# libs.versions.toml [versions]
detekt = "1.23.7"
ktlintPlugin = "12.1.2"
# [plugins]
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlintPlugin" }
```
```kotlin
// root build.gradle.kts
plugins {
    // existing aliases…
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}
```
```kotlin
// app/build.gradle.kts plugins block
alias(libs.plugins.detekt)
alias(libs.plugins.ktlint)
```

Run: `./gradlew detekt ktlintCheck lint test`
Fix reported issues (formatting via `./gradlew ktlintFormat`; suppress noisy detekt rules in a generated `detekt.yml` only when justified).
Expected: all green.

- [ ] **Step 4: README**

`README.md`:
```markdown
# USB Camera Viewer

Modern Android viewer for USB (UVC) endoscopes and borescopes. By muh.

## Features

- Live preview over USB-C OTG — auto-launches when a camera is plugged in
- Photo capture and MP4 video recording (optional microphone audio)
- In-app gallery: zoomable viewer, share, delete
- Resolution picker, rotate/mirror, recording timer
- Dark, camera-first UI (Jetpack Compose, Material 3)
- Fully offline — no INTERNET permission, no analytics

## Requirements

- Android 8.0+ (API 26), phone with USB host (OTG) support
- A UVC-class USB camera (most cheap endoscopes are UVC)

## Build

    ./gradlew :app:assembleDebug     # debug APK
    ./gradlew :app:assembleRelease   # minified release (configure signing first)
    ./gradlew test detekt ktlintCheck lint

## Architecture

Single-module MVVM. Compose UI ← StateFlow ← ViewModels; all UVC access
behind `CameraController` (implemented with [AUSBC]). Captures publish to
MediaStore (`Pictures/USB Camera Viewer`, `Movies/USB Camera Viewer`).
See `docs/superpowers/specs/` for the design spec and
`docs/hardware-test-checklist.md` for the manual test pass.

[AUSBC]: https://github.com/jiangdongguo/AndroidUSBCamera

## Known limitations

- Audio-in-video depends on AUSBC recorder support on your device
- UVC camera controls (brightness/focus) not exposed yet (v2)
```

- [ ] **Step 5: Hardware test checklist**

`docs/hardware-test-checklist.md`:
```markdown
# Hardware Test Checklist

Run on a physical phone with a UVC endoscope before every release.
Device: ____________  Camera: ____________  Date: ________

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
- [ ] Audio on: video has sound; audio off: silent track/no track
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
```

- [ ] **Step 6: Final full verification**

Run: `./gradlew clean test detekt ktlintCheck lint :app:assembleRelease`
Expected: all green. Run the complete hardware checklist on-device; check every box or file issues.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: launcher icon, proguard, quality gates, README, hardware checklist"
```

---

## Plan Self-Review Notes

- Spec §3.3 lists a "fixed" resolution policy; trimmed to REMEMBER_LAST /
  ALWAYS_ASK (YAGNI) — spec updated to match.
- Spec §7 mentions ktlint + detekt: both land in Task 11.
- AUSBC exact API surface is the one high-uncertainty area; Task 6 carries
  the adaptation instruction and the architecture isolates the risk to one file.
- Robolectric Compose tests cover Viewer states (spec §7); gallery UI is
  covered by ViewModel tests + hardware checklist (MediaStore not
  Robolectric-testable in a meaningful way).
