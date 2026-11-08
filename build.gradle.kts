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
// GitHub Releases API 为 1.2.0 资产公布的 SHA-256（原字段形式 sha256:<hex>）。
// 来源：https://api.github.com/repos/Elytrium/LimboAPI/releases/tags/1.2.0
val limboApiSha256 = "25582ebf5567fec1c20a03ad2c6e01fa05911a76749c67e7b0192acf508f7d31"
val limboApiJarPath = layout.buildDirectory.file("limboapi/limboapi-$limboApiVersion.jar")
    .get().asFile.absolutePath

// 只捕获 String；避免 configuration cache 序列化 Gradle 脚本对象。
// 用内置 Exec 而不是 doLast 闭包：Kotlin DSL 的脚本闭包会被 Configuration
// Cache 视为脚本对象引用而拒绝序列化。
val downloadLimboApi = tasks.register<Exec>("downloadLimboApi") {
    group = "build"
    description = "Downloads LimboAPI $limboApiVersion from GitHub Releases for compileOnly use."
    inputs.property("sha256", limboApiSha256)
    outputs.file(limboApiJarPath)
    commandLine(
        "curl", "-fsSL", "--retry", "3", "--create-dirs",
        "-A", "hydwind-gate-build",
        "-o", limboApiJarPath, limboApiUrl
    )
}

// 不声明 outputs，因此每次构建都会执行；sha256sum 的额外成本可以忽略。
// 校验失败时构建中止，避免编译到被替换的 jar。
val verifyLimboApi = tasks.register<Exec>("verifyLimboApi") {
    group = "build"
    description = "Verifies the downloaded LimboAPI jar against GitHub's published SHA-256."
    dependsOn(downloadLimboApi)
    inputs.property("sha256", limboApiSha256)
    inputs.file(limboApiJarPath)
    commandLine(
        "sh", "-c",
        "printf '%s  %s\\n' '$limboApiSha256' '$limboApiJarPath' | sha256sum -c -"
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
    // LoginGateListenerTest 直接引用 Velocity 的事件枚举；compileOnly 不会进入
    // testCompileClasspath，因此测试需要单独声明依赖。
    testImplementation("com.velocitypowered:velocity-api:4.1.2-SNAPSHOT")
    // RestartTrigger 使用 Velocity 的 SLF4J；测试需要 API 与一个简单绑定。
    testImplementation("org.slf4j:slf4j-api:2.0.19")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.19")
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
    dependsOn(verifyLimboApi)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 让 CI 日志能看到每个用例；统计总数由 workflow 的 Report test results 步骤输出。
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    // 把构建所用的 LimboAPI 版本展开进 jar，供启动检查与运行时安装版本对齐。
    // 先复制成局部变量再传给 filesMatching：闭包只捕获 String，避免
    // Configuration Cache 序列化 Gradle 脚本对象（同 downloadLimboApi 的约束）。
    val limboVersion = limboApiVersion
    inputs.property("limboVersion", limboVersion)
    filesMatching("limbo-version.properties") {
        expand(mapOf("limboVersion" to limboVersion))
    }
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