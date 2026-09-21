plugins {
    `fabric-script`
    `publish-script`
    id("org.jetbrains.kotlin.plugin.serialization")
}

version = property("worldsVersion") as String
base.archivesName = "worlds-fabric"


dependencies {
    implementation(project(path = ":worlds-editor:worlds-editor-fabric", configuration = "namedElements"))
    include(project(":worlds-editor:worlds-editor-fabric"))

    implementation(project(path = ":worlds-preview:worlds-preview-fabric", configuration = "namedElements"))
    include(project(":worlds-preview:worlds-preview-fabric"))

    implementation(project(path = ":common:common-fabric", configuration = "namedElements"))
    include(project(":common:common-fabric"))

    // `namedElements` exports nothing, so the JiJ'd chunkeditor's fabric-api modules must be named here
    // as well or a dev run fails its `depends`
    val fabricApi = project.extensions.getByType<net.fabricmc.loom.api.fabricapi.FabricApiExtension>()
    val fapiVersion = extra["fapiVersion"] as String
    "modRuntimeOnly"(fabricApi.module("fabric-networking-api-v1", fapiVersion))
    "modRuntimeOnly"(fabricApi.module("fabric-lifecycle-events-v1", fapiVersion))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.+")

    // Pure-Java WebP decoder (TwelveMonkeys ImageIO)
    val twelvemonkeys = "3.12.0"
    listOf(
        "com.twelvemonkeys.imageio:imageio-webp:$twelvemonkeys",
        "com.twelvemonkeys.imageio:imageio-core:$twelvemonkeys",
        "com.twelvemonkeys.common:common-lang:$twelvemonkeys",
        "com.twelvemonkeys.common:common-io:$twelvemonkeys",
        "com.twelvemonkeys.common:common-image:$twelvemonkeys",
    ).forEach {
        "implementation"(it)
        "include"(it)
    }
}

modrinth {
    dependencies {
        //required.project(property("chunkEditorModrinthId") as String)
        //required.project(property("showMyWorldModrinthId") as String)
    }
}

modPublish {
    loader.set("fabric")
    modrinthId.set(property("worldsModrinthId") as String)
    curseforgeId.set(property("worldsCurseforgeId") as String)
    displayName.set("BetterWorlds")
    changelog.set(property("worldsChangelog") as String)
    readme.set(rootProject.layout.projectDirectory.file("README.md"))
}
