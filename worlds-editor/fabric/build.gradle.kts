plugins {
    `fabric-script`
    `publish-script`
}

version = property("chunkEditorVersion") as String
base.archivesName = "chunk-editor-fabric"


dependencies {
    implementation(project(":common:common-fabric"))
    include(project(":common:common-fabric"))
}

modPublish {
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
