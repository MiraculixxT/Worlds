import org.gradle.api.provider.Property


abstract class NeoModExtension {
    abstract val modId: Property<String>
}
