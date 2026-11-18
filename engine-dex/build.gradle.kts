plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":engine-api"))
    implementation(project(":core-common"))
    implementation(libs.coroutines.core)
    implementation(libs.dexlib2)
    implementation(libs.smali)
}
