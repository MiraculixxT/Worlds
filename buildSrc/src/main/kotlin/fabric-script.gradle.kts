import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("net.fabricmc.fabric-loom")
    id("io.github.dexman545.outlet")
}

group = rootProject.property("group") as String

val gameVersion = rootProject.property("gameVersion") as String
outlet.mcVersionRange = rootProject.property("supportedVersions") as String

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
}

dependencies {
    val gameVersion: String by properties
    outlet.mcVersionRange = properties["supportedVersions"] as String

    //
    // Fabric configuration
    //
    minecraft("com.mojang:minecraft:$gameVersion")
    println("Game Version: $gameVersion\nSupported Versions: ${outlet.mcVersionRange}")
    println("FabricLoader: ${outlet.loaderVersion()}\nFabricAPI: ${outlet.fapiVersion()}")
    implementation("net.fabricmc:fabric-loader:${outlet.loaderVersion()}")
//    implementation("net.fabricmc.fabric-api:fabric-api:${outlet.fapiVersion()}")

    //
    // Kotlin libraries
    //
    val flkVersion = outlet.latestModrinthModVersion("fabric-language-kotlin", outlet.mcVersions())
    println("Fabric Language Kotlin: $flkVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$flkVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.+")

    fun compileOnlyIsolated(notation: String) = "compileOnly"(notation) {
        this.isTransitive = false
    }
    compileOnlyIsolated("net.neoforged.fancymodloader:loader:$FML_VERSION")
    compileOnlyIsolated("net.neoforged:mergetool:$MERGETOOL_VERSION:api")
    compileOnlyIsolated("org.apache.maven:maven-artifact:$MAVEN_ARTIFACT_VERSION")
}

loom {
    runs {
        configureEach { runDir("../../run") }

        named("client") {
            programArgs("--username", "Notch")
            // `-PmixinAudit` is the only way to see a mixin succeed
            if (providers.gradleProperty("mixinAudit").isPresent) {
                property("mixin.debug.verbose", "true")
                property("mixin.debug.countInjections", "true")
            }
        }
    }
}

tasks.processResources {
    val expansions = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to gameVersion,
    )
    inputs.properties(expansions)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") { expand(expansions) }
}


java {
    toolchain.languageVersion = JavaLanguageVersion.of(TARGET_JAVA_VERSION)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(TARGET_JAVA_VERSION)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(TARGET_JAVA_VERSION.toString()))
}
