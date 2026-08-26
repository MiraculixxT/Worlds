import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("net.neoforged.moddev")
}

group = rootProject.property("group") as String

val neoMod = extensions.create<NeoModExtension>("neoMod")

val fabricTwin = project("${project.parent!!.path}:${project.name.removeSuffix("-neoforge")}-fabric")
evaluationDependsOn(fabricTwin.path)

repositories {
    mavenCentral()
    maven("https://repo.nyon.dev/releases") { name = "Nyon" }
}

/** The Fabric twin's compiled classes, which are this mod's real body on both loaders */
fun Project.mainOutput() = extensions.getByType<SourceSetContainer>()["main"].output

dependencies {
    compileOnly(fabricTwin)
    runtimeOnly(fabricTwin.mainOutput()) // Dev builds
    implementation("dev.nyon:KotlinLangForge:${rootProject.property("klfVersion")}")
}

neoForge {
    version = rootProject.property("neoforgeVersion") as String
}

/**
 * The mods a dev run has to know: this one, plus everything the production jar would have nested through `jarJar`
 */
fun NamedDomainObjectContainer<net.neoforged.moddevgradle.dsl.ModModel>.registerMod(neo: Project) {
    val twin = neo.project("${neo.parent!!.path}:${neo.name.removeSuffix("-neoforge")}-fabric")
    register(neo.extensions.getByType<NeoModExtension>().modId.get()) {
        sourceSet(neo.extensions.getByType<SourceSetContainer>()["main"])
        sourceSet(twin.extensions.getByType<SourceSetContainer>()["main"])
    }
}

afterEvaluate {
    val nested = configurations.getByName("jarJar").dependencies
        .filterIsInstance<ProjectDependency>()
        .map { project(it.path) }
        .onEach { evaluationDependsOn(it.path) }

    neoForge {
        mods {
            registerMod(project)
            nested.forEach { registerMod(it) }
        }
        runs {
            register("client") {
                client()
                gameDirectory = rootProject.layout.projectDirectory.dir("run").asFile
                programArguments.addAll("--username", "Notch")
                // `-PmixinAudit` makes Mixin name every config and target it applies
                if (providers.gradleProperty("mixinAudit").isPresent) {
                    systemProperty("mixin.debug.verbose", "true")
                    systemProperty("mixin.debug.countInjections", "true")
                }
            }
        }
    }
}

tasks.jar {
    from(fabricTwin.extensions.getByType<SourceSetContainer>()["main"].output) { exclude("fabric.mod.json") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    val expansions = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to rootProject.property("gameVersion") as String,
    )
    inputs.properties(expansions)
    filteringCharset = "UTF-8"

    filesMatching("META-INF/neoforge.mods.toml") { expand(expansions) }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(TARGET_JAVA_VERSION)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(TARGET_JAVA_VERSION)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(TARGET_JAVA_VERSION.toString()))
}
