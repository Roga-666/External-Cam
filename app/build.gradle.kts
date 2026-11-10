import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }

fun signingValue(
    property: String,
    envVar: String,
): String? = keystoreProperties.getProperty(property) ?: System.getenv(envVar)

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

ktlint {
    // Use the Android Kotlin style guide (4-space indent, no wildcard imports).
    android.set(true)
}

android {
    // Keep the original source namespace while giving this fork an independent install identity.
    namespace = "com.muh.usbcameraviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.roga666.externalcam"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
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

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
}

// ui-test-manifest above is debugImplementation-only (Google's convention: it injects a
// test-only ComponentActivity host for createComposeRule()). The release variant does not
// include this activity by design. The Compose UI tests in ViewerScreenTest and
// GalleryScreenTest therefore cannot launch on the release variant under Robolectric;
// all those tests DO execute on debug. Keep release JVM model/controller tests active,
// while preserving the production release manifest without an artificial test activity.
tasks.withType<Test>().matching { it.name == "testReleaseUnitTest" }.configureEach {
    filter {
        excludeTestsMatching("com.muh.usbcameraviewer.viewer.ViewerScreenTest")
        excludeTestsMatching("com.muh.usbcameraviewer.gallery.GalleryScreenTest")
        isFailOnNoMatchingTests = false
    }
}
