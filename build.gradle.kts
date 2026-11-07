import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
val limboApiJar = layout.buildDirectory.file("limboapi/limboapi-$limboApiVersion.jar")

val downloadLimboApi by tasks.registering {
    group = "build"
    description = "Downloads LimboAPI $limboApiVersion from GitHub Releases for compileOnly use."
    outputs.file(limboApiJar)
    doLast {
        val destination = limboApiJar.get().asFile
        if (destination.isFile && destination.length() > 0L) {
            return@doLast
        }
        destination.parentFile.mkdirs()

        val partial = destination.toPath().resolveSibling(destination.name + ".part").toFile()
        val connection = URI(limboApiUrl).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "hydwind-gate-build")
        }
        connection.getInputStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        if (partial.length() == 0L) {
            partial.delete()
            throw GradleException("Downloaded LimboAPI jar is empty: $limboApiUrl")
        }
        Files.move(
            partial.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.1.2-SNAPSHOT")
    // 生成插件根目录的 velocity-plugin.json。
    annotationProcessor("com.velocitypowered:velocity-api:4.1.2-SNAPSHOT")

    compileOnly(files(limboApiJar))

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