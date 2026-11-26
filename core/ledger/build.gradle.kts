plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    testImplementation(libs.junit)
}

// cis-gate quick/android 门禁统一寻找 testDebugUnitTest；纯 JVM 模块用它透明转发到标准 test。
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs pure JVM unit tests through the campus Android gate task name"
    dependsOn(tasks.named("test"))
}
