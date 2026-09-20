plugins {
    `fabric-script`
    `publish-script`
    id("org.jetbrains.kotlin.plugin.serialization")
}

version = property("chunkEditorVersion") as String
base.archivesName = "chunk-editor-fabric"


dependencies {
    implementation(project(path = ":common:common-fabric", configuration = "namedElements"))
    include(project(":common:common-fabric"))

    // The remote editor's own protocol and the server hooks behind it
    val fabricApi = project.extensions.getByType<net.fabricmc.loom.api.fabricapi.FabricApiExtension>()
    val fapiVersion = extra["fapiVersion"] as String
    "modImplementation"(fabricApi.module("fabric-networking-api-v1", fapiVersion))
    "modImplementation"(fabricApi.module("fabric-lifecycle-events-v1", fapiVersion))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.+")
}

modPublish {
    environments.set(setOf("Client", "Server"))
    loader.set("fabric")
    modrinthId.set(property("chunkEditorModrinthId") as String)
    curseforgeId.set(property("chunkEditorCurseforgeId") as String)
    displayName.set("Chunk Editor")
    changelog.set(property("chunkEditorChangelog") as String)
    readme.set(layout.projectDirectory.file("README.md"))
}

modrinth {
    dependencies {
        optional.project(property("worldsModrinthId") as String)
    }
}
