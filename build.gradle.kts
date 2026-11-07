plugins {
    java
    // 供 `gradle runServer` 拉起 Paper 26.3 做集成验证；CI 只执行 build，不启动服务器。
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "me.hydwind"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.244-stable")
    // Paper 运行时已内置 Gson；compileOnly 避免打包重复类。
    // 测试环境没有 Paper，所以测试运行时显式引入同一 API 版本。
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.11.0")
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
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    runServer {
        // 与 build 中的 paper-api 保持一致。首次运行会下载 Paper 26.3。
        minecraftVersion("26.3")
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
