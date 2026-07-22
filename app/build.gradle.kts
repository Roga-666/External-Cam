plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

ktlint {
    // Use the Android Kotlin style guide (4-space indent, no wildcard imports).
    android.set(true)
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint {
        disable += "NotificationPermission"
        // libausbc pulls in com.bumptech.glide:glide transitively for its own (unused) image
        // loading; this app never calls Glide and uses Coil for thumbnails instead. AGP lint's
        // classpath scan still flags Glide's bundled NotificationTarget class as needing
        // POST_NOTIFICATIONS because it's present on the classpath, even though no app code
        // reaches it. See app/proguard-rules.pro / build notes for the same Glide/AUSBC context.
    }
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
    implementation(libs.ausbc) {
        // AUSBC 3.2.7's POM drags in two extras only its demo UI uses; neither is
        // published on the repos we allow, and no libausbc class references them.
        exclude(group = "com.gyf.immersionbar", module = "immersionbar")
        exclude(group = "com.zlc.glide", module = "webpdecoder")
    }
    // libausbc declares libuvc (com.serenegiant.usb.*) with runtime scope only, but the
    // wrapper references USBMonitor.UsbControlBlock at compile time, so promote it to compile.
    implementation(libs.ausbc.uvc)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// ui-test-manifest above is debugImplementation-only (Google's own convention: it injects a
// throwaway ComponentActivity host into the *debug* merged manifest for createComposeRule(),
// and must never ship in release). That means the release variant's merged manifest has no
// host activity, so any test using createComposeRule() (ViewerScreenTest) fails under
// testReleaseUnitTest with "Unable to resolve activity for ... ComponentActivity" — not a
// real regression, just the release variant lacking a manifest entry it's not supposed to
// have. Exclude only that Compose-UI-test class; every other unit test (Filenames, Settings,
// ViewerViewModel, GalleryViewModel, ...) still runs against the release/minified classes.
tasks.withType<Test>().matching { it.name == "testReleaseUnitTest" }.configureEach {
    filter {
        excludeTestsMatching("com.muh.usbcameraviewer.viewer.ViewerScreenTest")
        isFailOnNoMatchingTests = false
    }
}
