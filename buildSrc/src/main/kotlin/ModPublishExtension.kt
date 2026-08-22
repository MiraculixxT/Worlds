import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property


interface ModPublishExtension {
    /** Modrinth project id or slug. */
    val modrinthId: Property<String>

    /** CurseForge **numeric** project id — the site shows it under "Project ID", slugs are not accepted. */
    val curseforgeId: Property<String>

    /** Human name, used to build `"<name> - <version>"`. */
    val displayName: Property<String>

    val changelog: Property<String>

    /** Uploaded as the project body on every publish. */
    val readme: RegularFileProperty
}
