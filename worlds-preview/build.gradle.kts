plugins {
    `fabric-script`
    `publish-script`
    id("org.jetbrains.kotlin.plugin.serialization")
}

version = property("showMyWorldVersion") as String
base.archivesName = "show-my-world"


dependencies {
    implementation(project(":common"))
    include(project(":common"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.+")
}

modPublish {
    modrinthId.set(property("showMyWorldModrinthId") as String)
    curseforgeId.set(property("showMyWorldCurseforgeId") as String)
    displayName.set("Show My World")
    changelog.set(property("showMyWorldChangelog") as String)
    readme.set(layout.projectDirectory.file("README.md"))
}

modrinth {
    dependencies {
        optional.project(property("worldsModrinthId") as String)
    }
}
