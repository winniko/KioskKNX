import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "it.kioskknx.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.kioskknx.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 13
        versionName = "1.4.4"
        ndk {
            // -Pabi=arm64-v8a produce un APK piu' leggero per un solo tipo di processore.
            val onlyAbi = project.findProperty("abi")?.toString()
            if (onlyAbi != null) abiFilters += onlyAbi else abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // Solo per i test su emulatore: ./gradlew assembleDebug -Pemu
            if (project.hasProperty("emu")) abiFilters += "x86_64"
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    defaultConfig {
        // Link al codice sorgente mostrato nell'app (licenza AGPL v3), impostato in gradle.properties
        buildConfigField("String", "SOURCE_URL", "\"${project.findProperty("kioskknx.sourceUrl") ?: ""}\"")
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "KioskKNX-${name}" + (project.findProperty("abi")?.let { "-$it" } ?: "") + ".apk"
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("org.linphone:linphone-sdk-android:5.4.128")
    implementation("org.mozilla.geckoview:geckoview:140.0.20250707120347")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.rburgst:okhttp-digest:3.1.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")

    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
}
