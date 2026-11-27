plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

val appVersionName = rootProject.extra["appVersionName"] as String
val appVersionCode = rootProject.extra["appVersionCode"] as Int
val compileSdkVer = rootProject.extra["compileSdkVersion"] as Int
val targetSdkVer = rootProject.extra["targetSdkVersion"] as Int
val minSdkVer = rootProject.extra["minSdkVersion"] as Int

android {
    namespace = "cn.edu.haut.campus"
    compileSdk = compileSdkVer

    defaultConfig {
        applicationId = "cn.edu.haut.campus"
        minSdk = minSdkVer
        targetSdk = targetSdkVer
        versionCode = appVersionCode
        versionName = appVersionName

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            fun configuredValue(name: String): String? =
                (System.getenv(name) ?: findProperty(name)?.toString())?.takeIf { it.isNotBlank() }

            val explicitPath = configuredValue("SIGNING_STORE_FILE")
            val signingNames = listOf("SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD")
            val requested = explicitPath != null || signingNames.any { configuredValue(it) != null }
            if (requested) {
                val keystoreFile = rootProject.file(explicitPath ?: "keystore/haut-release.jks")
                require(keystoreFile.isFile) { "正式签名文件不存在，请检查 SIGNING_STORE_FILE" }
                fun signingValue(name: String): String = configuredValue(name)
                    ?: error("缺少正式签名配置：$name")

                storeFile = keystoreFile
                storePassword = signingValue("SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("SIGNING_KEY_ALIAS")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            // 本地未配置正式密钥时只产出未签名包，避免把调试证书误作发行证书。
            signingConfig = releaseSigning.takeIf { it.storeFile?.isFile == true }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    // 版本化基线放在源码树。1.60 尚无内建 separateOutputDirs，因此显式按变体分目录。
    outputDir.set(file("src/test/roborazzi/debug"))
    compare {
        // 比对实际图、diff 图和失败产物只留在 build/，不污染已提交基线。
        outputDir.set(file("build/outputs/roborazzi-compare/debug"))
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:reminder"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:academic"))
    implementation(project(":core:location"))

    implementation(project(":feature:today"))
    implementation(project(":feature:timetable"))
    implementation(project(":feature:grades"))
    implementation(project(":feature:map"))
    implementation(project(":feature:importer"))
    implementation(project(":feature:settings"))

    implementation(project(":widget:common"))
    implementation(project(":widget:nextclass"))
    implementation(project(":widget:today"))
    implementation(project(":widget:timeline"))
    implementation(project(":widget:week"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.roborazzi.compose)
}
