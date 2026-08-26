plugins {
    `neoforge-script`
    `publish-script`
}

version = property("worldsVersion") as String
base.archivesName = "worlds-neoforge"

neoMod {
    modId = "worlds"
}

dependencies {
    jarJar(project(":worlds-editor:worlds-editor-neoforge"))
    jarJar(project(":worlds-preview:worlds-preview-neoforge"))
    jarJar(project(":common:common-neoforge"))

    val twelvemonkeys = "3.12.0"
    listOf(
        "com.twelvemonkeys.imageio:imageio-webp",
        "com.twelvemonkeys.imageio:imageio-core",
        "com.twelvemonkeys.common:common-lang",
        "com.twelvemonkeys.common:common-io",
        "com.twelvemonkeys.common:common-image",
    ).forEach { jarJar("$it:$twelvemonkeys") }
}

modPublish {
    loader.set("neoforge")
    modrinthId.set(property("worldsModrinthId") as String)
    curseforgeId.set(property("worldsCurseforgeId") as String)
    displayName.set("BetterWorlds")
    changelog.set(property("worldsChangelog") as String)
}

modrinth {
    dependencies {
        //required.project(property("chunkEditorModrinthId") as String)
        //required.project(property("showMyWorldModrinthId") as String)
    }
}
