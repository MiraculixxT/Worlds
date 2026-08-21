plugins {
    `fabric-script`
    `publish-script`
}

version = property("chunkEditorVersion") as String
base.archivesName = "chunk-editor"


dependencies {
    implementation(project(":common"))
    include(project(":common"))
}

modPublish {
    modrinthId.set(property("chunkEditorModrinthId") as String)
    displayName.set("Chunk Editor")
    changelog.set(property("chunkEditorChangelog") as String)
    readme.set(layout.projectDirectory.file("README.md"))
}

modrinth {
    dependencies {
        optional.project(property("worldsModrinthId") as String)
    }
}
