rootProject.name = "Smalix"
pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
include(
    ":app",
    ":core-model", ":core-common", ":core-database", ":core-storage",
    ":engine-api", ":engine-apk", ":engine-manifest", ":engine-dex",
    ":feature-home", ":feature-projects", ":feature-inspector",
)
