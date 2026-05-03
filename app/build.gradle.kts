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

    lint {
        disable += setOf("BlockedPrivateApi")
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// ── Download libv2ray.aar ─────────────────────────────────────────────────────
val libxrayVersion = "1.10.4"   // v2rayNG release that bundles tun2socks
val libxrayFile    = file("libs/libv2ray.aar")

tasks.register("downloadLibxray") {
    description = "Download libv2ray.aar from v2rayNG releases"
    onlyIf { !libxrayFile.exists() }
    doLast {
        libxrayFile.parentFile.mkdirs()
        val url = "https://github.com/2dust/v2rayNG/releases/download/$libxrayVersion/libv2ray.aar"
        println("Downloading libv2ray.aar v$libxrayVersion from v2rayNG...")
        val result = exec {
            commandLine("wget", "-q", "--show-progress", "-O", libxrayFile.absolutePath, url)
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0 || !libxrayFile.exists() || libxrayFile.length() < 1000) {
            exec { commandLine("curl", "-L", "-o", libxrayFile.absolutePath, url) }
        }
        require(libxrayFile.exists() && libxrayFile.length() > 1000) {
            "Failed to download libv2ray.aar from v2rayNG $libxrayVersion"
        }
        println("libv2ray.aar downloaded: ${libxrayFile.length()} bytes")
    }
}

// ── Download geo data files ───────────────────────────────────────────────────
val geoipFile   = file("src/main/assets/geoip.dat")
val geositeFile = file("src/main/assets/geosite.dat")

tasks.register("downloadGeoFiles") {
    description = "Download geoip.dat and geosite.dat from v2fly releases if not present"
    onlyIf { !geoipFile.exists() || !geositeFile.exists() }
    doLast {
        geoipFile.parentFile.mkdirs()

        fun download(url: String, dest: java.io.File) {
            if (dest.exists()) return
            println("Downloading ${dest.name}...")
            val result = exec {
                commandLine("wget", "-q", "--show-progress", "-O", dest.absolutePath, url)
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0 || !dest.exists() || dest.length() < 1000) {
                exec { commandLine("curl", "-L", "-o", dest.absolutePath, url) }
            }
            require(dest.exists() && dest.length() > 1000) {
                "Failed to download ${dest.name} from $url"
            }
            println("${dest.name} downloaded: ${dest.length()} bytes")
        }

        download(
            "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
            geoipFile,
        )
        download(
            "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
            geositeFile,
        )
    }
}

tasks.named("preBuild") {
    dependsOn("downloadLibxray")
    dependsOn("downloadGeoFiles")
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
