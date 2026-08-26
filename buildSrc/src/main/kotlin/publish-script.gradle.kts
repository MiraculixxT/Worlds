import net.darkhax.curseforgegradle.Constants
import net.darkhax.curseforgegradle.TaskPublishCurseForge

plugins {
    id("com.modrinth.minotaur")
    id("net.darkhax.curseforgegradle")
    id("io.github.dexman545.outlet")
}

val publish = extensions.create<ModPublishExtension>("modPublish")
publish.loader.convention("fabric")

// On Mojang mappings loom produces no `remapJar`, so `jar` is the production artifact
val modJar = tasks.named(if (tasks.names.contains("remapJar")) "remapJar" else "jar")

val isFabric = publish.loader.map { it == "fabric" }

val publishedVersion = publish.loader.map { "${project.version}+$it" }

modrinth {
    token.set(providers.gradleProperty("modrinthToken").orElse(""))
    projectId.set(publish.modrinthId)
    loaders.addAll(publish.loader.map { if (it == "fabric") listOf("fabric", "quilt") else listOf("neoforge") })
    dependencies {
//        required.project("fabric-api")
    }

    uploadFile.set(modJar)
    versionName.set(publish.displayName.map { "$it - ${project.version} (${publish.loader.get()})" })
    versionNumber.set(publishedVersion)
    changelog.set(publish.changelog)
    versionType.set("release")
    outlet.mcVersionRange = rootProject.property("supportedVersions") as String

    // Only the Fabric module carries the readme, so only it syncs the shared project body.
    syncBodyFrom.set(publish.readme.map { it.asFile.readText() }.orElse(""))
}


val publishCurseforge = tasks.register<TaskPublishCurseForge>("publishCurseforge") {
    group = "publishing"
    description = "Publishes the mod jar to CurseForge."
    dependsOn(modJar)

    apiToken = providers.gradleProperty("curseforgeToken").getOrElse("")
    // `-PcurseforgeDryRun` logs the request instead of sending it.
    debugMode = providers.gradleProperty("curseforgeDryRun").isPresent

    val mainFile = upload(publish.curseforgeId.get(), modJar)
    mainFile.displayName = publish.displayName.map { "$it - ${project.version} (${publish.loader.get()})" }.get()
    mainFile.releaseType = Constants.RELEASE_TYPE_RELEASE
    mainFile.changelogType = Constants.CHANGELOG_MARKDOWN
    mainFile.changelog = publish.changelog.get()
    mainFile.addEnvironment("Client")
    mainFile.addGameVersion(*outlet.curseforgeMcVersions().toTypedArray())

    if (isFabric.get()) {
        mainFile.addModLoader("Fabric")
        mainFile.addModLoader("Quilt")
        mainFile.addRequirement("fabric-language-kotlin")
    } else {
        mainFile.addModLoader("NeoForge")
        mainFile.addRequirement("kotlin-lang-forge")
    }
}

tasks.register("publishMods") {
    group = "publishing"
    description = "Publishes the mod to Modrinth and CurseForge."
    dependsOn(tasks.named("modrinth"), publishCurseforge)
}
