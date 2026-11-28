plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "cn.edu.haut.campus.feature.map"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        }
    }

    lint {
        // 工具链规避，非代码问题（裁决 AUTO-031，证据见 PR 与 task-notes/campus/issue-replies.md）：
        // lint 31.8.2（AGP 8.8.2 内置）在并发分析高峰加载 compose ui 1.7.8 lint.jar 中
        // MultipleAwaitPointerEventScopesDetector 的匿名内部类时偶发 NoClassDefFoundError，
        // 同一代码闲时 11 次构建零复现、同 SHA 重跑可红可绿，属 lint/compose 工具链竞态。
        // 该检查是「同一 pointerInput 块内多次 awaitPointerEventScope」风格检查；
        // 本模块 OutdoorMapCanvas.kt 每个 pointerInput 块均只含单个 awaitPointerEventScope，
        // 用法合规、抑制无实际覆盖损失。disable 后 detector 不执行、内部类不加载，崩溃路径确定消除。
        // 待 AGP/compose 升级后必须复评移除（跟踪草稿见 issue-replies.md）。
        disable.add("MultipleAwaitPointerEventScopes")
    }
}

roborazzi {
    // 与 app 模块保持同一套版本化基线布局；本模块只记录 map preview fixtures。
    outputDir.set(file("src/test/roborazzi/debug"))
    compare {
        outputDir.set(file("build/outputs/roborazzi-compare/debug"))
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    api(project(":core:database"))
    api(project(":core:location"))
    api(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.roborazzi.compose)
}
