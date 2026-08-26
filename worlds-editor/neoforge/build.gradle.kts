plugins {
    `neoforge-script`
    `publish-script`
}

version = property("chunkEditorVersion") as String
base.archivesName = "chunk-editor-neoforge"

neoMod {
    modId = "chunkeditor"
}

dependencies {
    jarJar(project(":common:common-neoforge"))
}

modPublish {
    loader.set("neoforge")
    modrinthId.set(property("chunkEditorModrinthId") as String)
    curseforgeId.set(property("chunkEditorCurseforgeId") as String)
    displayName.set("Chunk Editor")
    changelog.set(property("chunkEditorChangelog") as String)
}

modrinth {
    dependencies {
        optional.project(property("worldsModrinthId") as String)
    }
}
