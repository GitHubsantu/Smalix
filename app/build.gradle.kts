plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.koin.core)
    implementation(libs.coroutines.core)
    listOf("core-model","core-common","core-database","core-storage","engine-api","engine-apk",
           "engine-manifest","engine-dex","feature-home","feature-projects","feature-inspector")
        .forEach { implementation(project(":$it")) }
}

compose.desktop {
    application {
        mainClass = "com.imdevops.smalix.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "Smalix"
            packageVersion = "0.1.0"
        }
    }
}
