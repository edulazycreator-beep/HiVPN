plugins {
    alias(libs.plugins.hivpn.android.library)
    alias(libs.plugins.hivpn.hilt)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":vpn:api"))
    implementation(project(":vpn:wireguard"))
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(libs.androidx.core.ktx)
}
