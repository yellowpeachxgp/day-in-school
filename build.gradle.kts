import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

val verMajor = versionProps.getProperty("VERSION_MAJOR", "0").toInt()
val verMinor = versionProps.getProperty("VERSION_MINOR", "1").toInt()
val verPatch = versionProps.getProperty("VERSION_PATCH", "0").toInt()
val defaultVersionName = "$verMajor.$verMinor.$verPatch"
val defaultVersionCode = (verMajor * 10000) + (verMinor * 100) + verPatch

val resolvedVersionName: String = (findProperty("versionName") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("APP_VERSION_NAME")?.removePrefix("v")?.takeIf { it.isNotBlank() }
    ?: defaultVersionName

val resolvedVersionCode: Int = (findProperty("versionCode") as? String)?.toIntOrNull()
    ?: System.getenv("APP_VERSION_CODE")?.toIntOrNull()
    ?: versionProps.getProperty("VERSION_BUILD")?.toIntOrNull()
    ?: defaultVersionCode

ext.set("appVersionName", resolvedVersionName)
ext.set("appVersionCode", resolvedVersionCode)
ext.set("compileSdkVersion", versionProps.getProperty("COMPILE_SDK", "36").toInt())
ext.set("targetSdkVersion", versionProps.getProperty("TARGET_SDK", "36").toInt())
ext.set("minSdkVersion", versionProps.getProperty("MIN_SDK", "31").toInt())

tasks.register("printVersion") {
    group = "versioning"
    description = "Prints resolved semantic versionName and versionCode for CI/CD"
    doLast {
        println("VERSION_NAME=$resolvedVersionName")
        println("VERSION_CODE=$resolvedVersionCode")
    }
}

fun bumpVersion(majorDelta: Int = 0, minorDelta: Int = 0, patchDelta: Int = 0) {
    val props = Properties().apply {
        versionPropsFile.inputStream().use { load(it) }
    }
    var major = props.getProperty("VERSION_MAJOR", "0").toInt()
    var minor = props.getProperty("VERSION_MINOR", "1").toInt()
    var patch = props.getProperty("VERSION_PATCH", "0").toInt()

    when {
        majorDelta > 0 -> {
            major += majorDelta
            minor = 0
            patch = 0
        }
        minorDelta > 0 -> {
            minor += minorDelta
            patch = 0
        }
        patchDelta > 0 -> {
            patch += patchDelta
        }
    }
    val buildCode = (major * 10000) + (minor * 100) + patch
    versionPropsFile.writeText(
        """
        # HAUT Campus Assistant — Semantic Version & Android SDK Configuration
        # Single source of truth for local builds and GitHub Actions CI/CD releases.
        VERSION_MAJOR=$major
        VERSION_MINOR=$minor
        VERSION_PATCH=$patch
        VERSION_BUILD=$buildCode

        # Target the latest Android version (Android 16 / API 36, minSdk Android 12 / API 31)
        COMPILE_SDK=${props.getProperty("COMPILE_SDK", "36")}
        TARGET_SDK=${props.getProperty("TARGET_SDK", "36")}
        MIN_SDK=${props.getProperty("MIN_SDK", "31")}
        """.trimIndent() + "\n"
    )
    println("Bumped version to $major.$minor.$patch (versionCode=$buildCode)")
}

tasks.register("bumpPatch") {
    group = "versioning"
    description = "Increment VERSION_PATCH in version.properties"
    doLast { bumpVersion(patchDelta = 1) }
}

tasks.register("bumpMinor") {
    group = "versioning"
    description = "Increment VERSION_MINOR and reset VERSION_PATCH in version.properties"
    doLast { bumpVersion(minorDelta = 1) }
}

tasks.register("bumpMajor") {
    group = "versioning"
    description = "Increment VERSION_MAJOR and reset MINOR/PATCH in version.properties"
    doLast { bumpVersion(majorDelta = 1) }
}
