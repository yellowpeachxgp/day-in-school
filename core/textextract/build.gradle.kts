plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "cn.edu.haut.campus.core.textextract"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)

    // AUTO-011 并行期不允许修改版本目录；外部依赖版本固定写在本模块构建文件内。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // 首版只承诺未加密 PDF 文本抽取；排除约 10 MiB 压缩体积的 BouncyCastle 加密/签名传递依赖。
        // 加密 PDF 会以明确错误失败，不静默降级或联网。
        exclude(group = "org.bouncycastle")
    }

    testImplementation(libs.junit)
    // 桌面 JNI 仅用于 Linux/macOS JVM 单测执行同一 ONNX 模型，不进入 Android 运行时或 APK。
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.21.1")
}
