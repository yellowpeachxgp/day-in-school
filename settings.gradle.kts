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
    ":widget:week",
    ":core:timeline"
)

include(":core:ledger")
include(":core:events")
include(":core:textextract")
include(":core:solver")
include(":core:automation")
include(":core:community")
include(":core:automation-android")
include(":core:campus-graph")
include(":core:ui-runtime")
include(":core:vault")
include(":core:knowledge")
include(":feature:knowledge")
include(":core:ai")
include(":core:ai-android")
include(":feature:automation")
include(":core:degree")
include(":feature:degree")
include(":core:inbox")
