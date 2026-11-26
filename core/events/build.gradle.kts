plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// cis-gate quick 统一调用 <module>:testDebugUnitTest；纯 JVM 模块用 test 实现同一入口，
// 同时让根目录 testDebugUnitTest 聚合时也能执行本模块测试。
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the pure JVM unit tests through the campus Android-style gate entry."
    dependsOn(tasks.named("test"))
}
