plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-common"))
    implementation(project(":engine-api"))
    testImplementation(libs.coroutines.core)
}
