plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    api(project(":core:model"))
    api(project(":core:school-packages"))
    api(project(":core:campus-graph"))
    api(project(":core:timeline"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// cis-gate quick 统一调用 testDebugUnitTest；纯 JVM 模块用别名接到标准 test，
// 不引入 Android 插件，也不改变 root 或其他模块的构建配置。
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the pure JVM guide tests through the campus quick-gate task name."
    dependsOn(tasks.named("test"))
}
