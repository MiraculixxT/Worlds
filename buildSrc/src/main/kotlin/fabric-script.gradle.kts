import net.fabricmc.loom.api.fabricapi.FabricApiExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    // `-remap`, not plain `net.fabricmc.fabric-loom`: that id is `LoomNoRemapGradlePlugin`, which
    // finalizes `disableObfuscation = true` and so rejects Mojang mappings outright
    id("net.fabricmc.fabric-loom-remap")
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
    // 1.21.x is a *mapped* target: the runtime namespace is intermediary, so the compile stays on
    // Mojang mappings (what the NeoForge copy needs) and `remapJar` produces the Fabric artifact.
    "mappings"(loom.officialMojangMappings())
    println("Game Version: $gameVersion\nSupported Versions: ${outlet.mcVersionRange}")
    println("FabricLoader: ${outlet.loaderVersion()}\nFabricAPI: ${outlet.fapiVersion()}")
    // fabric-api is pulled module by module here, so a module script can add its own
    extra["fapiVersion"] = outlet.fapiVersion()
    "modImplementation"("net.fabricmc:fabric-loader:${outlet.loaderVersion()}")
    // Required for `assets/<modid>/` to be seen at all: fabric-loader ships no resource-pack
    // integration, `ModResourcePackCreator` lives in fabric-api's `fabric-resource-loader-v1`.
    "modImplementation"(project.extensions.getByType<FabricApiExtension>().module("fabric-resource-loader-v1", outlet.fapiVersion()))

    //
    // Kotlin libraries
    //
    val flkVersion = outlet.latestModrinthModVersion("fabric-language-kotlin", outlet.mcVersions())
    println("Fabric Language Kotlin: $flkVersion")
    "modImplementation"("net.fabricmc:fabric-language-kotlin:$flkVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.+")

    fun compileOnlyIsolated(notation: String) = "compileOnly"(notation) {
        this.isTransitive = false
    }
    compileOnlyIsolated("net.neoforged.fancymodloader:loader:$FML_VERSION")
    compileOnlyIsolated("net.neoforged:mergetool:$MERGETOOL_VERSION:api")
    compileOnlyIsolated("org.apache.maven:maven-artifact:$MAVEN_ARTIFACT_VERSION")
}

loom {
    mixin {
        useLegacyMixinAp = true
        defaultRefmapName = "${project.name}.refmap.json"
    }

    runs {
        configureEach { runDir("../../run-legacy") }

        named("server") {
            runDir("../../run-legacy/server")
            if (providers.gradleProperty("mixinAudit").isPresent) {
                property("mixin.debug.verbose", "true")
                property("mixin.debug.countInjections", "true")
            }
        }

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

tasks.matching { it.name == "runServer" }.configureEach {
    doFirst {
        val dir = rootProject.layout.projectDirectory.dir("run-legacy/server").asFile
        dir.mkdirs()
        val eula = File(dir, "eula.txt")
        if (!eula.exists()) eula.writeText("eula=true\n")
    }
}
