plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.network24.player"

    compileSdk = 35

    defaultConfig {
        applicationId = "com.network24.player"

        // The app really needs Android 7.0 (API 24): the WireGuard tunnel
        // library (in-app VPN) needs 24+ to avoid a CompletableFuture crash
        // on VPN teardown - confirmed live on an Android 9 (API 28) Fire TV.
        // minSdk stays at 21 only so older devices (e.g. first-gen Fire TV
        // Stick, API 21-22) can install it and see UnsupportedDeviceGate's
        // "device not supported" screen instead of "App not installed".
        // Nothing past that screen runs below API 24.
        minSdk = 21
        targetSdk = 35

        versionCode = 72
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

    }


    val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull
    val releaseStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() } && file(releaseStoreFile!!).isFile

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }


    buildTypes {

        release {
            isMinifyEnabled = false

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }


        debug {
            isMinifyEnabled = false
        }
    }


    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        // minSdk is 21 only for the "device not supported" screen; below
        // API 24 the app never gets past it (UnsupportedDeviceGate), so
        // API 23/24 calls elsewhere are safe and must not fail the build.
        disable += setOf("NewApi", "InlinedApi")
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }


    kotlinOptions {
        jvmTarget = "17"
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)

    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.material)


    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.gson)

    implementation(libs.kotlinx.coroutines.android)


    // Media3 Player
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ffmpeg.decoder)


    // Image Loading
    implementation(libs.coil)


    // QR Code
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // VPN (Secure Relay)
    implementation(libs.wireguard.tunnel)
    // Scheduled channel + TV guide refresh (Settings > Auto Refresh)
    implementation(libs.androidx.work.runtime.ktx)


    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.firestore.ktx)


    // Room Database
    val roomVersion = "2.6.1"

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    ksp(libs.androidx.room.compiler)



    // Testing
    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
