plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-common"))
    implementation(project(":engine-api"))
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.coroutines.core)
}
