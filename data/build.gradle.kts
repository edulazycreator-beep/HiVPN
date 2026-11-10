plugins {
    alias(libs.plugins.hivpn.android.library)
    alias(libs.plugins.hivpn.hilt)
}

dependencies {
    api(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
}
