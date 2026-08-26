plugins {
    `neoforge-script`
    `publish-script`
}

version = property("showMyWorldVersion") as String
base.archivesName = "show-my-world-neoforge"

neoMod {
    modId = "showmyworld"
}

dependencies {
    jarJar(project(":common:common-neoforge"))
}

modPublish {
    loader.set("neoforge")
    modrinthId.set(property("showMyWorldModrinthId") as String)
    curseforgeId.set(property("showMyWorldCurseforgeId") as String)
    displayName.set("Show My World")
    changelog.set(property("showMyWorldChangelog") as String)
}

modrinth {
    dependencies {
        optional.project(property("worldsModrinthId") as String)
    }
}
