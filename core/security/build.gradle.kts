plugins {
    alias(libs.plugins.hivpn.android.library)
    alias(libs.plugins.hivpn.hilt)
}

dependencies {
    implementation(project(":core:common"))
}
