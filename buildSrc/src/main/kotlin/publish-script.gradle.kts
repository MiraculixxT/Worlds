import net.darkhax.curseforgegradle.Constants
import net.darkhax.curseforgegradle.TaskPublishCurseForge

plugins {
    id("com.modrinth.minotaur")
    id("net.darkhax.curseforgegradle")
    id("io.github.dexman545.outlet")
}

val publish = extensions.create<ModPublishExtension>("modPublish")

// On Mojang mappings loom produces no `remapJar`, so `jar` is the production artifact.
val modJar = tasks.named(if (tasks.names.contains("remapJar")) "remapJar" else "jar")

modrinth {
    token.set(providers.gradleProperty("modrinthToken").orElse(""))
    projectId.set(publish.modrinthId)
    loaders.addAll("fabric", "quilt")
    dependencies {
//        required.project("fabric-api")
    }

    uploadFile.set(modJar)
    versionName.set(publish.displayName.map { "$it - ${project.version}" })
    versionNumber.set(provider { project.version.toString() })
    changelog.set(publish.changelog)
    versionType.set("release")
    outlet.mcVersionRange = rootProject.property("fabricSupportedVersions") as String

    syncBodyFrom.set(publish.readme.map { it.asFile.readText() })
}


val publishCurseforge = tasks.register<TaskPublishCurseForge>("publishCurseforge") {
    group = "publishing"
    description = "Publishes the mod jar to CurseForge."
    dependsOn(modJar)

    apiToken = providers.gradleProperty("curseforgeToken").getOrElse("")
    // `-PcurseforgeDryRun` logs the request instead of sending it.
    debugMode = providers.gradleProperty("curseforgeDryRun").isPresent

    val mainFile = upload(publish.curseforgeId.get(), modJar)
    mainFile.displayName = publish.displayName.map { "$it - ${project.version}" }.get()
    mainFile.releaseType = Constants.RELEASE_TYPE_RELEASE
    mainFile.changelogType = Constants.CHANGELOG_MARKDOWN
    mainFile.changelog = publish.changelog.get()
    mainFile.addModLoader("Quilt")
    mainFile.addEnvironment("Client")
    mainFile.addGameVersion(*outlet.curseforgeMcVersions().toTypedArray())
    mainFile.addRequirement("fabric-language-kotlin")
}

tasks.register("publishMods") {
    group = "publishing"
    description = "Publishes the mod to Modrinth and CurseForge."
    dependsOn(tasks.named("modrinth"), publishCurseforge)
}
