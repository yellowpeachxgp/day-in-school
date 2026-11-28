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

    // W1-07 过渡迁移适配器直接读取现有 domain 目录对象，避免把药品、餐厅等
    // 大型静态目录复制出第二份事实源。该依赖不泄露到 campus-graph 的公共实体模型；
    // 调用方迁出 legacy catalog 后应移除这一实现依赖。
    implementation(project(":core:domain"))

    testImplementation(libs.junit)
}

// cis-gate quick 统一调用 testDebugUnitTest；纯 JVM 模块用别名接到标准 test，
// 不引入 Android 插件，也不改变 root 或其他模块的构建配置。
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the pure JVM campus-graph tests through the campus quick-gate task name."
    dependsOn(tasks.named("test"))
}
