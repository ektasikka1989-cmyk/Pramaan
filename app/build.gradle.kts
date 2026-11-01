plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// LiteRT-LM requires newer coroutines.
// Force a single compatible version across all dependencies.
configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
            // sherpa-onnx 1.13.4 Android JNI is built against ORT 1.27.0.
            // Force the exact same native runtime so Gradle cannot select 1.29.x.
            "com.microsoft.onnxruntime:onnxruntime-android:1.27.0"
        )
    }
}

android {
    namespace = "com.example.arfas"

    ndkVersion = "26.1.10909125" // AROGYADOOT: same NDK as the :whisper module
    externalNativeBuild { // AROGYADOOT: Meta Llama (llama.cpp) bridge
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        externalNativeBuild { // AROGYADOOT
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    // llama.cpp is built for Android 9+ (API 28); phones that can run
                    // a 3B model are all newer. The app itself keeps minSdk 24.
                    "-DANDROID_PLATFORM=android-28"
                )
            }
        }

        applicationId = "com.example.arogyadoot"

        minSdk = 24
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        // Current target device is arm64.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,NOTICE}"
        }

        jniLibs {
            // Required for native LiteRT-LM / Sherpa ONNX libraries.
            useLegacyPackaging = true

            pickFirsts += "**/libc++_shared.so" // AROGYADOOT: whisper, LiteRT and llama all ship it

            // ONNX Runtime can be supplied by more than one dependency.
            // Package only one copy when duplicates exist.
            pickFirsts += "**/libonnxruntime.so"

            // Sherpa ONNX native libraries.
            pickFirsts += "**/libsherpa-onnx-jni.so"
            pickFirsts += "**/libsherpa-onnx-c-api.so"
            pickFirsts += "**/libsherpa-onnx-cxx-api.so"
        }
    }
}

// Match LiteRT-LM's JVM-default configuration.
tasks
    .withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>()
    .configureEach {

        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
            )

            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

dependencies {

    // AROGYADOOT: Hindi / Devanagari text on medicine strips, MCP cards and registers (offline)
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    // -------------------------------------------------------------------------
    // Coroutines
    // -------------------------------------------------------------------------
	implementation(project(":whisper"))

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0"
    )

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0"
    )


    // -------------------------------------------------------------------------
    // LiteRT-LM — Local LLM
    // -------------------------------------------------------------------------

    implementation(
        "com.google.ai.edge.litertlm:litertlm-android:0.16.1"
    )


    // -------------------------------------------------------------------------
    // ONNX Runtime
    // -------------------------------------------------------------------------

    implementation(
        "com.microsoft.onnxruntime:onnxruntime-android:1.27.0"
    )


    // -------------------------------------------------------------------------
    // Sherpa-ONNX — Offline TTS
    // -------------------------------------------------------------------------

    implementation(
        "com.github.k2-fsa:sherpa-onnx:1.13.4"
    )


    // -------------------------------------------------------------------------
    // ML Kit
    // -------------------------------------------------------------------------

    implementation(
        "com.google.mlkit:text-recognition:16.0.1"
    )

    implementation(
        "com.google.mlkit:object-detection:17.0.2"
    )

    implementation(
        "com.google.mlkit:barcode-scanning:17.3.0"
    )


    // -------------------------------------------------------------------------
    // AR + Networking
    // -------------------------------------------------------------------------

    implementation(libs.okhttp)
    implementation(libs.arcore)
    implementation(libs.sceneview)


    // -------------------------------------------------------------------------
    // AndroidX
    // -------------------------------------------------------------------------

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)


    // -------------------------------------------------------------------------
    // Jetpack Compose
    // -------------------------------------------------------------------------

    implementation(
        platform(libs.androidx.compose.bom)
    )

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)


    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    testImplementation(libs.junit)

    androidTestImplementation(
        platform(libs.androidx.compose.bom)
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}