plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "club.mcqi.macesurvival"
version = "1.0.0"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    implementation("net.kyori:adventure-text-minimessage:4.24.0")
    implementation("net.kyori:adventure-text-serializer-gson:4.24.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.24.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.yaml:snakeyaml:2.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    compileTestJava {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    processResources {
        inputs.property("pluginVersion", pluginVersion)
        filteringCharset = "UTF-8"
        filesMatching(listOf("plugin.yml")) {
            expand("version" to pluginVersion)
        }
    }
    test {
        useJUnitPlatform()
    }
    shadowJar {
        archiveClassifier.set("")
        relocate("net.kyori.adventure.text.minimessage", "club.mcqi.macesurvival.libs.minimessage")
        relocate("net.kyori.adventure.text.serializer.gson", "club.mcqi.macesurvival.libs.adventure.gson")
        relocate("net.kyori.adventure.text.serializer.legacy", "club.mcqi.macesurvival.libs.adventure.legacy")
    }
    build {
        dependsOn(shadowJar)
    }
}
