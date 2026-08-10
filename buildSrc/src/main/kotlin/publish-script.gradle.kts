plugins {
    id("com.modrinth.minotaur")
    id("io.github.dexman545.outlet")
}

val publish = extensions.create<ModPublishExtension>("modPublish")

modrinth {
    token.set(providers.gradleProperty("modrinthToken").orElse(""))
    projectId.set(publish.modrinthId)
    loaders.addAll("fabric", "quilt")
    dependencies {
//        required.project("fabric-api")
    }

    uploadFile.set(tasks.named(if (tasks.names.contains("remapJar")) "remapJar" else "jar"))
    versionName.set(publish.displayName.map { "$it - ${project.version}" })
    versionNumber.set(provider { project.version.toString() })
    changelog.set(publish.changelog)
    versionType.set("release")
    outlet.mcVersionRange = rootProject.property("fabricSupportedVersions") as String

    syncBodyFrom.set(publish.readme.map { it.asFile.readText() })
}
