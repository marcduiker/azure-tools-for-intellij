import org.jetbrains.intellij.IntelliJPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformJvmPlugin

apply<KotlinPlatformJvmPlugin>()
apply<IntelliJPlugin>()

val sources: String by project

intellij {
    type = "RD"
    version = "RD-2018.1"
    downloadSources = sources.toBooleanChecked()
}

dependencies {
    compile(project(":platform"))
}
