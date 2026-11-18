plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":engine-api"))
    implementation(project(":core-common"))
    implementation(libs.coroutines.core)
    testImplementation(project(":engine-apk"))
}
