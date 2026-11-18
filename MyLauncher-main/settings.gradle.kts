pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven { setUrl("https://www.jitpack.io") }   // androidphotofilters (editör filtreleri) için gerekli
    }
}
rootProject.name = "MyL"
include(":app")
include(":basic-editor")
