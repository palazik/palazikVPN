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
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

// ── Download libv2ray.aar ─────────────────────────────────────────────────────
// libv2ray.aar = AndroidLibXrayLite — xray-core as Go mobile AAR.
// Uses wget/curl with --location to follow GitHub release redirects.
// ant.get does NOT follow redirects and always fails on GitHub URLs.

val libxrayVersion = "25.12.2"  // tag from AndroidLibXrayLite releases (without 'v' prefix in URL)
val libxrayFile    = file("libs/libv2ray.aar")

tasks.register("downloadLibxray") {
    description = "Download libv2ray.aar from AndroidLibXrayLite if not present"
    onlyIf { !libxrayFile.exists() }
    doLast {
        libxrayFile.parentFile.mkdirs()
        val url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/v$libxrayVersion/libv2ray.aar"
        println("Downloading libv2ray.aar v$libxrayVersion...")
        // wget follows redirects by default; curl needs --location
        val result = exec {
            commandLine("wget", "-q", "--show-progress", "-O", libxrayFile.absolutePath, url)
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0 || !libxrayFile.exists() || libxrayFile.length() < 1000) {
            // fallback to curl
            exec {
                commandLine("curl", "-L", "-o", libxrayFile.absolutePath, url)
            }
        }
        require(libxrayFile.exists() && libxrayFile.length() > 1000) {
            "Failed to download libv2ray.aar. Download manually from:\n$url\nPlace in app/libs/"
        }
        println("libv2ray.aar downloaded: ${libxrayFile.length()} bytes")
    }
}

tasks.named("preBuild") { dependsOn("downloadLibxray") }

dependencies {
    // xray-core AAR — downloaded by downloadLibxray task above
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
