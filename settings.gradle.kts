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

rootProject.name = "haut-campus-assistant"

include(
    ":app",
    ":core:model",
    ":core:domain",
    ":core:database",
    ":core:designsystem",
    ":core:academic",
    ":core:location",
    ":feature:today",
    ":feature:timetable",
    ":feature:grades",
    ":feature:map",
    ":feature:settings",
    ":feature:importer",
    ":widget:common",
    ":widget:nextclass",
    ":widget:today",
    ":widget:timeline",
    ":widget:week"
)
