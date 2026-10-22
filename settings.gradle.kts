pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

val prCandidates = listOfNotNull(
    providers.gradleProperty("prRoot").orNull?.let { file(it) },
    file("vendor/pr"),
    file("../pr.oo.or.id"),
)
val prRoot = prCandidates.firstOrNull { java.io.File(it, "android/session-core/build.gradle.kts").exists() }
    ?: error(
        "Cannot locate the `pr` project in any of: " +
            prCandidates.joinToString { it.path } +
            ". Run `git submodule update --init vendor/pr` and " +
            "`git -C vendor/pr submodule update --init vendor/termlib`, or pass -PprRoot=<path to pr>."
    )

rootProject.name = "hr"
include(":app")

include(":termlib")
project(":termlib").projectDir = java.io.File(prRoot, "android/termlib")
include(":proot-engine")
project(":proot-engine").projectDir = java.io.File(prRoot, "android/proot-engine")
include(":session-core")
project(":session-core").projectDir = java.io.File(prRoot, "android/session-core")
