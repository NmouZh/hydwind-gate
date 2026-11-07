plugins {
    java
    // 把 SnakeYAML 与 Gson 打进插件 jar；不依赖代理服或 LimboAPI 提供这些类。
    id("com.gradleup.shadow") version "9.6.1"
}

group = "me.hydwind"
version = "0.1.0"

repositories {
    mavenCentral()
    // Velocity API 4.1.2-SNAPSHOT 只发布在这里。
    maven("https://repo.papermc.io/repository/maven-public/")
}

// LimboAPI 1.2.0 没有发布到 Elytrium Maven 或 JitPack，只能从 GitHub Release 取。
// 只用于编译（compileOnly），运行时由代理服单独安装 limboapi-1.2.0.jar。
val limboApiVersion = "1.2.0"
val limboApiUrl = "https://github.com/Elytrium/LimboAPI/releases/download/" +
    "$limboApiVersion/limboapi-$limboApiVersion.jar"
val limboApiJarPath = layout.buildDirectory.file("limboapi/limboapi-$limboApiVersion.jar")
    .get().asFile.absolutePath

// 只捕获 String；避免 configuration cache 序列化 Gradle 脚本对象。
// 用内置 Exec 而不是 doLast 闭包：Kotlin DSL 的脚本闭包会被 Configuration
// Cache 视为脚本对象引用而拒绝序列化。
val downloadLimboApi = tasks.register<Exec>("downloadLimboApi") {
    group = "build"
    description = "Downloads LimboAPI $limboApiVersion from GitHub Releases for compileOnly use."
    outputs.file(limboApiJarPath)
    commandLine(
        "curl", "-fsSL", "--retry", "3", "--create-dirs",
        "-A", "hydwind-gate-build",
        "-o", limboApiJarPath, limboApiUrl
    )
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.1.2-SNAPSHOT")
    // 生成插件根目录的 velocity-plugin.json。
    annotationProcessor("com.velocitypowered:velocity-api:4.1.2-SNAPSHOT")

    compileOnly(files(limboApiJarPath))

    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // JDK 23+ 不再默认运行注解处理；velocity-plugin.json 依赖它。
    options.compilerArgs.add("-proc:full")
    dependsOn(downloadLimboApi)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

// 只产出包含运行时依赖的插件 jar，避免 CI 误传瘦 jar。
tasks.jar {
    enabled = false
}

tasks.named<Jar>("shadowJar") {
    archiveClassifier.set("")
}

// 显式让 `gradle build` 产出插件 jar，不依赖插件生命周期的默认接线。
tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}