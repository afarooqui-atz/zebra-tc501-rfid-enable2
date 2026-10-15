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
        // Local directory for Zebra API3 AAR libraries
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "ZebraRfidTC501"
include(":app")
