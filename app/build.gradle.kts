plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vidub.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vidub.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.1"

        // Chỉ giữ ABI của điện thoại thật để APK nhẹ hơn (sherpa-onnx mang theo thư viện native)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor cần desugaring khi minSdk < 26
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        // sherpa-onnx có thể được biên dịch bằng Kotlin mới hơn bản đang dùng
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Trình phát video
    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // Dịch offline trên máy (Hàn/Anh -> Việt)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")

    // Nhận dạng giọng nói (Zipformer) và giọng đọc Piper, chạy offline bằng CPU (sherpa-onnx).
    // File AAR được workflow tải từ bản phát hành chính thức vào app/libs (xem .github/workflows/build.yml).
    implementation(files("libs/sherpa-onnx.aar"))
    // Giải nén gói .tar.bz2
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Lấy thông tin video + phụ đề YouTube (thư viện của NewPipe)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Nếu YouTube đổi và app lỗi, hãy nâng phiên bản này lên bản mới nhất:
    // https://github.com/TeamNewPipe/NewPipeExtractor/releases
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.0")
}
