import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    id("io.github.dexman545.outlet") version "1.8.+"

}

version = project.property("version") as String
group = "de.miraculixx"

outlet.mcVersionRange = properties["fabricSupportedVersions"] as String


val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    val gameVersion: String by properties

    //
    // Fabric configuration
    //
    minecraft("com.mojang:minecraft:$gameVersion")
    println("Game Version: $gameVersion\nSupported Versions: ${outlet.mcVersionRange}")
    println("FabricLoader: ${outlet.loaderVersion()}\nFabricAPI: ${outlet.fapiVersion()}")
    implementation("net.fabricmc:fabric-loader:${outlet.loaderVersion()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${outlet.fapiVersion()}")
    implementation("net.fabricmc:fabric-language-kotlin:1.13.13+kotlin.2.4.10")

    // Provided at runtime by fabric-language-kotlin (bundled 1.11.x); declared for compile.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.+")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.+")

    // Pure-Java WebP decoder (TwelveMonkeys ImageIO) — Modrinth serves icons as WebP, which STB
    // (NativeImage.read) can't decode. Bundled into the jar via Fabric's jar-in-jar (include).
    val twelvemonkeys = "3.12.0"
    listOf(
        "com.twelvemonkeys.imageio:imageio-webp:$twelvemonkeys",
        "com.twelvemonkeys.imageio:imageio-core:$twelvemonkeys",
        "com.twelvemonkeys.common:common-lang:$twelvemonkeys",
        "com.twelvemonkeys.common:common-io:$twelvemonkeys",
        "com.twelvemonkeys.common:common-image:$twelvemonkeys",
    ).forEach {
        implementation(it)
        include(it)
    }
}

val minecraftVersion = project.property("gameVersion") as String
val loaderVersion = outlet.loaderVersion()
val kotlinLoaderVersion = "1.13.13+kotlin.2.4.10"

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)
    inputs.property("kotlin_loader_version", kotlinLoaderVersion)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion,
            "kotlin_loader_version" to kotlinLoaderVersion
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

