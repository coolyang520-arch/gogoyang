plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gogoyang.plates"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gogoyang.plates"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        // Phones only; leaving out the x86 builds keeps the OpenCV libraries out of the APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Keypoint matching for comparing scooter photos, fully offline.
    implementation("org.opencv:opencv:4.10.0")

    testImplementation("junit:junit:4.13.2")
}
