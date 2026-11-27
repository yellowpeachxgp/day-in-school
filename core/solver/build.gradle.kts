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
    api(project(":core:selection"))
    api(project(":core:timeline"))
    testImplementation(libs.junit)
}

// cis-gate quick 对纯 JVM 模块直接执行 test；保留 testDebugUnitTest 作为统一入口别名。
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the pure JVM solver tests through the campus quick-gate task name."
    dependsOn(tasks.named("test"))
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
