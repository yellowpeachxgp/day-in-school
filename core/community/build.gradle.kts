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
    api(libs.kotlinx.coroutines.core)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// cis-gate quick 统一调用 testDebugUnitTest；纯 JVM 模块用别名接到标准 test。
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the pure JVM community tests through the campus quick-gate task name."
    dependsOn(tasks.named("test"))
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // 契约比对测试只读仓库根目录 contracts/，不复制、不修改权威契约。
    workingDir(rootDir)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
