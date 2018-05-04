import org.gradle.api.internal.HasConvention
import org.jetbrains.intellij.IntelliJPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformJvmPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

apply<KotlinPlatformJvmPlugin>()
apply<IntelliJPlugin>()

operator fun File.div(part: String) = File(this, part)

val path = File(project.projectDir, "../../azure-toolkit-for-intellij").canonicalFile!!
if (!path.isDirectory) error("No directory at $path")

java.sourceSets["main"].java.setSrcDirs(listOf(path / "src"))
java.sourceSets["main"].resources.setSrcDirs(listOf(path / "resources"))

@Suppress("PropertyName")
val intellij_version: String by project

val sources: String by project

val dep_plugins: String by project

intellij {
//    type = "RD"
//    version = "RD-2018.1"
    version = intellij_version
    downloadSources = sources.toBooleanChecked()
}