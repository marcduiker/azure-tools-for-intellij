import org.gradle.api.internal.HasConvention
import org.jetbrains.intellij.IntelliJPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformJvmPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

apply<KotlinPlatformJvmPlugin>()
apply<IntelliJPlugin>()

@Suppress("PropertyName")
val intellij_version: String by project

val sources: String by project

val dep_plugins: String by project

intellij {
    this.setPlugins("maven", dep_plugins)
    version = intellij_version
    downloadSources = sources.toBooleanChecked()
}

dependencies {
    compile(project(":platform"))
}