plugins {
    `fabric-script`
    `publish-script`
    id("org.jetbrains.kotlin.plugin.serialization")
}

version = property("worldsVersion") as String


dependencies {
    implementation(project(":worlds-editor"))
    include(project(":worlds-editor"))

    implementation(project(":common"))
    include(project(":common"))

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
    }
}

modPublish {
    modrinthId.set(property("worldsModrinthId") as String)
    displayName.set("BetterWorlds")
    changelog.set(property("worldsChangelog") as String)
    readme.set(rootProject.layout.projectDirectory.file("README.md"))
}
