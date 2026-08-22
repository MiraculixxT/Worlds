plugins {
    `kotlin-dsl`
}

// buildSrc has its own settings, so the root `pluginManagement` repositories do not reach it.
repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}


dependencies {
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.4.10")
    implementation("org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin:2.4.10")
    implementation("net.fabricmc:fabric-loom:1.17-SNAPSHOT")
    implementation("io.github.dexman545.outlet:io.github.dexman545.outlet.gradle.plugin:1.8.+")
    implementation("com.modrinth.minotaur:Minotaur:2.+")
    implementation("net.darkhax.curseforgegradle:CurseForgeGradle:1.3.+")
}
