plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "cn.edu.haut.campus.feature.settings"
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
}

roborazzi {
    // 与 app 模块保持同一套版本化基线布局；本模块只记录 settings preview fixtures。
    outputDir.set(file("src/test/roborazzi/debug"))
    compare {
        outputDir.set(file("build/outputs/roborazzi-compare/debug"))
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    api(project(":core:database"))
    api(project(":core:reminder"))
    api(project(":core:academic"))
    api(project(":core:academic-protocols"))
    api(project(":core:school-packages"))
    api(project(":core:ui-runtime"))
    api(project(":core:campusservices"))
    api(project(":core:location"))
    api(project(":core:designsystem"))
    api(project(":widget:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.roborazzi.compose)
    testImplementation("org.json:json:20240303")
}
