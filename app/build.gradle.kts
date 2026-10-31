plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cn.edu.haut.campus"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.edu.haut.campus"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
