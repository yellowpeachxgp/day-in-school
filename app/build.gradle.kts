plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
            val keystorePath = System.getenv("SIGNING_STORE_FILE")
                ?: (findProperty("SIGNING_STORE_FILE") as? String)
                ?: rootProject.file("keystore/haut-release.jks").takeIf { it.exists() }?.absolutePath

            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                    ?: (findProperty("SIGNING_STORE_PASSWORD") as? String)
                    ?: "HautCampus2026Release"
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    ?: (findProperty("SIGNING_KEY_ALIAS") as? String)
                    ?: "haut_campus_key"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                    ?: (findProperty("SIGNING_KEY_PASSWORD") as? String)
                    ?: "HautCampus2026Release"
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
            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
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
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
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

    testImplementation(libs.junit)
}
