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

rootProject.name = "astrawms-mobile"

// The platform-independent core (EPC/GS1 codecs, tag buffering, API client, offline queue) is its own build, so it
// can be built and tested on a plain JVM without the Android SDK; the app consumes it as com.astrawms.mobile:core.
includeBuild("core")
include(":app")
