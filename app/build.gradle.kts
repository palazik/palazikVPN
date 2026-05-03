plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// ── Download libv2ray.aar at CONFIGURATION time ───────────────────────────────
// This MUST be top-level code, not inside a task. Gradle resolves fileTree()
// dependencies during configuration — before any task runs — so the AAR must
// already exist on disk by the time the dependencies block is evaluated.
val libxrayVersion = "26.5.3"
val libxrayFile    = file("libs/libv2ray.aar")

if (!libxrayFile.exists()) {
    libxrayFile.parentFile.mkdirs()
    val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/v$libxrayVersion/libv2ray.aar"
    println(">>> Downloading libv2ray.aar v$libxrayVersion …")
    // Use curl with a hard timeout — wget --show-progress hangs in Gradle daemon (no TTY)
    val proc = ProcessBuilder(
            "curl", "-fL", "--max-time", "120", "--retry", "3",
            "-o", libxrayFile.absolutePath, url)
        .redirectErrorStream(true)
        .start()
    proc.waitFor()
    if (!libxrayFile.exists() || libxrayFile.length() < 1_000) {
        libxrayFile.delete()
        // fallback: wget without progress bar
        val proc2 = ProcessBuilder(
                "wget", "-q", "--timeout=120", "-O", libxrayFile.absolutePath, url)
            .redirectErrorStream(true)
            .start()
        proc2.waitFor()
    }
    println(">>> libv2ray.aar: ${libxrayFile.length()} bytes")
}

// ── Download geo data files at CONFIGURATION time ─────────────────────────────
val assetsDir   = file("src/main/assets")
val geoipFile   = file("$assetsDir/geoip.dat")
val geositeFile = file("$assetsDir/geosite.dat")

fun downloadIfMissing(url: String, dest: java.io.File) {
    if (dest.exists() && dest.length() > 1_000) return
    dest.parentFile.mkdirs()
    println(">>> Downloading ${dest.name} …")
    val proc = ProcessBuilder(
            "curl", "-fL", "--max-time", "120", "--retry", "3",
            "-o", dest.absolutePath, url)
        .redirectErrorStream(true)
        .start()
    proc.waitFor()
    if (!dest.exists() || dest.length() < 1_000) {
        dest.delete()
        val proc2 = ProcessBuilder(
                "wget", "-q", "--timeout=120", "-O", dest.absolutePath, url)
            .redirectErrorStream(true)
            .start()
        proc2.waitFor()
    }
    println(">>> ${dest.name}: ${dest.length()} bytes")
}

downloadIfMissing(
    "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
    geoipFile,
)
downloadIfMissing(
    "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
    geositeFile,
)

// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace         = "com.palazik.vpn"
    compileSdk        = 35

    defaultConfig {
        applicationId = "com.palazik.vpn"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable        = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    lint {
        disable += setOf("BlockedPrivateApi")
        checkReleaseBuilds = false
        abortOnError       = false
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
