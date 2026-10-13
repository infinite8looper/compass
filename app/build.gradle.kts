import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.infinite8looper.compass"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.infinite8looper.compass"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        // The instrumented tests bring the real activity to the front and wait for its main thread to go idle.
        // A screen that is animating never does (the hold-flat hint loops for as long as the watch is tilted, and
        // an emulator's default pose is upright), so the tests run with the system's animations switched off.
        animationsDisabled = true
    }
    // The migration test reads the exported schemas as assets.
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

// Room writes the database's schema here, one file per version. They are kept in the repository: the migration test
// builds a database of an earlier version from them and checks the migrated one against the current one. It reads
// them itself: Room's own migration test helper (room-testing 2.8.5) needs kotlinx.serialization 1.8.1 where the
// app has 1.7.3 from the navigation library, and an instrumented test runs on the app's copy.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    implementation("androidx.wear.compose:compose-navigation:1.7.1")
    implementation("androidx.wear:wear-input:1.2.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

// Lint's analysis of the debug unit tests and of the debug instrumented tests (lintAnalyzeDebugUnitTest,
// lintAnalyzeDebugAndroidTest) indexes the Kotlin files KSP generated for the release variant too, though their own
// lint models name no such folder. When a release build is asked for in the same invocation, kspReleaseKotlin can be
// remaking those files at that moment, and the analysis then fails on a file that is not there
// (`./gradlew lintDebug assembleRelease` with debug already built; seen with AGP 9.4.1 and KSP 2.3.12).
// So where both are to run, lint's analysis runs after the generated sources are made.
// The tasks are picked by name, which makes none of them that the build does not need.
tasks.named { it.startsWith("lintAnalyze") || it.startsWith("lintVitalAnalyze") }.configureEach {
    mustRunAfter(tasks.named { it.startsWith("ksp") })
}

// The instrumented tests delete the app's waypoints and settings on the device they run on, Gradle uninstalls the
// app when they end, and without ANDROID_SERIAL Gradle runs them on every device adb lists. They are for emulators.
// So every task that runs them, and every task that uninstalls, fails before it does anything unless ANDROID_SERIAL
// names an emulator (emulator-NNNN) and adb lists no device that is not one. Installing is not held back.
val adbForDeviceCheck = androidComponents.sdkComponents.adb
tasks.named { (it.startsWith("connected") && (it.endsWith("AndroidTest") || it.endsWith("Check"))) || it == "deviceCheck" || it.startsWith("uninstall") }.configureEach {
    val adb = adbForDeviceCheck
    val serial = providers.environmentVariable("ANDROID_SERIAL")
    doFirst {
        val named = serial.orNull
        if (named == null || !Regex("^emulator-\\d+$").matches(named)) {
            throw GradleException(
                "refusing: this task wipes the app's data on the device it runs on and is for emulators only. " +
                    "Set ANDROID_SERIAL to the emulator's serial (for example ANDROID_SERIAL=emulator-5554); it is ${named ?: "not set"}."
            )
        }
        val listed = ProcessBuilder(adb.get().asFile.absolutePath, "devices").redirectErrorStream(true).start()
            .inputStream.bufferedReader().readLines().drop(1).mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.takeIf(String::isNotEmpty) }
        val others = listed.filterNot { Regex("^emulator-\\d+$").matches(it) }
        if (others.isNotEmpty()) {
            throw GradleException(
                "refusing: a device that is not an emulator is attached (${others.joinToString()}). " +
                    "This task wipes the app's data and is for emulators only; unplug it or disconnect it and run again."
            )
        }
    }
}
