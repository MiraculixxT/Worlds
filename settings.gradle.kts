pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://maven.neoforged.net/releases") {
            name = "NeoForged"
        }
        gradlePluginPortal()
    }
}

rootProject.name = "Worlds"

fun loaderModule(mod: String, loader: String) {
    include(":$mod:$loader")
    project(":$mod:$loader").name = "$mod-$loader"
}

listOf("common", "worlds-editor", "worlds-preview", "worlds").forEach { mod ->
    loaderModule(mod, "fabric")
    loaderModule(mod, "neoforge")
}
