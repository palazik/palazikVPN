plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

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

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

// ── Download libv2ray.aar if missing ──────────────────────────────────────────
// libv2ray.aar = AndroidLibXrayLite — xray-core compiled as a Go mobile AAR.
// It provides Libv2ray.newV2RayPoint() which accepts a TUN fd and runs the full
// xray-core Go runtime (VLESS, VMess, XHTTP, REALITY, TLS, tun2socks — everything).
//
// Source: https://github.com/2dust/AndroidLibXrayLite
// The task below downloads it automatically on first build.
// For manual download: grab libv2ray.aar from the releases page and put it in app/libs/

val libxrayVersion = "26.2.6"

tasks.register("downloadLibxray") {
    description = "Download libv2ray.aar from AndroidLibXrayLite releases if not present"
    val destFile = file("libs/libv2ray.aar")
    onlyIf { !destFile.exists() }
    doLast {
        destFile.parentFile.mkdirs()
        val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/$libxrayVersion/libv2ray.aar"
        println("Downloading libv2ray.aar $libxrayVersion...")
        ant.invokeMethod("get", mapOf("src" to url, "dest" to destFile, "verbose" to "true"))
        println("Done: ${destFile.length()} bytes")
    }
}

tasks.named("preBuild") { dependsOn("downloadLibxray") }

dependencies {
    // libv2ray.aar — xray-core for Android (downloaded by downloadLibxray task above)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // ── Core ──────────────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // ── Compose BOM ───────────────────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // ── Navigation ────────────────────────────────────────────────────────────
    implementation(libs.androidx.navigation.compose)

    // ── Hilt ──────────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ── ViewModel / Lifecycle ─────────────────────────────────────────────────
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ── Networking ────────────────────────────────────────────────────────────
    implementation(libs.okhttp)

    // ── Test ──────────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
