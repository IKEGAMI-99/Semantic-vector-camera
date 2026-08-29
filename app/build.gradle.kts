plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val injectedVersionName = providers.environmentVariable("SVCAM_VERSION_NAME").orNull
    ?.trim()
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
val injectedVersionCode = providers.environmentVariable("SVCAM_VERSION_CODE").orNull
    ?.toIntOrNull()
    ?.coerceAtLeast(1)

android {
    namespace = "com.ikegami.svcam"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.ikegami.svcam"
        minSdk = 28
        targetSdk = 37
        versionCode = injectedVersionCode ?: 1
        versionName = injectedVersionName ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSVCAM_ENABLE_VULKAN=OFF",
                )
            }
        }
    }

    // Personal-distribution profile. Debug and Release intentionally share the
    // same package id and repository-embedded signing key so either APK can
    // overwrite the previous build and the in-app updater keeps working.
    signingConfigs {
        create("stableDev") {
            storeFile = file("keys/svcam-release.jks")
            storePassword = "svcam2026"
            keyAlias = "svcam"
            keyPassword = "svcam2026"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableDev")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableDev")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    testImplementation("junit:junit:4.13.2")
}
