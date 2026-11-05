plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.decio.skipii"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.skipii"
        minSdk = 26
        targetSdk = 36
        versionCode = 117
        versionName = "0.5.108"
    }

    buildTypes {
        release {
            // Judge speed and smoothness on this build type, never on debug.
            isMinifyEnabled = true
            isShrinkResources = true
            // Sideloaded personal build: the debug key is fine until there's a reason
            // to distribute it.
            signingConfig = signingConfigs.getByName("debug")
            // ML Kit's translator (captions) is the app's only native code, ~16 MB per CPU type: phones only
            // (64- and 32-bit ARM). Debug keeps x86 for the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.nio / java.time APIs that need desugaring below API 33.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    packaging {
        // Compressed in the APK (unpacked on install): the translator's library shrinks from 16 MB to under 7.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    val media3 = "1.10.1"

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui-compose:$media3")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.guava:guava:33.3.1-android")
    // Captions translated on the phone (YouTube refuses its own auto-translate): a model per language, downloaded on first use.
    implementation("com.google.mlkit:translate:17.0.3")
    // The bell's new-video check runs every 15 minutes with no window open (NewVideos).
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Foldables: where the fold runs and whether the phone is half open (Adaptive.kt).
    implementation("androidx.window:window:1.3.0")

    // Parser tests run on the JVM against real InnerTube responses (src/test/resources).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
