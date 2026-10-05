plugins {
    id("com.android.application")
}

android {
    namespace = "app.tack"
    compileSdk = 37

    defaultConfig {
        // Not renamed with the app: Android WebView sends this id to the site on
        // every page request, so it stays neutral, and changing it would discard
        // the saved login.
        applicationId = "app.tack.webview"
        // 29: DownloadManager can write to public Downloads without a storage permission.
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Debug-signed until a release keystore exists, so the minified build
            // can be installed on the phone for size and speed measurements.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.version",
            "META-INF/proguard/*",
            "kotlin/**",
            "DebugProbesKt.bin"
        )
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    // For running the page script before the site's own scripts.
    implementation("androidx.webkit:webkit:1.17.1")
}
