import java.util.zip.ZipFile

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

        // First-generation Fire TV/Stick devices run API 21/22. Keep the
        // APK installable there; P2P is gated off at
        // runtime on API < 23.
        minSdk = 21
        targetSdk = 35

        versionCode = 30
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


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }


    kotlinOptions {
        jvmTarget = "11"
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// WebRTC exposes the same native filename from its AAR. Fail the build if a
// second runtime dependency contributes another copy instead of letting the
// APK merger silently choose one.
val verifyUniqueWebRtcNativeLibraries = tasks.register("verifyUniqueWebRtcNativeLibraries") {
    doLast {
        val owners = mutableMapOf<String, MutableList<String>>()
        configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .filter { it.file.extension.equals("aar", ignoreCase = true) }
            .forEach { artifact ->
                ZipFile(artifact.file).use { aar ->
                    aar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("jni/") && it.name.endsWith("/libjingle_peerconnection_so.so") }
                        .forEach { entry ->
                            owners.getOrPut(entry.name) { mutableListOf() }
                                .add("${artifact.moduleVersion.id.group}:${artifact.name}:${artifact.moduleVersion.id.version}")
                        }
                }
            }

        val duplicates = owners.filterValues { it.size > 1 }
        check(duplicates.isEmpty()) {
            "Duplicate WebRTC native libraries detected: ${duplicates.entries.joinToString { (path, modules) -> "$path <- ${modules.joinToString()}" }}"
        }
        check(owners.isNotEmpty()) { "No libjingle_peerconnection_so.so was found in the debug runtime AARs" }
        logger.lifecycle("Verified unique WebRTC native owners: ${owners.entries.joinToString { (path, modules) -> "$path <- ${modules.single()}" }}")
    }
}

tasks.named("preBuild") {
    dependsOn(verifyUniqueWebRtcNativeLibraries)
}



dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)

    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

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
    implementation(libs.webrtc.android)


    // Image Loading
    implementation(libs.coil)
    implementation(libs.glide)


    // QR Code
    implementation("com.google.zxing:core:3.5.3")


    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.messaging.ktx)


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
