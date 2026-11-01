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

rootProject.name = "day-in-school"

include(
    ":app",
    ":core:model",
    ":core:domain",
    ":core:database",
    ":core:reminder",
    ":core:designsystem",
    ":core:academic",
    ":core:campusservices",
    ":core:location",
    ":feature:today",
    ":feature:timetable",
    ":feature:grades",
    ":feature:map",
    ":feature:settings",
    ":feature:importer",
    ":core:academic-protocols",
    ":core:school-packages",
    ":core:academic-plan",
    ":core:selection",
    ":widget:common",
    ":widget:nextclass",
    ":widget:today",
    ":widget:timeline",
    ":widget:week"
)
