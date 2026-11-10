plugins {
    alias(libs.plugins.hivpn.android.library)
    alias(libs.plugins.hivpn.hilt)
}

dependencies {
    api(libs.androidx.datastore.preferences)
}
