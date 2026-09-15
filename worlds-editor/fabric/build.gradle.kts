plugins {
    `fabric-script`
    `publish-script`
    id("org.jetbrains.kotlin.plugin.serialization")
}

version = property("chunkEditorVersion") as String
base.archivesName = "chunk-editor-fabric"


dependencies {
    implementation(project(":common:common-fabric"))
    include(project(":common:common-fabric"))

    // Runtime comes from fabric-language-kotlin / KotlinLangForge, so this is not JiJ'd.
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
