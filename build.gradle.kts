// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Keep local outputs out of the source tree, including native build intermediates.
val labDataDirectory = System.getenv("IZUKI_LAB_DATA")?.let(::file)
    ?: file("../../.local")
allprojects {
    layout.buildDirectory.set(labDataDirectory.resolve("android-out/izuki-app/${project.name}"))
}
