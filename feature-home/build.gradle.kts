plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    implementation(project(":core-model"))
    implementation(project(":engine-api"))
    implementation(libs.coroutines.core)
}
