# Wear OS Compass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone Wear OS compass that logs GPS waypoints and shows any number of them as colored targets on the dial, ready for release under the infinite8looper Play account.

**Architecture:** A single-activity Compose for Wear OS app. Pure Kotlin functions hold all navigation math and target logic; thin Android wrappers expose the rotation-vector sensor and fused location as Flows; Room stores waypoints and DataStore stores settings; one ViewModel combines them into screen state. The dial is one Canvas whose rotation is updated per display frame without recomposition.

**Tech Stack:** Kotlin 2.4.21, Android Gradle Plugin 9.4.1, Gradle 9.8.1, KSP 2.3.12, Compose BOM 2026.09.00, Wear Compose (material3, foundation, navigation) 1.7.1, Room 2.8.5, DataStore 1.2.1, Play services location 21.4.0, kotlinx-coroutines 1.11.0, JDK 17.

**Spec:** `docs/superpowers/specs/2026-10-11-wear-compass-design.md`

## Global Constraints

- Package and application id: `com.infinite8looper.compass`. Min SDK 33, target SDK 35, compile SDK 36.
- No network access, no phone companion, no accounts. The manifest requests only the two location permissions.
- No mocks or fakes in any test. Unit tests call real functions with real inputs; instrumented tests use a real database file, a real DataStore file, real sensors and real location on the emulator.
- Never weaken or delete a failing test to make it pass. Fix the code.
- User-facing strings fixed by the spec, verbatim: `Arrived at target`, `Set target`, `MAG`, `No target`, `Waypoint N` for auto-names.
- Waypoint colors: `Color.hsv(hue, 0.65f, 1f)`; only hue varies.
- American English in all prose and strings.
- Versions above were the latest stable on 2026-10-11 (read from Google's Maven index, Maven Central and services.gradle.org). The code in this plan was written against older releases of these libraries. When a build fails on a renamed or moved API, fix the call against the library's current documentation; do not remove the behavior.
- Known-good fallback toolchain on this machine, used by `~/beam-up-pixel`: Gradle 8.11.1, AGP 8.9.0, Kotlin 2.1.0. Use it only if Task 1's build cannot be made to work with the versions above, and record why in the notes.
- `adb` is not on PATH. Use `ADB=~/Library/Android/sdk/platform-tools/adb` and `EMU=~/Library/Android/sdk/emulator/emulator`.
- Shell is zsh on macOS: no `timeout` command; every wait loop needs its own deadline.
- After each task: update `notes/2026-10-11-session-notes.md`, commit, and push once that task's checks pass. Before any push, scan the diff for keys or personal data.
- Scratch scripts and screenshots go in `scripts/` or `docs/screenshots/` if kept, and are otherwise deleted.
- Publishing to Play, and any wording that speaks for the owner (privacy text, store listing), needs the owner's explicit approval.
- Learning-mode note: at the two points marked **Owner contribution**, offer the owner the chance to write the function before using the version given here.

## Review Focus

Inputs the spec implies that are most likely to bite a wearer, each pinned by a test in the task named:

1. The wearer stands exactly on a waypoint (distance 0): bearing must be a finite number and the text must read `Arrived at target`. Task 2.
2. A waypoint across the antimeridian or near a pole: distance and bearing take the short way round. Task 2.
3. A sensor event with a NaN heading, or a frame with zero elapsed time: the dial keeps its previous value instead of jumping or going blank. Task 2.
4. A rename that comes back empty, all spaces, or very long: the old name is kept, or the new one is trimmed to 40 characters. Task 2.
5. Location permission missing or revoked while targets are active: the location flow ends quietly and the compass keeps working. Task 6, and the on-device checklist in Task 12.

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle.properties, .gitignore
gradlew, gradle/wrapper/*
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/main/res/values/strings.xml
app/src/main/java/com/infinite8looper/compass/
  MainActivity.kt            activity; hosts CompassApp
  nav/Navigation.kt          pure math, formatting, hue, fix acquisition
  nav/Targets.kt             pure target and focus logic, name cleaning
  data/Waypoint.kt           Room entity, DAO, database
  data/SettingsStore.kt      DataStore wrapper
  sensors/HeadingSource.kt   rotation-vector and magnetometer Flow
  sensors/LocationSource.kt  fused location Flow, declination lookup
  CompassViewModel.kt        combines everything into NavUi
  ui/CompassApp.kt           navigation graph
  ui/CompassDial.kt          the Canvas dial
  ui/CompassScreen.kt        start screen
  ui/LogScreen.kt            logging flow
  ui/WaypointsScreen.kt      list
  ui/EditScreen.kt           edit, rename, delete; hue picker
  ui/CalibrationScreen.kt
  ui/SettingsScreen.kt
app/src/test/java/com/infinite8looper/compass/nav/NavigationTest.kt, TargetsTest.kt
app/src/androidTest/java/com/infinite8looper/compass/
  WaypointDatabaseTest.kt, SettingsStoreTest.kt, HeadingSourceTest.kt, LocationSourceTest.kt
scripts/emulator.sh, scripts/screenshot.sh, scripts/geo-feed.sh
```

---

### Task 1: Project scaffold and toolchain check

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/java/com/infinite8looper/compass/MainActivity.kt`, `scripts/emulator.sh`, `scripts/screenshot.sh`
- Copy: `gradlew`, `gradle/wrapper/` from `~/beam-up-pixel/BeamUpWatchFace/`

**Interfaces:**
- Produces: a building app module; `scripts/emulator.sh` (boots an emulator and waits), `scripts/screenshot.sh <name>` (writes `docs/screenshots/<name>.png`).

- [ ] **Step 1: Copy the Gradle wrapper and point it at Gradle 9.8.1**

```bash
cd ~/compass
cp ~/beam-up-pixel/BeamUpWatchFace/gradlew . && chmod +x gradlew
mkdir -p gradle && cp -R ~/beam-up-pixel/BeamUpWatchFace/gradle/wrapper gradle/
sed -i '' 's#gradle-8.11.1-all.zip#gradle-9.8.1-bin.zip#' gradle/wrapper/gradle-wrapper.properties
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```
Expected: the line ends in `gradle-9.8.1-bin.zip`.

- [ ] **Step 2: Write the root build files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "compass"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2g
android.useAndroidX=true
kotlin.code.style=official
```

`.gitignore`:
```gitignore
.DS_Store
.gradle/
build/
local.properties
*.iml
.idea/
keystore.properties
*.jks
*.keystore
```

- [ ] **Step 3: Write `app/build.gradle.kts`**

AGP 9 compiles Kotlin itself, so the `org.jetbrains.kotlin.android` plugin is not applied. If Gradle reports that plugin is required, add `id("org.jetbrains.kotlin.android") version "2.4.21"` to both plugin blocks.

```kotlin
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
    compileSdk = 36

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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
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
```

- [ ] **Step 4: Write the manifest, resources and a first activity**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.type.watch" />
    <uses-feature android:name="android.hardware.sensor.compass" android:required="true" />
    <uses-feature android:name="android.hardware.location.gps" android:required="false" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.DeviceDefault">
        <meta-data android:name="com.google.android.wearable.standalone" android:value="true" />
        <activity android:name=".MainActivity" android:exported="true"
            android:taskAffinity="">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Compass</string>
</resources>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml` (a ring with a red north needle and a white south needle):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:strokeColor="#FFFFFF" android:strokeWidth="3" android:fillColor="#00000000"
        android:pathData="M54,54m-26,0a26,26 0,1 1,52 0a26,26 0,1 1,-52 0" />
    <path android:fillColor="#F44336" android:pathData="M54,32L61,54L47,54Z" />
    <path android:fillColor="#FFFFFF" android:pathData="M54,76L61,54L47,54Z" />
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/black" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`app/src/main/java/com/infinite8looper/compass/MainActivity.kt`:
```kotlin
package com.infinite8looper.compass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("Compass", color = Color.White)
            }
        }
    }
}
```

- [ ] **Step 5: Write the emulator and screenshot scripts**

`scripts/emulator.sh`:
```bash
#!/bin/zsh
# Boots a Wear OS emulator (default Wear_OS_Large_Round) and waits for it, up to 5 minutes.
ADB=~/Library/Android/sdk/platform-tools/adb
EMU=~/Library/Android/sdk/emulator/emulator
AVD=${1:-Wear_OS_Large_Round}
if ! $ADB devices | grep -q "emulator-.*device"; then
  nohup $EMU -avd "$AVD" -no-snapshot-save > /tmp/compass-emulator.log 2>&1 &
fi
deadline=$(( $(date +%s) + 300 ))
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  if [ $(date +%s) -gt $deadline ]; then echo "emulator did not boot in 300 s"; exit 1; fi
  sleep 3
done
echo "emulator ready: $($ADB shell getprop ro.build.version.sdk | tr -d '\r')"
```

`scripts/screenshot.sh`:
```bash
#!/bin/zsh
# Usage: scripts/screenshot.sh <name>   ->  docs/screenshots/<name>.png
ADB=~/Library/Android/sdk/platform-tools/adb
mkdir -p docs/screenshots
$ADB exec-out screencap -p > "docs/screenshots/$1.png" && echo "wrote docs/screenshots/$1.png"
```

```bash
chmod +x scripts/emulator.sh scripts/screenshot.sh
```

- [ ] **Step 6: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists. If it fails on a toolchain incompatibility, read the error, fix the build files, and record the change in the notes.

- [ ] **Step 7: Install, launch and look**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
scripts/emulator.sh
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n com.infinite8looper.compass/.MainActivity
sleep 3 && scripts/screenshot.sh task1-launch
```
Expected: open `docs/screenshots/task1-launch.png`; it shows a black round screen with "Compass". Delete the screenshot afterwards.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties .gitignore gradlew gradle app scripts
git commit -m "Scaffold Wear OS app module and verify the toolchain builds"
```

---

### Task 2: Navigation math and target logic (pure Kotlin)

**Files:**
- Create: `app/src/main/java/com/infinite8looper/compass/nav/Navigation.kt`, `app/src/main/java/com/infinite8looper/compass/nav/Targets.kt`
- Test: `app/src/test/java/com/infinite8looper/compass/nav/NavigationTest.kt`, `app/src/test/java/com/infinite8looper/compass/nav/TargetsTest.kt`

**Interfaces:**
- Consumes: `Waypoint` from Task 3. To keep this task self-contained, `Targets.kt` takes a small `WaypointInfo` and Task 3 adds the mapping.
- Produces (all in package `com.infinite8looper.compass.nav`):
  - `data class GeoPoint(val lat: Double, val lon: Double)`
  - `data class Fix(val point: GeoPoint, val accuracyM: Float, val altitudeM: Float, val elapsedMs: Long)`
  - `enum class NorthRef { TRUE, MAGNETIC }`, `enum class Units { METRIC, IMPERIAL }`
  - `fun normalizeDeg(d: Float): Float`, `fun shortestDelta(from: Float, to: Float): Float`
  - `fun smoothAngle(prev: Float, target: Float, dtSeconds: Float, tau: Float = 0.12f): Float`
  - `fun bearingDeg(from: GeoPoint, to: GeoPoint): Float`, `fun distanceMeters(from: GeoPoint, to: GeoPoint): Double`
  - `data class DisplayHeading(val deg: Float, val isMagnetic: Boolean)`, `fun displayHeading(magneticDeg: Float, declination: Float?, north: NorthRef): DisplayHeading`
  - `fun dialAngle(trueBearing: Float, declination: Float?, north: NorthRef): Float`
  - `fun formatDistance(meters: Double, units: Units): String`, `fun hasArrived(meters: Double, units: Units): Boolean`
  - `fun hueForIndex(index: Int): Float`
  - `fun shouldHoldFlat(pitchDeg: Float): Boolean`, `fun isFieldNormal(microTesla: Float): Boolean`, `fun accuracyLabel(accuracy: Int?): String`, `fun isStale(nowMs: Long, fixMs: Long?): Boolean`
  - `suspend fun acquireFix(fixes: Flow<Fix>, goodEnoughM: Float = 15f, timeoutMs: Long = 20_000, onProgress: (Fix) -> Unit = {}): Fix?`
  - `data class WaypointInfo(val id: Long, val name: String, val point: GeoPoint, val hue: Float, val isTarget: Boolean)`
  - `data class TargetUi(val id: Long, val name: String, val hue: Float, val dialAngleDeg: Float?, val distanceM: Double?, val focused: Boolean)`
  - `fun resolveFocus(targetIds: List<Long>, focusedId: Long?): Long?`, `fun stepFocus(targetIds: List<Long>, current: Long?, step: Int): Long?`
  - `fun buildTargets(waypoints: List<WaypointInfo>, focusedId: Long?, position: GeoPoint?, declination: Float?, north: NorthRef): List<TargetUi>`
  - `fun cleanName(input: String?, fallback: String): String`

- [ ] **Step 1: Write the failing tests**

`NavigationTest.kt`:
```kotlin
package com.infinite8looper.compass.nav

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
    private val kc = GeoPoint(39.099912, -94.581213)   // Kansas City
    private val stl = GeoPoint(38.627089, -90.200203)  // St. Louis

    @Test fun normalizeWraps() {
        assertEquals(350f, normalizeDeg(-10f), 1e-4f)
        assertEquals(10f, normalizeDeg(370f), 1e-4f)
        assertEquals(0f, normalizeDeg(360f), 1e-4f)
    }
    @Test fun shortestDeltaCrossesZero() {
        assertEquals(20f, shortestDelta(350f, 10f), 1e-4f)
        assertEquals(-20f, shortestDelta(10f, 350f), 1e-4f)
        assertEquals(180f, kotlin.math.abs(shortestDelta(0f, 180f)), 1e-4f)
    }
    @Test fun smoothingGoesTheShortWay() {
        val s = smoothAngle(350f, 10f, 0.05f)
        assertTrue("expected between 350 and 10 through 0, got $s", s > 350f || s < 10f)
    }
    @Test fun smoothingConvergesAndHolds() {
        assertEquals(10f, smoothAngle(350f, 10f, 10f), 0.01f)
        assertEquals(350f, smoothAngle(350f, 10f, 0f), 1e-4f)      // zero elapsed time
        assertEquals(350f, smoothAngle(350f, Float.NaN, 0.02f), 1e-4f) // NaN sensor value
        assertEquals(350f, smoothAngle(350f, 10f, -1f), 1e-4f)
    }
    @Test fun bearingCardinals() {
        assertEquals(0f, bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 0.01f)
        assertEquals(90f, bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 0.01f)
        assertEquals(180f, bearingDeg(GeoPoint(1.0, 0.0), GeoPoint(0.0, 0.0)), 0.01f)
        assertEquals(270f, bearingDeg(GeoPoint(0.0, 1.0), GeoPoint(0.0, 0.0)), 0.01f)
    }
    @Test fun bearingAndDistanceReferencePair() {
        assertEquals(96.51f, bearingDeg(kc, stl), 0.1f)
        assertEquals(382_900.0, distanceMeters(kc, stl), 1000.0)
    }
    @Test fun oneDegreeOfLatitude() {
        assertEquals(111_195.0, distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 1.0)
    }
    @Test fun samePointIsFiniteAndArrived() {
        val p = GeoPoint(43.7022, -72.2896)
        assertEquals(0.0, distanceMeters(p, p), 1e-6)
        assertFalse(bearingDeg(p, p).isNaN())
        assertTrue(hasArrived(distanceMeters(p, p), Units.METRIC))
    }
    @Test fun antimeridianTakesShortWay() {
        val a = GeoPoint(0.0, 179.5); val b = GeoPoint(0.0, -179.5)
        assertEquals(111_195.0, distanceMeters(a, b), 5.0)
        assertEquals(90f, bearingDeg(a, b), 0.01f)
    }
    @Test fun nearPoleIsFinite() {
        val d = distanceMeters(GeoPoint(89.9, 0.0), GeoPoint(89.9, 180.0))
        assertEquals(2 * 0.1 * 111_195.0, d, 50.0)
        assertFalse(bearingDeg(GeoPoint(90.0, 0.0), GeoPoint(0.0, 0.0)).isNaN())
    }
    @Test fun displayHeadingAppliesDeclination() {
        assertEquals(DisplayHeading(346f, false), displayHeading(0f, -14f, NorthRef.TRUE))
        assertEquals(DisplayHeading(0f, true), displayHeading(0f, -14f, NorthRef.MAGNETIC))
        assertEquals(DisplayHeading(0f, true), displayHeading(0f, null, NorthRef.TRUE))
    }
    @Test fun dialAngleMatchesReference() {
        assertEquals(90f, dialAngle(90f, -14f, NorthRef.TRUE), 1e-4f)
        assertEquals(104f, dialAngle(90f, -14f, NorthRef.MAGNETIC), 1e-4f)
        assertEquals(90f, dialAngle(90f, null, NorthRef.TRUE), 1e-4f)
    }
    @Test fun distanceFormatting() {
        assertEquals("420 m", formatDistance(420.4, Units.METRIC))
        assertEquals("999 m", formatDistance(999.4, Units.METRIC))
        assertEquals("1.0 km", formatDistance(1000.0, Units.METRIC))
        assertEquals("12.3 km", formatDistance(12_345.0, Units.METRIC))
        assertEquals("328 ft", formatDistance(100.0, Units.IMPERIAL))
        assertEquals("0.2 mi", formatDistance(305.0, Units.IMPERIAL))   // 1000.7 ft
        assertEquals("1.0 mi", formatDistance(1609.344, Units.IMPERIAL))
    }
    @Test fun arrivalThresholds() {
        assertTrue(hasArrived(9.99, Units.METRIC)); assertFalse(hasArrived(10.0, Units.METRIC))
        assertTrue(hasArrived(9.0, Units.IMPERIAL)); assertFalse(hasArrived(9.2, Units.IMPERIAL)) // 30 ft = 9.144 m
    }
    @Test fun huesStayApartForManyWaypoints() {
        val hues = (0 until 500).map { hueForIndex(it) }
        assertTrue(hues.all { it >= 0f && it < 360f })
        for (i in 1 until hues.size) {
            assertTrue(kotlin.math.abs(shortestDelta(hues[i - 1], hues[i])) > 100f)
        }
        val first50 = hues.take(50).sorted()
        val minGap = first50.zipWithNext { a, b -> b - a }.min()
        assertTrue("min gap $minGap", minGap > 1f)
    }
    @Test fun sensorHelpers() {
        assertTrue(shouldHoldFlat(75f)); assertFalse(shouldHoldFlat(40f))
        assertTrue(isFieldNormal(48f)); assertFalse(isFieldNormal(120f)); assertFalse(isFieldNormal(10f))
        assertEquals("High", accuracyLabel(3)); assertEquals("Medium", accuracyLabel(2))
        assertEquals("Low", accuracyLabel(1)); assertEquals("Unreliable", accuracyLabel(0))
        assertEquals("Unknown", accuracyLabel(null))
        assertTrue(isStale(30_000, 5_000)); assertFalse(isStale(30_000, 15_000)); assertFalse(isStale(30_000, null))
    }
    private fun fix(acc: Float) = Fix(GeoPoint(1.0, 2.0), acc, 0f, 0L)

    @Test fun acquireReturnsFirstGoodFix() = runTest {
        val seen = mutableListOf<Float>()
        val got = acquireFix(flow { emit(fix(40f)); delay(1000); emit(fix(12f)); delay(1000); emit(fix(3f)) }) { seen += it.accuracyM }
        assertEquals(12f, got!!.accuracyM, 0f)
        assertEquals(listOf(40f, 12f), seen)
    }
    @Test fun acquireFallsBackToBestAfterTimeout() = runTest {
        val got = acquireFix(flow { emit(fix(40f)); delay(5000); emit(fix(25f)); delay(60_000); emit(fix(5f)) })
        assertEquals(25f, got!!.accuracyM, 0f)
    }
    @Test fun acquireReturnsNullWithNoFix() = runTest {
        assertNull(acquireFix(flow { delay(60_000) }))
        assertNull(acquireFix(flow { }))
    }
}
```

`TargetsTest.kt`:
```kotlin
package com.infinite8looper.compass.nav

import org.junit.Assert.*
import org.junit.Test

class TargetsTest {
    private val here = GeoPoint(0.0, 0.0)
    private fun wp(id: Long, lat: Double, lon: Double, target: Boolean) =
        WaypointInfo(id, "W$id", GeoPoint(lat, lon), hueForIndex(id.toInt()), target)
    private val all = listOf(wp(3, 0.0, 1.0, true), wp(2, 1.0, 0.0, false), wp(1, -1.0, 0.0, true))

    @Test fun onlyTargetsAreBuiltAndFocusIsMarked() {
        val t = buildTargets(all, focusedId = 1, position = here, declination = -14f, north = NorthRef.TRUE)
        assertEquals(listOf(3L, 1L), t.map { it.id })
        assertEquals(listOf(false, true), t.map { it.focused })
        assertEquals(90f, t[0].dialAngleDeg!!, 0.01f)
        assertEquals(180f, t[1].dialAngleDeg!!, 0.01f)
        assertEquals(111_195.0, t[0].distanceM!!, 1.0)
    }
    @Test fun magneticDialShiftsMarkers() {
        val t = buildTargets(all, 3, here, -14f, NorthRef.MAGNETIC)
        assertEquals(104f, t[0].dialAngleDeg!!, 0.01f)
    }
    @Test fun noPositionMeansNoAngleOrDistance() {
        val t = buildTargets(all, 3, null, null, NorthRef.TRUE)
        assertEquals(2, t.size); assertNull(t[0].dialAngleDeg); assertNull(t[0].distanceM)
    }
    @Test fun focusFallsBackWhenMissing() {
        val t = buildTargets(all, focusedId = 2, position = here, declination = null, north = NorthRef.TRUE)
        assertTrue(t[0].focused); assertFalse(t[1].focused)
        assertEquals(3L, resolveFocus(listOf(3, 1), 99)); assertEquals(1L, resolveFocus(listOf(3, 1), 1))
        assertNull(resolveFocus(emptyList(), 1))
    }
    @Test fun allWaypointsCanBeTargets() {
        val many = (1L..300L).map { wp(it, it / 1000.0, 0.0, true) }
        assertEquals(300, buildTargets(many, 1, here, 0f, NorthRef.TRUE).size)
    }
    @Test fun steppingWrapsBothWays() {
        val ids = listOf(5L, 4L, 2L)
        assertEquals(4L, stepFocus(ids, 5, 1)); assertEquals(5L, stepFocus(ids, 2, 1))
        assertEquals(2L, stepFocus(ids, 5, -1)); assertEquals(5L, stepFocus(ids, null, 1))
        assertEquals(5L, stepFocus(ids, 99, -1)); assertNull(stepFocus(emptyList(), 5, 1))
    }
    @Test fun namesAreCleaned() {
        assertEquals("Camp", cleanName("  Camp  ", "Old"))
        assertEquals("Old", cleanName("", "Old")); assertEquals("Old", cleanName("   ", "Old"))
        assertEquals("Old", cleanName(null, "Old"))
        assertEquals("Trail head", cleanName("Trail \n  head", "Old"))
        assertEquals(40, cleanName("x".repeat(200), "Old").length)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew testDebugUnitTest`
Expected: compilation fails with unresolved references such as `GeoPoint`.

- [ ] **Step 3: Write `Navigation.kt`**

**Owner contribution:** `smoothAngle` (how quickly the dial follows the sensor) and `hueForIndex` (how colors are spread). Offer both before using these.

```kotlin
package com.infinite8looper.compass.nav

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.*

data class GeoPoint(val lat: Double, val lon: Double)
data class Fix(val point: GeoPoint, val accuracyM: Float, val altitudeM: Float, val elapsedMs: Long)
enum class NorthRef { TRUE, MAGNETIC }
enum class Units { METRIC, IMPERIAL }
data class DisplayHeading(val deg: Float, val isMagnetic: Boolean)

private const val EARTH_RADIUS_M = 6_371_008.8
private const val FEET_PER_METER = 3.280839895
private const val METERS_PER_MILE = 1609.344
const val STALE_AFTER_MS = 20_000L

fun normalizeDeg(d: Float): Float = ((d % 360f) + 360f) % 360f

/** Signed smallest rotation from [from] to [to], in -180..180. */
fun shortestDelta(from: Float, to: Float): Float {
    val d = normalizeDeg(to - from)
    return if (d > 180f) d - 360f else d
}

/** Exponential approach to [target] with time constant [tau] seconds, along the shorter arc. */
fun smoothAngle(prev: Float, target: Float, dtSeconds: Float, tau: Float = 0.12f): Float {
    if (target.isNaN() || dtSeconds.isNaN() || dtSeconds <= 0f) return prev
    val alpha = 1f - exp(-dtSeconds / tau)
    return normalizeDeg(prev + shortestDelta(prev, target) * alpha)
}

/** Initial great-circle bearing from true north, 0..360. */
fun bearingDeg(from: GeoPoint, to: GeoPoint): Float {
    val p1 = Math.toRadians(from.lat); val p2 = Math.toRadians(to.lat)
    val dl = Math.toRadians(to.lon - from.lon)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return normalizeDeg(Math.toDegrees(atan2(y, x)).toFloat())
}

/** Haversine distance on the mean-radius sphere. */
fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
    val p1 = Math.toRadians(from.lat); val p2 = Math.toRadians(to.lat)
    val dp = p2 - p1; val dl = Math.toRadians(to.lon - from.lon)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

/** Declination is positive east: true = magnetic + declination. */
fun displayHeading(magneticDeg: Float, declination: Float?, north: NorthRef): DisplayHeading =
    if (north == NorthRef.TRUE && declination != null) DisplayHeading(normalizeDeg(magneticDeg + declination), false)
    else DisplayHeading(normalizeDeg(magneticDeg), true)

/** Where a true bearing sits on a dial whose zero is the displayed north. */
fun dialAngle(trueBearing: Float, declination: Float?, north: NorthRef): Float =
    if (north == NorthRef.TRUE || declination == null) normalizeDeg(trueBearing)
    else normalizeDeg(trueBearing - declination)

fun formatDistance(meters: Double, units: Units): String = when (units) {
    Units.METRIC ->
        if (meters.roundToInt() < 1000) "${meters.roundToInt()} m"
        else String.format(Locale.US, "%.1f km", meters / 1000.0)
    Units.IMPERIAL -> {
        val feet = meters * FEET_PER_METER
        if (feet.roundToInt() < 1000) "${feet.roundToInt()} ft"
        else String.format(Locale.US, "%.1f mi", meters / METERS_PER_MILE)
    }
}

fun hasArrived(meters: Double, units: Units): Boolean = when (units) {
    Units.METRIC -> meters < 10.0
    Units.IMPERIAL -> meters * FEET_PER_METER < 30.0
}

/** Golden-angle steps around the hue wheel: neighbors are far apart and no hue repeats. */
fun hueForIndex(index: Int): Float = normalizeDeg(index * 137.508f)

fun shouldHoldFlat(pitchDeg: Float): Boolean = pitchDeg > 70f
fun isFieldNormal(microTesla: Float): Boolean = microTesla in 25f..65f
fun accuracyLabel(accuracy: Int?): String = when (accuracy) {
    3 -> "High"; 2 -> "Medium"; 1 -> "Low"; 0 -> "Unreliable"; else -> "Unknown"
}
fun isStale(nowMs: Long, fixMs: Long?): Boolean = fixMs != null && nowMs - fixMs > STALE_AFTER_MS

/**
 * Collects [fixes] until one is at least [goodEnoughM] accurate, or [timeoutMs] passes.
 * On timeout, or if the flow ends, returns the most accurate fix seen, or null if none arrived.
 */
suspend fun acquireFix(
    fixes: Flow<Fix>, goodEnoughM: Float = 15f, timeoutMs: Long = 20_000, onProgress: (Fix) -> Unit = {},
): Fix? {
    var best: Fix? = null
    val good = withTimeoutOrNull(timeoutMs) {
        fixes.firstOrNull { f ->
            if (best == null || f.accuracyM < best!!.accuracyM) best = f
            onProgress(f)
            f.accuracyM <= goodEnoughM
        }
    }
    return good ?: best
}
```

In `formatDistance`, 305 m is 1000.7 ft, which rounds to 1001 and so prints as miles (`0.2 mi`); the test pins this boundary.

- [ ] **Step 4: Write `Targets.kt`**

```kotlin
package com.infinite8looper.compass.nav

data class WaypointInfo(val id: Long, val name: String, val point: GeoPoint, val hue: Float, val isTarget: Boolean)

data class TargetUi(
    val id: Long, val name: String, val hue: Float,
    val dialAngleDeg: Float?, val distanceM: Double?, val focused: Boolean,
)

fun resolveFocus(targetIds: List<Long>, focusedId: Long?): Long? =
    if (focusedId != null && focusedId in targetIds) focusedId else targetIds.firstOrNull()

fun stepFocus(targetIds: List<Long>, current: Long?, step: Int): Long? {
    if (targetIds.isEmpty()) return null
    val i = targetIds.indexOf(current)
    return if (i < 0) targetIds.first() else targetIds[(i + step).mod(targetIds.size)]
}

/** Targets in the order given (the store returns newest first). */
fun buildTargets(
    waypoints: List<WaypointInfo>, focusedId: Long?, position: GeoPoint?, declination: Float?, north: NorthRef,
): List<TargetUi> {
    val targets = waypoints.filter { it.isTarget }
    val focus = resolveFocus(targets.map { it.id }, focusedId)
    return targets.map { w ->
        TargetUi(
            id = w.id, name = w.name, hue = w.hue,
            dialAngleDeg = position?.let { dialAngle(bearingDeg(it, w.point), declination, north) },
            distanceM = position?.let { distanceMeters(it, w.point) },
            focused = w.id == focus,
        )
    }
}

fun cleanName(input: String?, fallback: String): String {
    val cleaned = input?.trim()?.replace(Regex("\\s+"), " ")?.take(40).orEmpty()
    return cleaned.ifEmpty { fallback }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew testDebugUnitTest`
Expected: all tests in `NavigationTest` and `TargetsTest` pass. If a reference-value test fails, the code is wrong, not the test; the expected values were recomputed independently when the plan was written (recorded in `notes/2026-10-11-session-notes.md`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/infinite8looper/compass/nav app/src/test
git commit -m "Add navigation math, target logic and their unit tests"
```

---

### Task 3: Waypoint database

**Files:**
- Create: `app/src/main/java/com/infinite8looper/compass/data/Waypoint.kt`
- Test: `app/src/androidTest/java/com/infinite8looper/compass/WaypointDatabaseTest.kt`

**Interfaces:**
- Consumes: `GeoPoint`, `WaypointInfo` from Task 2.
- Produces (package `com.infinite8looper.compass.data`):
  - `data class Waypoint(val id: Long = 0, val name: String, val lat: Double, val lon: Double, val accuracyM: Float, val createdAt: Long, val hue: Float, val isTarget: Boolean)` with `fun toInfo(): WaypointInfo`
  - `interface WaypointDao { fun observeAll(): Flow<List<Waypoint>>; suspend fun get(id: Long): Waypoint?; suspend fun insert(w: Waypoint): Long; suspend fun update(w: Waypoint); suspend fun delete(id: Long); suspend fun setTarget(id: Long, isTarget: Boolean); suspend fun setAllTargets(isTarget: Boolean) }`
  - `abstract class WaypointDatabase : RoomDatabase { abstract fun waypoints(): WaypointDao; companion: fun get(context: Context): WaypointDatabase; fun open(context: Context, name: String): WaypointDatabase }`

- [ ] **Step 1: Write the failing instrumented test**

It uses a real database file on the emulator, removed after each test.

```kotlin
package com.infinite8looper.compass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaypointDatabaseTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "test-waypoints.db"
    private lateinit var db: WaypointDatabase

    @Before fun open() { ctx.deleteDatabase(name); db = WaypointDatabase.open(ctx, name) }
    @After fun close() { db.close(); ctx.deleteDatabase(name) }

    private fun wp(n: String, t: Long, target: Boolean = true) =
        Waypoint(name = n, lat = 43.7022, lon = -72.2896, accuracyM = 8f, createdAt = t, hue = 120f, isTarget = target)

    @Test fun freshDatabaseIsEmpty() = runBlocking {
        assertTrue(db.waypoints().observeAll().first().isEmpty())
    }
    @Test fun insertReadBackExactValuesNewestFirst() = runBlocking {
        val a = db.waypoints().insert(wp("A", 1000)); val b = db.waypoints().insert(wp("B", 2000))
        val all = db.waypoints().observeAll().first()
        assertEquals(listOf(b, a), all.map { it.id })
        val got = db.waypoints().get(a)!!
        assertEquals("A", got.name); assertEquals(43.7022, got.lat, 0.0); assertEquals(-72.2896, got.lon, 0.0)
        assertEquals(8f, got.accuracyM, 0f); assertEquals(120f, got.hue, 0f); assertTrue(got.isTarget)
        assertEquals(got.id, got.toInfo().id); assertEquals(43.7022, got.toInfo().point.lat, 0.0)
    }
    @Test fun duplicateNamesGetDistinctIds() = runBlocking {
        val a = db.waypoints().insert(wp("Same", 1)); val b = db.waypoints().insert(wp("Same", 1))
        assertNotEquals(a, b); assertEquals(2, db.waypoints().observeAll().first().size)
    }
    @Test fun idsAreNotReusedAfterDelete() = runBlocking {
        val a = db.waypoints().insert(wp("A", 1)); db.waypoints().delete(a)
        assertTrue(db.waypoints().insert(wp("B", 2)) > a)
    }
    @Test fun updateRenamesAndRecolors() = runBlocking {
        val id = db.waypoints().insert(wp("A", 1))
        db.waypoints().update(db.waypoints().get(id)!!.copy(name = "Camp", hue = 300f))
        val got = db.waypoints().get(id)!!
        assertEquals("Camp", got.name); assertEquals(300f, got.hue, 0f)
    }
    @Test fun targetsToggleSinglyAndAllAtOnce() = runBlocking {
        val a = db.waypoints().insert(wp("A", 1, target = false)); db.waypoints().insert(wp("B", 2, target = false))
        db.waypoints().setTarget(a, true)
        assertEquals(1, db.waypoints().observeAll().first().count { it.isTarget })
        db.waypoints().setAllTargets(true)
        assertTrue(db.waypoints().observeAll().first().all { it.isTarget })
        db.waypoints().setAllTargets(false)
        assertTrue(db.waypoints().observeAll().first().none { it.isTarget })
    }
    @Test fun deleteMissingIdIsHarmless() = runBlocking {
        db.waypoints().delete(12345); assertNull(db.waypoints().get(12345))
    }
    @Test fun dataSurvivesReopen() = runBlocking {
        val id = db.waypoints().insert(wp("Keep", 1)); db.close()
        db = WaypointDatabase.open(ctx, name)
        assertEquals("Keep", db.waypoints().get(id)!!.name)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts/emulator.sh && ./gradlew connectedDebugAndroidTest`
Expected: compilation fails with unresolved reference `WaypointDatabase`.

- [ ] **Step 3: Write `Waypoint.kt`**

```kotlin
package com.infinite8looper.compass.data

import android.content.Context
import androidx.room.*
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.WaypointInfo
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "waypoints")
data class Waypoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val createdAt: Long,
    val hue: Float,
    val isTarget: Boolean,
) {
    fun toInfo() = WaypointInfo(id, name, GeoPoint(lat, lon), hue, isTarget)
}

@Dao
interface WaypointDao {
    @Query("SELECT * FROM waypoints ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<Waypoint>>

    @Query("SELECT * FROM waypoints WHERE id = :id")
    suspend fun get(id: Long): Waypoint?

    @Insert suspend fun insert(w: Waypoint): Long
    @Update suspend fun update(w: Waypoint)

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE waypoints SET isTarget = :isTarget WHERE id = :id")
    suspend fun setTarget(id: Long, isTarget: Boolean)

    @Query("UPDATE waypoints SET isTarget = :isTarget")
    suspend fun setAllTargets(isTarget: Boolean)
}

@Database(entities = [Waypoint::class], version = 1, exportSchema = false)
abstract class WaypointDatabase : RoomDatabase() {
    abstract fun waypoints(): WaypointDao

    companion object {
        @Volatile private var instance: WaypointDatabase? = null
        fun open(context: Context, name: String): WaypointDatabase =
            Room.databaseBuilder(context.applicationContext, WaypointDatabase::class.java, name).build()
        fun get(context: Context): WaypointDatabase =
            instance ?: synchronized(this) { instance ?: open(context, "waypoints.db").also { instance = it } }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest connectedDebugAndroidTest`
Expected: all unit tests and all eight `WaypointDatabaseTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/infinite8looper/compass/data/Waypoint.kt app/src/androidTest
git commit -m "Add Room waypoint store with instrumented tests"
```

---

### Task 4: Settings store

**Files:**
- Create: `app/src/main/java/com/infinite8looper/compass/data/SettingsStore.kt`
- Test: `app/src/androidTest/java/com/infinite8looper/compass/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: `NorthRef`, `Units`, `GeoPoint` from Task 2.
- Produces (package `com.infinite8looper.compass.data`):
  - `data class Settings(val north: NorthRef, val units: Units, val focusedId: Long?, val lastPosition: GeoPoint?, val lastAltitudeM: Float?)`
  - `fun defaultUnits(locale: Locale): Units`
  - `class SettingsStore(context: Context, fileName: String) { val settings: Flow<Settings>; suspend fun setNorth(n: NorthRef); suspend fun setUnits(u: Units); suspend fun setFocused(id: Long?); suspend fun setLastPosition(p: GeoPoint, altitudeM: Float); suspend fun nextWaypointNumber(): Int; companion: fun get(context: Context): SettingsStore }`

- [ ] **Step 1: Write the failing instrumented test**

```kotlin
package com.infinite8looper.compass

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.defaultUnits
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.Units
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun withStore(block: suspend (SettingsStore, String) -> Unit) = runBlocking {
        val file = "test-settings-${UUID.randomUUID()}"
        try { block(SettingsStore(ctx, file), file) } finally { ctx.preferencesDataStoreFile(file).delete() }
    }

    @Test fun defaultsWhenNoFileExists() = withStore { s, _ ->
        val v = s.settings.first()
        assertEquals(NorthRef.TRUE, v.north); assertEquals(defaultUnits(Locale.getDefault()), v.units)
        assertNull(v.focusedId); assertNull(v.lastPosition); assertNull(v.lastAltitudeM)
    }
    @Test fun valuesRoundTrip() = withStore { s, _ ->
        s.setNorth(NorthRef.MAGNETIC); s.setUnits(Units.IMPERIAL); s.setFocused(7)
        s.setLastPosition(GeoPoint(43.7022, -72.2896), 160f)
        val v = s.settings.first()
        assertEquals(NorthRef.MAGNETIC, v.north); assertEquals(Units.IMPERIAL, v.units); assertEquals(7L, v.focusedId)
        assertEquals(43.7022, v.lastPosition!!.lat, 1e-9); assertEquals(-72.2896, v.lastPosition!!.lon, 1e-9)
        assertEquals(160f, v.lastAltitudeM!!, 0f)
    }
    @Test fun focusCanBeCleared() = withStore { s, _ ->
        s.setFocused(7); s.setFocused(null); assertNull(s.settings.first().focusedId)
    }
    @Test fun waypointNumbersCountUpAndNeverRepeat() = withStore { s, _ ->
        assertEquals(listOf(1, 2, 3), listOf(s.nextWaypointNumber(), s.nextWaypointNumber(), s.nextWaypointNumber()))
    }
    @Test fun unitsDefaultFromLocale() {
        assertEquals(Units.IMPERIAL, defaultUnits(Locale.US)); assertEquals(Units.IMPERIAL, defaultUnits(Locale.UK))
        assertEquals(Units.METRIC, defaultUnits(Locale.FRANCE)); assertEquals(Units.METRIC, defaultUnits(Locale.CANADA))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew connectedDebugAndroidTest`
Expected: compilation fails with unresolved reference `SettingsStore`.

- [ ] **Step 3: Write `SettingsStore.kt`**

```kotlin
package com.infinite8looper.compass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.Units
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

data class Settings(
    val north: NorthRef, val units: Units, val focusedId: Long?,
    val lastPosition: GeoPoint?, val lastAltitudeM: Float?,
)

fun defaultUnits(locale: Locale): Units =
    if (locale.country in setOf("US", "GB", "LR", "MM")) Units.IMPERIAL else Units.METRIC

class SettingsStore(context: Context, fileName: String) {
    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile(fileName) }

    val settings: Flow<Settings> = store.data.map { p ->
        val lat = p[LAT]; val lon = p[LON]
        Settings(
            north = if (p[MAGNETIC] == true) NorthRef.MAGNETIC else NorthRef.TRUE,
            units = when (p[IMPERIAL]) { true -> Units.IMPERIAL; false -> Units.METRIC; null -> defaultUnits(Locale.getDefault()) },
            focusedId = p[FOCUSED],
            lastPosition = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
            lastAltitudeM = p[ALT],
        )
    }

    suspend fun setNorth(n: NorthRef) { store.edit { it[MAGNETIC] = n == NorthRef.MAGNETIC } }
    suspend fun setUnits(u: Units) { store.edit { it[IMPERIAL] = u == Units.IMPERIAL } }
    suspend fun setFocused(id: Long?) { store.edit { if (id == null) it.remove(FOCUSED) else it[FOCUSED] = id } }
    suspend fun setLastPosition(p: GeoPoint, altitudeM: Float) {
        store.edit { it[LAT] = p.lat; it[LON] = p.lon; it[ALT] = altitudeM }
    }
    suspend fun nextWaypointNumber(): Int {
        var n = 0
        store.edit { n = (it[COUNTER] ?: 0) + 1; it[COUNTER] = n }
        return n
    }

    companion object {
        private val MAGNETIC = booleanPreferencesKey("magnetic_north")
        private val IMPERIAL = booleanPreferencesKey("imperial")
        private val FOCUSED = longPreferencesKey("focused_id")
        private val LAT = doublePreferencesKey("last_lat")
        private val LON = doublePreferencesKey("last_lon")
        private val ALT = floatPreferencesKey("last_alt")
        private val COUNTER = intPreferencesKey("waypoint_counter")

        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) { instance ?: SettingsStore(context, "settings").also { instance = it } }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest connectedDebugAndroidTest`
Expected: all pass, including the five `SettingsStoreTest` tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/infinite8looper/compass/data/SettingsStore.kt app/src/androidTest
git commit -m "Add DataStore settings store with instrumented tests"
```

---

### Task 5: Heading source

**Files:**
- Create: `app/src/main/java/com/infinite8looper/compass/sensors/HeadingSource.kt`
- Test: `app/src/androidTest/java/com/infinite8looper/compass/HeadingSourceTest.kt`

**Interfaces:**
- Consumes: `normalizeDeg` from Task 2.
- Produces (package `com.infinite8looper.compass.sensors`):
  - `data class HeadingReading(val magneticDeg: Float, val pitchDeg: Float, val accuracy: Int?, val fieldMicroTesla: Float?)`
  - `class HeadingSource(context: Context) { val isSupported: Boolean; fun readings(): Flow<HeadingReading> }`

`magneticDeg` is the direction of the watch's 12 o'clock edge relative to magnetic north. `accuracy` is the magnetometer's `SensorManager.SENSOR_STATUS_*` value (0 to 3), or null before the first report.

- [ ] **Step 1: Write the failing instrumented test**

It reads the emulator's real (simulated) sensor through the real `SensorManager`.

```kotlin
package com.infinite8looper.compass

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinite8looper.compass.sensors.HeadingSource
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadingSourceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun deviceHasTheSensor() { assertTrue(HeadingSource(ctx).isSupported) }

    @Test fun readingsArriveAndAreInRange() = runBlocking {
        val got = withTimeout(10_000) { HeadingSource(ctx).readings().take(5).toList() }
        assertEquals(5, got.size)
        for (r in got) {
            assertTrue("heading ${r.magneticDeg}", r.magneticDeg >= 0f && r.magneticDeg < 360f)
            assertTrue("pitch ${r.pitchDeg}", r.pitchDeg in 0f..90f)
            r.accuracy?.let { assertTrue(it in 0..3) }
            r.fieldMicroTesla?.let { assertTrue(it > 0f) }
        }
    }
    @Test fun collectingTwiceWorks() = runBlocking {
        val s = HeadingSource(ctx)
        withTimeout(10_000) { s.readings().take(1).toList() }
        assertEquals(1, withTimeout(10_000) { s.readings().take(1).toList() }.size)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew connectedDebugAndroidTest`
Expected: compilation fails with unresolved reference `HeadingSource`.

- [ ] **Step 3: Write `HeadingSource.kt`**

```kotlin
package com.infinite8looper.compass.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.infinite8looper.compass.nav.normalizeDeg
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs
import kotlin.math.sqrt

data class HeadingReading(val magneticDeg: Float, val pitchDeg: Float, val accuracy: Int?, val fieldMicroTesla: Float?)

class HeadingSource(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotation: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnet: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val isSupported: Boolean get() = rotation != null

    fun readings(): Flow<HeadingReading> = callbackFlow {
        val rot = rotation
        if (rot == null) { close(); return@callbackFlow }
        val matrix = FloatArray(9); val angles = FloatArray(3)
        var accuracy: Int? = null
        var field: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    field = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    accuracy = e.accuracy
                    return
                }
                SensorManager.getRotationMatrixFromVector(matrix, e.values)
                SensorManager.getOrientation(matrix, angles)
                val azimuth = Math.toDegrees(angles[0].toDouble()).toFloat()
                val pitch = abs(Math.toDegrees(angles[1].toDouble()).toFloat())
                if (!azimuth.isNaN() && !pitch.isNaN()) {
                    trySend(HeadingReading(normalizeDeg(azimuth), pitch, accuracy, field))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, value: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) accuracy = value
            }
        }
        sensors.registerListener(listener, rot, SensorManager.SENSOR_DELAY_GAME)
        magnet?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        awaitClose { sensors.unregisterListener(listener) }
    }.conflate()
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest connectedDebugAndroidTest`
Expected: all pass. If `deviceHasTheSensor` fails on the emulator, list its sensors with `$ADB shell dumpsys sensorservice | head -60` and report what it offers; do not weaken the test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/infinite8looper/compass/sensors/HeadingSource.kt app/src/androidTest
git commit -m "Add rotation-vector heading source with instrumented tests"
```

---

### Task 6: Location source and declination

**Files:**
- Create: `app/src/main/java/com/infinite8looper/compass/sensors/LocationSource.kt`, `scripts/geo-feed.sh`
- Test: `app/src/androidTest/java/com/infinite8looper/compass/LocationSourceTest.kt`

**Interfaces:**
- Consumes: `GeoPoint`, `Fix` from Task 2.
- Produces (package `com.infinite8looper.compass.sensors`):
  - `fun declinationAt(point: GeoPoint, altitudeM: Float, timeMs: Long): Float` (degrees, positive east)
  - `class LocationSource(context: Context, permissionCheck: () -> Boolean = default) { fun hasPermission(): Boolean; fun updates(intervalMs: Long): Flow<Fix> }`

`updates` emits only fixes younger than 10 seconds, so a cached position is never delivered. It completes without emitting when permission is missing or the request fails. `Fix.elapsedMs` is on the `SystemClock.elapsedRealtime()` clock.

- [ ] **Step 1: Write the geo feed script**

`scripts/geo-feed.sh` feeds the emulator a position once a second so the fused provider keeps producing fresh fixes:
```bash
#!/bin/zsh
# Usage: scripts/geo-feed.sh <lon> <lat> [seconds=120]
ADB=~/Library/Android/sdk/platform-tools/adb
end=$(( $(date +%s) + ${3:-120} ))
while [ $(date +%s) -lt $end ]; do
  $ADB emu geo fix "$1" "$2" > /dev/null
  sleep 1
done
```
```bash
chmod +x scripts/geo-feed.sh
```

- [ ] **Step 2: Write the failing instrumented test**

```kotlin
package com.infinite8looper.compass

import android.Manifest
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.infinite8looper.compass.nav.GeoPoint
import com.infinite8looper.compass.nav.distanceMeters
import com.infinite8looper.compass.sensors.LocationSource
import com.infinite8looper.compass.sensors.declinationAt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationSourceTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val hanover = GeoPoint(43.7022, -72.2896)

    /** Needs `scripts/geo-feed.sh -72.2896 43.7022` running. */
    @Test fun freshFixMatchesTheFedPosition() = runBlocking {
        val src = LocationSource(ctx)
        assertTrue(src.hasPermission())
        val fix = withTimeout(60_000) { src.updates(1000).first() }
        assertTrue("off by ${distanceMeters(hanover, fix.point)} m", distanceMeters(hanover, fix.point) < 100.0)
        assertTrue(fix.accuracyM > 0f)
        assertTrue(SystemClock.elapsedRealtime() - fix.elapsedMs < 15_000)
    }
    @Test fun withoutPermissionTheFlowEndsQuietly() = runBlocking {
        val src = LocationSource(ctx) { false }
        assertFalse(src.hasPermission())
        assertTrue(withTimeout(5_000) { src.updates(1000).toList() }.isEmpty())
    }
    @Test fun declinationIsWestInNewEnglandAndEastInSeattle() {
        val now = System.currentTimeMillis()
        val nh = declinationAt(hanover, 160f, now)
        assertTrue("Hanover declination $nh", nh in -16f..-11f)
        assertTrue(declinationAt(GeoPoint(47.6062, -122.3321), 50f, now) in 12f..17f)
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./gradlew connectedDebugAndroidTest`
Expected: compilation fails with unresolved reference `LocationSource`.

- [ ] **Step 4: Write `LocationSource.kt`**

```kotlin
package com.infinite8looper.compass.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.infinite8looper.compass.nav.Fix
import com.infinite8looper.compass.nav.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val MAX_FIX_AGE_MS = 10_000L

/** Degrees, positive east, from the World Magnetic Model built into Android. */
fun declinationAt(point: GeoPoint, altitudeM: Float, timeMs: Long): Float =
    GeomagneticField(point.lat.toFloat(), point.lon.toFloat(), altitudeM, timeMs).declination

class LocationSource(
    context: Context,
    private val permissionCheck: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    },
) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    fun hasPermission(): Boolean = permissionCheck()

    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long): Flow<Fix> = callbackFlow {
        if (!hasPermission()) { close(); return@callbackFlow }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    val fixMs = loc.elapsedRealtimeNanos / 1_000_000
                    if (SystemClock.elapsedRealtime() - fixMs > MAX_FIX_AGE_MS) continue
                    trySend(Fix(GeoPoint(loc.latitude, loc.longitude), loc.accuracy, loc.altitude.toFloat(), fixMs))
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { close() }
        } catch (e: SecurityException) {
            close()
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
```

`androidx.core` arrives transitively through activity-compose. If `ContextCompat` does not resolve, add `implementation("androidx.core:core-ktx")` at its current stable version, read from Google's Maven index.

- [ ] **Step 5: Run the tests with the feed running**

```bash
scripts/geo-feed.sh -72.2896 43.7022 300 &
./gradlew testDebugUnitTest connectedDebugAndroidTest
kill %1
```
Expected: all pass. If `freshFixMatchesTheFedPosition` times out, check that location is turned on in the emulator (`$ADB shell settings get secure location_mode` should not be `0`) and that Play services is present (`$ADB shell pm list packages | grep gms`). Report what is missing instead of changing the test.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/infinite8looper/compass/sensors/LocationSource.kt scripts/geo-feed.sh app/src/androidTest
git commit -m "Add fused location source and declination lookup with instrumented tests"
```

---

### Task 7: ViewModel, dial and compass screen

**Files:**
- Create: `app/src/main/java/com/infinite8looper/compass/CompassViewModel.kt`, `ui/CompassDial.kt`, `ui/CompassScreen.kt`, `ui/CompassApp.kt`
- Modify: `app/src/main/java/com/infinite8looper/compass/MainActivity.kt`

**Interfaces:**
- Consumes: everything produced by Tasks 2 to 6.
- Produces:
  - `data class NavUi(val targets: List<TargetUi>, val focused: TargetUi?, val units: Units, val north: NorthRef, val declination: Float?, val fixElapsedMs: Long?, val waypoints: List<Waypoint>)` with `companion object { val EMPTY }`
  - `class CompassViewModel(app: Application) : AndroidViewModel` with `val heading: StateFlow<HeadingReading?>`, `val nav: StateFlow<NavUi>`, `val supported: Boolean`, `val locationSource: LocationSource`, and functions `moveFocus(step: Int)`, `permissionChanged()`, `suspend fun logWaypoint(fix: Fix): Waypoint`, `setTarget(id: Long, on: Boolean)`, `setAllTargets(on: Boolean)`, `rename(id: Long, name: String?)`, `setHue(id: Long, hue: Float)`, `delete(id: Long)`, `setNorth(n: NorthRef)`, `setUnits(u: Units)`
  - `fun waypointColor(hue: Float): Color`
  - `@Composable fun CompassDial(headingProvider: () -> Float, targets: List<TargetUi>, modifier: Modifier = Modifier)`
  - `@Composable fun CompassScreen(vm: CompassViewModel, onOpenList: () -> Unit, onLog: () -> Unit, onCalibrate: () -> Unit)`
  - `@Composable fun CompassApp()` with routes `compass`, `log`, `waypoints`, `edit/{id}`, `hue/{id}`, `settings`, `calibrate`

- [ ] **Step 1: Write `CompassViewModel.kt`**

```kotlin
package com.infinite8looper.compass

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinite8looper.compass.data.SettingsStore
import com.infinite8looper.compass.data.Waypoint
import com.infinite8looper.compass.data.WaypointDatabase
import com.infinite8looper.compass.nav.*
import com.infinite8looper.compass.sensors.HeadingReading
import com.infinite8looper.compass.sensors.HeadingSource
import com.infinite8looper.compass.sensors.LocationSource
import com.infinite8looper.compass.sensors.declinationAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NavUi(
    val targets: List<TargetUi>, val focused: TargetUi?, val units: Units, val north: NorthRef,
    val declination: Float?, val fixElapsedMs: Long?, val waypoints: List<Waypoint>,
) {
    companion object {
        val EMPTY = NavUi(emptyList(), null, Units.METRIC, NorthRef.TRUE, null, null, emptyList())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CompassViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = WaypointDatabase.get(app).waypoints()
    private val settingsStore = SettingsStore.get(app)
    private val headingSource = HeadingSource(app)
    val locationSource = LocationSource(app)
    val supported: Boolean = headingSource.isSupported

    private val sharing = SharingStarted.WhileSubscribed(2_000)
    private val permissionTick = MutableStateFlow(0)

    val heading: StateFlow<HeadingReading?> = headingSource.readings().stateIn(viewModelScope, sharing, null)

    private val waypoints = dao.observeAll()
    private val hasTargets = waypoints.map { ws -> ws.any { it.isTarget } }.distinctUntilChanged()

    /** Location runs only while at least one waypoint is a target and something is collecting [nav]. */
    private val fix: Flow<Fix?> = combine(hasTargets, permissionTick) { has, _ -> has }
        .flatMapLatest { has ->
            if (has && locationSource.hasPermission()) locationSource.updates(5_000) else emptyFlow()
        }
        .onEach { settingsStore.setLastPosition(it.point, it.altitudeM) }
        .map<Fix, Fix?> { it }
        .onStart { emit(null) }

    val nav: StateFlow<NavUi> = combine(waypoints, settingsStore.settings, fix) { ws, s, f ->
        val known = f?.point ?: s.lastPosition
        val declination = known?.let { declinationAt(it, f?.altitudeM ?: s.lastAltitudeM ?: 0f, System.currentTimeMillis()) }
        val targets = buildTargets(ws.map { it.toInfo() }, s.focusedId, f?.point, declination, s.north)
        NavUi(targets, targets.firstOrNull { it.focused }, s.units, s.north, declination, f?.elapsedMs, ws)
    }.stateIn(viewModelScope, sharing, NavUi.EMPTY)

    fun permissionChanged() { permissionTick.value++ }

    fun moveFocus(step: Int) = viewModelScope.launch {
        val n = nav.value
        settingsStore.setFocused(stepFocus(n.targets.map { it.id }, n.focused?.id, step))
    }

    suspend fun logWaypoint(fix: Fix): Waypoint {
        val n = settingsStore.nextWaypointNumber()
        val w = Waypoint(
            name = "Waypoint $n", lat = fix.point.lat, lon = fix.point.lon, accuracyM = fix.accuracyM,
            createdAt = System.currentTimeMillis(), hue = hueForIndex(n - 1), isTarget = true,
        )
        settingsStore.setLastPosition(fix.point, fix.altitudeM)
        return w.copy(id = dao.insert(w))
    }

    fun setTarget(id: Long, on: Boolean) = viewModelScope.launch { dao.setTarget(id, on) }
    fun setAllTargets(on: Boolean) = viewModelScope.launch { dao.setAllTargets(on) }
    fun rename(id: Long, name: String?) = viewModelScope.launch {
        dao.get(id)?.let { dao.update(it.copy(name = cleanName(name, it.name))) }
    }
    fun setHue(id: Long, hue: Float) = viewModelScope.launch {
        dao.get(id)?.let { dao.update(it.copy(hue = normalizeDeg(hue))) }
    }
    fun delete(id: Long) = viewModelScope.launch { dao.delete(id) }
    fun setNorth(n: NorthRef) = viewModelScope.launch { settingsStore.setNorth(n) }
    fun setUnits(u: Units) = viewModelScope.launch { settingsStore.setUnits(u) }
}
```

A logged waypoint keeps the existing focus because `resolveFocus` only falls back to the first target when the stored focus is not a target. When there was no focus, the new waypoint becomes the only target and so takes it.

- [ ] **Step 2: Write `ui/CompassDial.kt`**

All sizes are fractions of the dial radius so both round emulator sizes and the watch scale alike. The heading is read inside the draw lambda, so a change redraws without recomposing.

```kotlin
package com.infinite8looper.compass.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.infinite8looper.compass.nav.TargetUi

val HeadingBlue = Color(0xFF2F8BFF)
val NorthRed = Color(0xFFFF3B30)
private val TickGray = Color(0xFF6E6E6E)
private val LabelGray = Color(0xFF9E9E9E)
private val Ink = Color(0xFFE8EAED)

fun waypointColor(hue: Float): Color = Color.hsv(hue, 0.65f, 1f)

@Composable
fun CompassDial(headingProvider: () -> Float, targets: List<TargetUi>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Spacer(modifier.fillMaxSize().drawWithCache {
        val r = size.minDimension / 2f
        val c = center
        val degStyle = TextStyle(color = LabelGray, fontSize = (r * 0.095f).toSp())
        val cardStyle = TextStyle(color = Ink, fontSize = (r * 0.17f).toSp(), fontWeight = FontWeight.Medium)
        val degrees = (0 until 360 step 30).map { it to measurer.measure(it.toString(), degStyle) }
        val cardinals = listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").map { (a, s) -> a to measurer.measure(s, cardStyle) }
        val ringOuter = r * 0.84f; val ringInner = r * 0.72f
        val triangle = Path().apply {
            moveTo(c.x, c.y - r * 0.68f); lineTo(c.x - r * 0.06f, c.y - r * 0.58f); lineTo(c.x + r * 0.06f, c.y - r * 0.58f); close()
        }
        onDrawBehind {
            val heading = headingProvider().let { if (it.isNaN()) 0f else it }
            rotate(-heading, c) {
                for (d in 0 until 360 step 2) {
                    val heavy = d % 30 == 0
                    rotate(d.toFloat(), c) {
                        drawLine(
                            color = if (d == 0) NorthRed else if (heavy) Ink else TickGray,
                            start = Offset(c.x, c.y - ringOuter), end = Offset(c.x, c.y - ringInner),
                            strokeWidth = if (heavy) r * 0.022f else r * 0.01f, cap = StrokeCap.Round,
                        )
                    }
                }
                for ((angle, layout) in degrees) rotate(angle.toFloat(), c) { centeredText(layout, c.x, c.y - r * 0.93f) }
                for ((angle, layout) in cardinals) rotate(angle.toFloat(), c) { centeredText(layout, c.x, c.y - r * 0.50f) }
                // Unfocused markers first so the focused one is drawn on top.
                for (t in targets.sortedBy { it.focused }) {
                    val a = t.dialAngleDeg ?: continue
                    rotate(a, c) {
                        val at = Offset(c.x, c.y - (ringOuter + ringInner) / 2f)
                        val radius = if (t.focused) r * 0.075f else r * 0.048f
                        drawCircle(Color.Black, radius + r * 0.012f, at)
                        drawCircle(waypointColor(t.hue), radius, at)
                        if (t.focused) drawCircle(Color.Black, radius * 0.38f, at, style = Stroke(r * 0.012f))
                    }
                }
            }
            drawLine(HeadingBlue, Offset(c.x, c.y - ringOuter), Offset(c.x, c.y - ringInner), r * 0.022f, StrokeCap.Round)
            drawPath(triangle, HeadingBlue)
        }
    })
}

private fun DrawScope.centeredText(layout: TextLayoutResult, cx: Float, cy: Float) {
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
}
```

- [ ] **Step 3: Write `ui/CompassScreen.kt`**

```kotlin
package com.infinite8looper.compass.ui

import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val Ink = Color(0xFFE8EAED)
private val Dim = Color(0xFF9E9E9E)

@Composable
fun CompassScreen(vm: CompassViewModel, onOpenList: () -> Unit, onLog: () -> Unit, onCalibrate: () -> Unit) {
    // Held as a State and read only in the frame loop and derived states, so sensor events do not recompose.
    val readingState = vm.heading.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val navState = rememberUpdatedState(nav)
    val shown = remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val reading = readingState.value
            if (reading != null && !shouldHoldFlat(reading.pitchDeg)) {
                val n = navState.value
                val target = displayHeading(reading.magneticDeg, n.declination, n.north).deg
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                shown.floatValue = if (shown.floatValue.isNaN()) target else smoothAngle(shown.floatValue, target, dt)
            }
            last = now
        }
    }

    val headingText by remember { derivedStateOf { if (shown.floatValue.isNaN()) "--" else "${shown.floatValue.roundToInt() % 360}°" } }
    val holdFlat by remember { derivedStateOf { readingState.value?.let { shouldHoldFlat(it.pitchDeg) } ?: false } }
    val lowAccuracy by remember {
        derivedStateOf { readingState.value?.accuracy?.let { it <= SensorManager.SENSOR_STATUS_ACCURACY_LOW } ?: false }
    }
    val showingMagnetic = nav.north == NorthRef.MAGNETIC || nav.declination == null
    val stale by produceState(false, nav.fixElapsedMs) {
        while (true) { value = isStale(SystemClock.elapsedRealtime(), nav.fixElapsedMs); delay(1_000) }
    }

    val view = LocalView.current
    val navigating = nav.targets.isNotEmpty()
    DisposableEffect(navigating) {
        view.keepScreenOn = navigating
        onDispose { view.keepScreenOn = false }
    }

    val focus = remember { FocusRequester() }
    var crown by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e ->
                crown += e.verticalScrollPixels
                if (abs(crown) > 80f) { vm.moveFocus(if (crown > 0) 1 else -1); crown = 0f }
                true
            }
            .focusRequester(focus).focusable()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (abs(total) > 48.dp.toPx()) vm.moveFocus(if (total < 0) 1 else -1) },
                ) { _, dy -> total += dy }
            },
        contentAlignment = Alignment.Center,
    ) {
        CompassDial(headingProvider = { shown.floatValue }, targets = nav.targets)

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(0.52f)) {
            if (!vm.supported) {
                Text("This watch has no compass sensor", color = Ink, fontSize = 12.sp, textAlign = TextAlign.Center)
            } else {
                if (showingMagnetic) Text("MAG", color = Dim, fontSize = 9.sp)
                Text(headingText, color = Ink, fontSize = 38.sp)
                when {
                    holdFlat -> Text("Hold flat", color = NorthRed, fontSize = 11.sp)
                    lowAccuracy -> Text("Calibrate", color = NorthRed, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onCalibrate))
                }
            }
            val focused = nav.focused
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onOpenList).padding(top = 2.dp)) {
                when {
                    focused != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(waypointColor(focused.hue), CircleShape))
                            Spacer(Modifier.width(4.dp))
                            Text(focused.name, color = Ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val d = focused.distanceM
                        Text(
                            text = when {
                                d == null -> "Locating…"
                                hasArrived(d, nav.units) -> "Arrived at target"
                                stale -> "${formatDistance(d, nav.units)} (old)"
                                else -> formatDistance(d, nav.units)
                            },
                            color = if (stale) Dim else Ink, fontSize = 11.sp,
                        )
                    }
                    nav.waypoints.isEmpty() -> Text("Tap + to save a waypoint", color = Dim, fontSize = 10.sp, textAlign = TextAlign.Center)
                    else -> Text("No target", color = Dim, fontSize = 11.sp)
                }
            }
        }

        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(34.dp)
                .background(Color(0xFF1F1F1F), CircleShape).clickable(onClick = onLog),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Ink, fontSize = 20.sp) }
    }
}
```

- [ ] **Step 4: Write `ui/CompassApp.kt` and point the activity at it**

The other screens are added in Tasks 8 to 10. Until then their routes show the route name, so the graph compiles and navigation can be exercised.

```kotlin
package com.infinite8looper.compass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.infinite8looper.compass.CompassViewModel

@Composable
fun CompassApp() {
    val vm: CompassViewModel = viewModel()
    val nav = rememberSwipeDismissableNavController()
    AppScaffold(timeText = {}) {
        SwipeDismissableNavHost(navController = nav, startDestination = "compass") {
            composable("compass") {
                CompassScreen(vm, onOpenList = { nav.navigate("waypoints") }, onLog = { nav.navigate("log") }, onCalibrate = { nav.navigate("calibrate") })
            }
            composable("log") { Pending("log") }
            composable("waypoints") { Pending("waypoints") }
            composable("edit/{id}") { Pending("edit") }
            composable("hue/{id}") { Pending("hue") }
            composable("settings") { Pending("settings") }
            composable("calibrate") { Pending("calibrate") }
        }
    }
}

@Composable
private fun Pending(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) }
}
```

In `MainActivity.kt`, replace the `setContent { ... }` block and its now-unused imports with:
```kotlin
        setContent { com.infinite8looper.compass.ui.CompassApp() }
```

- [ ] **Step 5: Build, install and compare with the mockup at both sizes**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
./gradlew testDebugUnitTest assembleDebug
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n com.infinite8looper.compass/.MainActivity
sleep 4 && scripts/screenshot.sh compass-large
```
Open `docs/screenshots/compass-large.png` next to `notes/mockup.png` and check each of these by eye:
- fine ticks every 2°, heavy ticks every 30°, red tick at north;
- degree labels outside the ring and N, E, S, W inside it, both rotating with the dial;
- blue tick and triangle fixed at 12 o'clock;
- heading number centered; `MAG` shown (no position is known yet);
- "Tap + to save a waypoint" under the heading; `+` at the bottom;
- nothing clipped by the round edge.

Then run `$ADB emu sensor status` to see which virtual sensors can be set. Change the device orientation from the emulator's Extended Controls (Virtual sensors) and confirm the dial turns and the number follows. If the rotation-vector sensor cannot be driven on the emulator, say so in the notes; varied headings are then checked on the watch in Task 12.

Repeat on the small emulator: close the large one, run `scripts/emulator.sh Wear_OS_Small_Round`, install, launch, and save `compass-small`. Fix any layout fault found at either size and re-check both.

- [ ] **Step 6: Commit**

```bash
git add app/src/main docs/screenshots
git commit -m "Add ViewModel, compass dial and compass screen"
```

---

### Task 8: Logging a waypoint

**Files:**
- Create: `app/src/main/java/com/infinite8looper/compass/ui/LogScreen.kt`
- Modify: `app/src/main/java/com/infinite8looper/compass/ui/CompassApp.kt` (the `log` route)

**Interfaces:**
- Consumes: `CompassViewModel.locationSource`, `logWaypoint`, `permissionChanged`; `acquireFix`.
- Produces: `@Composable fun LogScreen(vm: CompassViewModel, onDone: () -> Unit)`

- [ ] **Step 1: Write `LogScreen.kt`**

```kotlin
package com.infinite8looper.compass.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.acquireFix
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private sealed interface LogState {
    data object NeedsPermission : LogState
    data object Denied : LogState
    data class Searching(val accuracyM: Float?) : LogState
    data class Saved(val name: String, val accuracyM: Float) : LogState
    data object Failed : LogState
}

@Composable
fun LogScreen(vm: CompassViewModel, onDone: () -> Unit) {
    var state by remember {
        mutableStateOf<LogState>(if (vm.locationSource.hasPermission()) LogState.Searching(null) else LogState.NeedsPermission)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.permissionChanged()
        state = if (granted) LogState.Searching(null) else LogState.Denied
    }
    LaunchedEffect(state is LogState.NeedsPermission) {
        if (state is LogState.NeedsPermission) ask.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val searching = state is LogState.Searching
    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        val fix = acquireFix(vm.locationSource.updates(1_000)) { state = LogState.Searching(it.accuracyM) }
        if (fix == null) {
            state = LogState.Failed
        } else {
            val w = vm.logWaypoint(fix)
            state = LogState.Saved(w.name, fix.accuracyM)
            delay(1_500)
            onDone()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val text = when (val s = state) {
            LogState.NeedsPermission -> "Waiting for location permission"
            LogState.Denied -> "Waypoints need location access. Allow it in Settings, then try again."
            is LogState.Searching -> "Finding position…" + (s.accuracyM?.let { "\n±${it.roundToInt()} m" } ?: "")
            is LogState.Saved -> "Saved ${s.name}\n±${s.accuracyM.roundToInt()} m"
            LogState.Failed -> "Couldn't get a position. Try again outdoors."
        }
        Text(text, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (state is LogState.Denied || state is LogState.Failed) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone) { Text("OK") }
        }
    }
}
```

- [ ] **Step 2: Wire the route**

In `CompassApp.kt`, replace `composable("log") { Pending("log") }` with:
```kotlin
            composable("log") { LogScreen(vm, onDone = { nav.popBackStack() }) }
```

- [ ] **Step 3: Exercise every outcome on the emulator**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
./gradlew testDebugUnitTest assembleDebug && $ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell pm clear com.infinite8looper.compass
$ADB shell am start -n com.infinite8looper.compass/.MainActivity
```
Check each of these, saving a screenshot of each result:
1. **Denied:** tap `+`, deny the permission. Expected: the "Waypoints need location access" message; OK returns to the compass, which still works.
2. **Granted, with a position:** run `$ADB shell pm grant com.infinite8looper.compass android.permission.ACCESS_FINE_LOCATION`, start `scripts/geo-feed.sh -72.2896 43.7022 120 &`, tap `+`. Expected: "Finding position…" with an accuracy, then "Saved Waypoint 1", then the compass showing "Waypoint 1", `Arrived at target`, and `MAG` gone.
3. **Marker and distance:** restart the feed about 423 m north with `scripts/geo-feed.sh -72.2896 43.7060 120 &`. Expected: within about 10 seconds the distance reads close to `423 m` (or `0.3 mi`) and the marker sits at the dial's 180° (the waypoint is due south).
4. **No position:** stop the feed, turn location off with `$ADB shell settings put secure location_mode 0`, tap `+`. Expected: after 20 seconds, "Couldn't get a position." Turn location back on with `location_mode 3`.
5. **Stale:** with a target active and the feed stopped for over 20 seconds, the distance is dimmed and ends in `(old)`.

Confirm the stored row is correct, not only the screen:
```bash
$ADB shell run-as com.infinite8looper.compass sqlite3 databases/waypoints.db "select id,name,lat,lon,isTarget from waypoints"
```
Expected: `1|Waypoint 1|43.7022|-72.2896|1` (coordinates within 0.0005). If `sqlite3` is missing on the image, pull the file with `run-as ... cat databases/waypoints.db > /tmp/w.db` and query it on the Mac.

- [ ] **Step 4: Commit**

```bash
git add app/src/main docs/screenshots
git commit -m "Add waypoint logging flow with permission and no-fix handling"
```

---

### Task 9: Waypoints list, edit screen and hue picker

**Files:**
- Create: `app/src/main/java/com/infinite8looper/compass/ui/WaypointsScreen.kt`, `ui/EditScreen.kt`
- Modify: `ui/CompassApp.kt` (routes `waypoints`, `edit/{id}`, `hue/{id}`)

**Interfaces:**
- Consumes: `CompassViewModel.nav`, `setTarget`, `setAllTargets`, `rename`, `setHue`, `delete`; `waypointColor`; `formatDistance`, `distanceMeters`.
- Produces:
  - `@Composable fun WaypointsScreen(vm: CompassViewModel, onEdit: (Long) -> Unit, onSettings: () -> Unit)`
  - `@Composable fun EditScreen(vm: CompassViewModel, id: Long, onPickHue: () -> Unit, onDone: () -> Unit)`
  - `@Composable fun HueScreen(vm: CompassViewModel, id: Long, onDone: () -> Unit)`

The spec is amended in this task's last step: on a list row, the switch sets the target and the rest of the row opens the edit screen; settings are reached from the end of the list.

- [ ] **Step 1: Write `WaypointsScreen.kt`**

```kotlin
package com.infinite8looper.compass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SplitSwitchButton
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.formatDistance

@Composable
fun WaypointsScreen(vm: CompassViewModel, onEdit: (Long) -> Unit, onSettings: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val distances = nav.targets.associate { it.id to it.distanceM }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Waypoints") } }
            if (nav.waypoints.isEmpty()) {
                item { Text("None yet. Tap + on the compass.") }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { vm.setAllTargets(true) }) { Text("All") }
                        Button(onClick = { vm.setAllTargets(false) }) { Text("None") }
                    }
                }
                items(nav.waypoints, key = { it.id }) { w ->
                    SplitSwitchButton(
                        checked = w.isTarget,
                        onCheckedChange = { vm.setTarget(w.id, it) },
                        toggleContentDescription = "Set target",
                        onContainerClick = { onEdit(w.id) },
                        modifier = Modifier.fillMaxWidth(),
                        secondaryLabel = distances[w.id]?.let { d -> { Text(formatDistance(d, nav.units)) } },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(waypointColor(w.hue), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(w.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item { Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") } }
        }
    }
}
```

- [ ] **Step 2: Write `EditScreen.kt`**

```kotlin
package com.infinite8looper.compass.ui

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.normalizeDeg
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Opens the system voice or keyboard input and returns what was entered. */
@Composable
fun rememberTextInput(onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("name") }
        if (text != null) onText(text.toString())
    }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("name").setLabel("Name").build()))
        launcher.launch(intent)
    }
}

@Composable
fun EditScreen(vm: CompassViewModel, id: Long, onPickHue: () -> Unit, onDone: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val w = nav.waypoints.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    val rename = rememberTextInput { vm.rename(id, it) }
    val listState = rememberScalingLazyListState()

    if (w == null) {
        // Deleted, or not loaded yet. Leave once the list has loaded without it.
        LaunchedEffect(nav.waypoints) { if (nav !== com.infinite8looper.compass.NavUi.EMPTY) onDone() }
        return
    }
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(w.name) } }
            item {
                SwitchButton(checked = w.isTarget, onCheckedChange = { vm.setTarget(id, it) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Set target") })
            }
            item { Button(onClick = rename, modifier = Modifier.fillMaxWidth()) { Text("Rename") } }
            item {
                Button(onClick = onPickHue, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(12.dp).background(waypointColor(w.hue), androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.width(8.dp)); Text("Color")
                }
            }
            item { Button(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete") } }
        }
    }
    AlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${w.name}?") },
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = { confirmDelete = false; vm.delete(id); onDone() }) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmDelete = false }) },
    )
}

@Composable
fun HueScreen(vm: CompassViewModel, id: Long, onDone: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val start = nav.waypoints.firstOrNull { it.id == id }?.hue ?: 0f
    var hue by remember(start) { mutableFloatStateOf(start) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Hue 0 is drawn at 12 o'clock and increases clockwise.
    fun hueAt(p: Offset, center: Offset) =
        normalizeDeg(Math.toDegrees(atan2((p.x - center.x).toDouble(), (center.y - p.y).toDouble())).toFloat())

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e -> hue = normalizeDeg(hue + e.verticalScrollPixels * 0.25f); true }
            .focusRequester(focus).focusable()
            .pointerInput(Unit) { detectDragGestures { change, _ -> hue = hueAt(change.position, Offset(size.width / 2f, size.height / 2f)) } }
            .pointerInput(Unit) { detectTapGestures { p ->
                val c = Offset(size.width / 2f, size.height / 2f)
                if ((p - c).getDistance() > size.width * 0.3f) hue = hueAt(p, c)
            } },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val ring = r * 0.16f
            val colors = (0..360 step 10).map { waypointColor(((it + 90) % 360).toFloat()) }
            // sweepGradient starts at 3 o'clock, so the list is offset by 90 degrees to put hue 0 at the top.
            drawCircle(Brush.sweepGradient(colors, center), r - ring / 2f - r * 0.04f, center, style = Stroke(ring))
            val a = Math.toRadians(hue.toDouble())
            val at = Offset(center.x + (r - ring / 2f - r * 0.04f) * sin(a).toFloat(), center.y - (r - ring / 2f - r * 0.04f) * cos(a).toFloat())
            drawCircle(Color.Black, ring * 0.62f, at)
            drawCircle(waypointColor(hue), ring * 0.5f, at)
            drawCircle(waypointColor(hue), r * 0.2f, Offset(center.x, center.y - r * 0.22f))
        }
        Button(onClick = { vm.setHue(id, hue); onDone() }, modifier = Modifier.padding(top = 46.dp)) { Text("Done") }
    }
}
```

- [ ] **Step 3: Wire the routes**

In `CompassApp.kt`, replace the `waypoints`, `edit/{id}` and `hue/{id}` routes with:
```kotlin
            composable("waypoints") {
                WaypointsScreen(vm, onEdit = { nav.navigate("edit/$it") }, onSettings = { nav.navigate("settings") })
            }
            composable("edit/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                EditScreen(vm, id, onPickHue = { nav.navigate("hue/$id") }, onDone = { nav.popBackStack() })
            }
            composable("hue/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                HueScreen(vm, id, onDone = { nav.popBackStack() })
            }
```

- [ ] **Step 4: Exercise it on the emulator**

Build and install as before, start `scripts/geo-feed.sh -72.2896 43.7022 600 &`, and log five waypoints, moving the feed between each (for example latitudes 43.7022, 43.7060, 43.6990 and longitudes -72.2896, -72.2850, -72.2940). Then check, with a screenshot of each:
1. The compass shows five markers in five clearly different hues, one larger (focused).
2. Turning the crown (`$ADB shell input rotary 100`, or the emulator's crown control) and swiping up or down both move the focus; the name and distance change and a different marker enlarges. The set of markers does not change.
3. Tapping the name opens the list: five rows, newest first, each with its dot, distance and switch.
4. A switch turned off removes that marker from the compass. "None" clears all markers and the compass reads `No target`; "All" restores five.
5. Tapping a row opens the edit screen. "Rename" opens text input; an entered name appears on the row and the compass. Submitting an empty name leaves the old one.
6. "Color" opens the hue ring; the crown and a drag both move the indicator; "Done" changes the dot, the row and the marker.
7. "Delete" asks first; confirming removes the row. Deleting the focused target moves the focus to another target, and deleting the last waypoint returns the list to "None yet."
8. Swiping right backs out of each screen.

Then check many targets. Feed positions in a loop and log 30 waypoints, or insert rows directly:
```bash
for i in $(seq 1 30); do $ADB shell run-as com.infinite8looper.compass sqlite3 databases/waypoints.db \
 "insert into waypoints(name,lat,lon,accuracyM,createdAt,hue,isTarget) values('Bulk $i', 43.70+$i*0.002, -72.29+($i%7)*0.003, 5, $i, ($i*137.508)%360, 1)"; done
$ADB shell am force-stop com.infinite8looper.compass && $ADB shell am start -n com.infinite8looper.compass/.MainActivity
```
Expected: all markers draw, overlapping ones stay legible because the focused marker is on top, the list scrolls smoothly, and the app does not crash. Remove the bulk rows afterwards with `$ADB shell pm clear com.infinite8looper.compass`.

- [ ] **Step 5: Amend the spec to match**

In `docs/superpowers/specs/2026-10-11-wear-compass-design.md`:
- Under "Waypoints list", replace "Tapping a row toggles it as a target." and "Each row has an edit control that opens the edit screen." with "Each row has a switch that sets it as a target; tapping the rest of the row opens the edit screen." and add "The last item opens settings."
- Under "Compass (start screen)", delete the bullet "A settings entry point at the top edge, small enough not to compete with the dial."

- [ ] **Step 6: Commit**

```bash
git add app/src/main docs
git commit -m "Add waypoint list, edit screen and hue picker"
```

---

### Task 10: Calibration and settings screens

**Files:**
- Create: `app/src/main/java/com/infinite8looper/compass/ui/CalibrationScreen.kt`, `ui/SettingsScreen.kt`
- Modify: `ui/CompassApp.kt` (routes `settings`, `calibrate`)

**Interfaces:**
- Consumes: `CompassViewModel.heading`, `nav`, `setNorth`, `setUnits`; `accuracyLabel`, `isFieldNormal`; `BuildConfig.VERSION_NAME`.
- Produces: `@Composable fun CalibrationScreen(vm: CompassViewModel)`, `@Composable fun SettingsScreen(vm: CompassViewModel, onCalibrate: () -> Unit)`

- [ ] **Step 1: Write `CalibrationScreen.kt`**

```kotlin
package com.infinite8looper.compass.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.accuracyLabel
import com.infinite8looper.compass.nav.isFieldNormal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CalibrationScreen(vm: CompassViewModel) {
    val reading by vm.heading.collectAsStateWithLifecycle()
    val phase by rememberInfiniteTransition(label = "figure8")
        .animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
    val accuracy = reading?.accuracy
    val field = reading?.fieldMicroTesla
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Move your wrist in a figure 8", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
        Canvas(Modifier.fillMaxWidth(0.7f).height(46.dp)) {
            // A lemniscate traced as x = sin t, y = sin t cos t.
            fun at(t: Float) = Offset(center.x + size.width * 0.45f * sin(t), center.y + size.height * 0.85f * sin(t) * cos(t))
            val path = Path().apply {
                moveTo(at(0f).x, at(0f).y)
                for (i in 1..64) { val p = at(i * (2 * PI).toFloat() / 64); lineTo(p.x, p.y) }
            }
            drawPath(path, Color(0xFF6E6E6E), style = Stroke(2.dp.toPx()))
            drawCircle(HeadingBlue, 5.dp.toPx(), at(phase))
        }
        Text(
            "Accuracy: ${accuracyLabel(accuracy)}",
            color = if (accuracy != null && accuracy >= 2) Color(0xFF7BE07B) else NorthRed, fontSize = 13.sp,
        )
        if (field != null) {
            Text("Field: ${field.roundToInt()} µT", color = Color(0xFF9E9E9E), fontSize = 10.sp)
            if (!isFieldNormal(field)) {
                Text("Magnetic interference nearby. Move away from chargers, magnets and steel.",
                    color = NorthRed, fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
```

- [ ] **Step 2: Write `SettingsScreen.kt`**

```kotlin
package com.infinite8looper.compass.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.infinite8looper.compass.BuildConfig
import com.infinite8looper.compass.CompassViewModel
import com.infinite8looper.compass.nav.NorthRef
import com.infinite8looper.compass.nav.Units

@Composable
fun SettingsScreen(vm: CompassViewModel, onCalibrate: () -> Unit) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Settings") } }
            item {
                SwitchButton(
                    checked = nav.north == NorthRef.TRUE,
                    onCheckedChange = { vm.setNorth(if (it) NorthRef.TRUE else NorthRef.MAGNETIC) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("True north") }, secondaryLabel = { Text("Off: magnetic north") },
                )
            }
            item {
                SwitchButton(
                    checked = nav.units == Units.IMPERIAL,
                    onCheckedChange = { vm.setUnits(if (it) Units.IMPERIAL else Units.METRIC) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Feet and miles") }, secondaryLabel = { Text("Off: meters and km") },
                )
            }
            item { Button(onClick = onCalibrate, modifier = Modifier.fillMaxWidth()) { Text("Calibrate") } }
            item { Text("Compass ${BuildConfig.VERSION_NAME}\nMIT License") }
        }
    }
}
```

- [ ] **Step 3: Wire the routes and remove the stub**

In `CompassApp.kt`, replace the `settings` and `calibrate` routes with:
```kotlin
            composable("settings") { SettingsScreen(vm, onCalibrate = { nav.navigate("calibrate") }) }
            composable("calibrate") { CalibrationScreen(vm) }
```
Delete the `Pending` composable and any imports only it used.

- [ ] **Step 4: Exercise it on the emulator**

Build, install, and with one waypoint logged and a position fed for Hanover:
1. Compass, list, Settings. Turn "True north" off. Expected on the compass: `MAG` appears, the heading number changes by about 13 to 14 degrees, and the target marker moves by the same amount, so it still points at the same physical direction.
2. Turn it back on: `MAG` goes and both return.
3. Toggle units: the distance switches between `m`/`km` and `ft`/`mi` on the compass and the list.
4. Force-stop and relaunch: both settings, the targets and the focus are as they were left.
5. Calibrate: the dot traces the figure 8, accuracy shows a label, and the field strength shows a number. Set an abnormal field from the emulator's virtual sensors if available (`$ADB emu sensor set magnetic-field 200:0:0`) and confirm the interference warning appears; if the emulator cannot do this, check it on the watch in Task 12 with a magnet nearby.

Save screenshots of the settings and calibration screens at both emulator sizes and check for clipped text.

- [ ] **Step 5: Run everything and commit**

```bash
scripts/geo-feed.sh -72.2896 43.7022 300 &
./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug
kill %1
git add app/src/main docs/screenshots
git commit -m "Add calibration and settings screens"
```
Expected: all tests pass and lint reports no errors. Fix every lint error; read each warning and fix those that point at a real fault.

---

### Task 11: Documentation

**Files:**
- Modify: `README.md`
- Create: `docs/TESTING.md`

**Interfaces:**
- Produces: nothing consumed by code.

The README states only what the code does. It must not state positions, policies or promises on the owner's behalf; where such text is needed (support, privacy, contribution rules), leave it out and list it for the owner in the handoff.

- [ ] **Step 1: Write `README.md`**

```markdown
# Compass

A compass and waypoint navigator for Wear OS watches.

![Compass screen](docs/screenshots/compass-large.png)

## What it does

- Shows a rotating compass dial with the heading in degrees.
- Logs the current GPS position as a waypoint with one tap.
- Shows any number of waypoints as colored markers on the dial, with the name and distance of the focused one.
- Lets you rename, recolor and delete waypoints.
- Shows true or magnetic north, in metric or imperial units.
- Guides magnetometer calibration and warns about magnetic interference.

The app runs entirely on the watch. It makes no network requests and has no phone companion.

## Using it

| Action | How |
|-|-|
| Log a waypoint | Tap `+` |
| Change the focused target | Turn the crown, or swipe up or down |
| Open the waypoint list | Tap the name under the heading |
| Set or clear a target | Use the switch on its row |
| Rename, recolor or delete | Tap its row |
| Settings and calibration | Last item in the waypoint list |

## Building

Requires JDK 17 and the Android SDK with platform 36.

    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

## Testing

See [docs/TESTING.md](docs/TESTING.md).

## License

MIT. See [LICENSE](LICENSE).
```

- [ ] **Step 2: Write `docs/TESTING.md`**

```markdown
# Testing

No test uses mocks or fakes.

## Unit tests

    ./gradlew testDebugUnitTest

Cover bearing, distance, declination handling, angle smoothing, distance formatting, color assignment, fix acquisition, and target and focus logic.

## Instrumented tests

These need a running Wear OS emulator or a connected watch, and a position feed for the location test:

    scripts/emulator.sh
    scripts/geo-feed.sh -72.2896 43.7022 300 &
    ./gradlew connectedDebugAndroidTest

They write and read a real database file and a real settings file, read the device's rotation-vector sensor, and read a fix from the fused location provider.

## Screens

    scripts/screenshot.sh <name>

writes `docs/screenshots/<name>.png` from the running device.

## On a watch

The emulator's sensors are simulated. Heading accuracy, smoothness and GPS behavior are checked on a physical watch using the checklist in `notes/device-checklist.md`.
```

- [ ] **Step 3: Check the links and commands, then commit**

Open the README preview and confirm the screenshot renders and both relative links resolve to existing files. Run each command in the two documents once to confirm it works as written.

```bash
git add README.md docs/TESTING.md
git commit -m "Document usage, building and testing"
```

---

### Task 12: Verification on the Pixel Watch

**Files:**
- Create: `notes/device-checklist.md`
- Modify: code as needed to fix what the checks find

**Interfaces:**
- Consumes: the finished app.
- Produces: a filled-in checklist with measured results.

This task needs the owner: the watch must be paired for wireless debugging (on the watch: Settings, Developer options, Wireless debugging, Pair new device; on the Mac: `$ADB pair <ip>:<port>` then `$ADB connect <ip>:<port>`), and the outdoor checks need the owner to walk with it. Stop and ask when the watch is needed.

- [ ] **Step 1: Record what the watch is**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB shell getprop ro.product.model; $ADB shell getprop ro.build.version.sdk
$ADB shell dumpsys sensorservice | grep -iE "rotation vector|magnetic"
```
Expected: an SDK level of 33 or higher, and both a rotation-vector and a magnetic-field sensor listed. Write the model, SDK level and sensor names into `notes/device-checklist.md`. If the SDK level is below 33, stop and report it.

- [ ] **Step 2: Find out whether any hardware button is available to apps**

Add one temporary line to `MainActivity.onCreate`, after `super.onCreate`:
```kotlin
        android.util.Log.i("Compass", "buttons=" + androidx.wear.input.WearableButtons.getButtonCount(this))
```
Install, launch, and read it with `$ADB logcat -d -s Compass`. Record the count, then remove the line. A count above zero means a button could also log a waypoint; report that to the owner as a possible addition instead of building it.

- [ ] **Step 3: Run the automated tests on the watch**

Run: `./gradlew connectedDebugAndroidTest` with the watch as the only connected device, outdoors or by a window so it can get a fix.
Expected: all pass except possibly `freshFixMatchesTheFedPosition`, which asserts the emulator's fed position. If that is the only failure, record the fix the watch reported and that it was a plausible real position; do not change the test.

- [ ] **Step 4: Write and work through `notes/device-checklist.md`**

```markdown
# Device checklist

Watch model: ____   Wear OS SDK: ____   Date: ____

## Heading
- [ ] After a figure-8, accuracy reads Medium or High.
- [ ] Watch flat, pointed at a reference compass's N, E, S, W in turn: each reading within 5 degrees (magnetic setting).  N __ E __ S __ W __
- [ ] True-north setting differs from magnetic by the local declination (about 13 to 14 degrees west in Hanover, NH).  Measured: __
- [ ] Tilting past about 70 degrees shows "Hold flat" and the dial stops; it resumes when flat.
- [ ] A magnet or the charger nearby triggers the interference warning on the calibration screen.

## Smoothness
- [ ] Turning slowly through a full circle: the dial moves without visible steps, and passes through north without spinning the long way.
- [ ] Holding still: the dial and the number do not jitter.
- [ ] A quick 90-degree turn: the dial catches up in well under a second, without overshoot.
- [ ] Frame statistics over 30 seconds of turning (command below): janky frames under 2 percent.  Measured: __

## Waypoints
- [ ] Logging outdoors saves within 20 seconds and reports an accuracy.
- [ ] Logging indoors with no sky view reports that it could not get a position.
- [ ] Walk 100 m from a logged point: distance reads 100 m within the reported accuracy, and the marker points back at it.
- [ ] Walk back: the text changes to "Arrived at target" within about 10 m.
- [ ] Three targets in different directions each point the right way at once.
- [ ] Crown and swipe both change the focus.
- [ ] Rename by voice and by keyboard both work.

## Power and lifecycle
- [ ] With a target set, the screen stays on while the compass is open.
- [ ] With no target, the screen times out normally.
- [ ] Revoking the location permission in system settings while a target is set: the app keeps running and the compass still works.
- [ ] Pressing the crown and returning restores the same state.
- [ ] Battery drop over 30 minutes of navigating: __ percent.
```

Frame statistics:
```bash
$ADB shell dumpsys gfxinfo com.infinite8looper.compass reset
# turn steadily for 30 seconds with the compass open
$ADB shell dumpsys gfxinfo com.infinite8looper.compass | grep -E "Total frames|Janky frames|percentile"
```

- [ ] **Step 5: Tune and fix**

If the dial lags or jitters, adjust `tau` in `smoothAngle` (smaller follows faster and jitters more) and repeat the smoothness checks; the unit tests must still pass unchanged. For any other failed item, find the cause before changing code (use superpowers:systematic-debugging), fix it, and re-run every automated check and the affected checklist items.

- [ ] **Step 6: Commit**

```bash
git add notes/device-checklist.md app
git commit -m "Record on-device verification and apply tuning"
```

---

### Task 13: Release preparation

**Files:**
- Create: `docs/release/privacy-draft.md`, `docs/release/listing-draft.md`
- Modify: none in the app unless the checks below require it

**Interfaces:**
- Produces: a signed release bundle and draft text for the owner. Nothing is uploaded or published in this task.

- [ ] **Step 1: Check Play's current requirements**

Read the current Google Play target API level requirement for Wear OS apps and the Wear OS app quality guidelines from developer.android.com. If target SDK 35 does not meet the requirement, report the required level to the owner before changing it. Record the URLs read and the date in the notes.

- [ ] **Step 2: Set up signing with the owner**

Read `~/beam-up-pixel/PLAY_STORE_RELEASE.md` to see how the owner signed the earlier app. The upload key is the owner's: ask whether to reuse an existing keystore or create a new one, and do not create or move a key without that answer. Once a keystore exists, write `keystore.properties` in the repo root (it is git-ignored):
```properties
storeFile=<path to the keystore>
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```
Confirm with `git status --short` that neither it nor the keystore shows as untracked.

- [ ] **Step 3: Build and check the release bundle**

```bash
./gradlew clean testDebugUnitTest lintRelease bundleRelease assembleRelease
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB install -r app/build/outputs/apk/release/app-release.apk
$ADB shell am start -n com.infinite8looper.compass/.MainActivity
```
Expected: `app/build/outputs/bundle/release/app-release.aab` exists. The release build is minified, so repeat this smoke test on it: launch, log a waypoint, open the list, rename, recolor, toggle a setting, relaunch and confirm the data persisted. A crash here that the debug build does not have points at a missing keep rule; add the specific rule to `app/proguard-rules.pro`, reference that file in `proguardFiles`, and repeat.

- [ ] **Step 4: Draft the privacy and listing text for approval**

`docs/release/privacy-draft.md`:
```markdown
# Draft for the owner's review. Not published.

Facts about the app that a privacy statement needs to reflect:

- The app reads the watch's location only to save waypoints and to show the direction and distance to them.
- Waypoints and settings are stored only on the watch.
- The app makes no network requests and sends no data to anyone.
- The app has no accounts, analytics or advertising.
- Uninstalling the app removes all of its data.
```

`docs/release/listing-draft.md`:
```markdown
# Draft for the owner's review. Not published.

Title: Compass
Short description (80 characters max): Compass for Wear OS with GPS waypoints you can navigate back to.
Category: Maps & Navigation
Price: Free
Data safety form: location is accessed, processed on-device only, not collected and not shared.
Screenshots: docs/screenshots/ (retake on the watch-shaped emulator at the sizes Play asks for).
```

Give both to the owner. The wording that goes on Play and on `infinite8looper.github.io/privacy.html` is theirs to approve or rewrite.

- [ ] **Step 5: Commit, and stop before publishing**

```bash
git add docs/release app/proguard-rules.pro app/build.gradle.kts
git commit -m "Prepare release bundle and draft listing text"
```
Report the bundle path and the open decisions. Uploading to the Play Console and publishing happen only when the owner says to.
