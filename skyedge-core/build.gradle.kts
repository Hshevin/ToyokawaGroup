plugins {
    id("com.android.library")
}

android {
    namespace = "com.example.skyedge.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    androidResources {
        noCompress += listOf("pt", "onnx", "tflite")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":imgrecord"))
    implementation(libs.pytorch.android)
    implementation(libs.pytorch.android.torchvision)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
}
