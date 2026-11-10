plugins {
    alias(libs.plugins.hivpn.android.library)
    alias(libs.plugins.hivpn.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    buildFeatures.buildConfig = true
    defaultConfig {
        // Both values come from gradle.properties (or -P on the command line / CI).
        // An empty base URL means "no backend configured": every call fails with
        // ApiNotConfigured instead of contacting a default host.
        buildConfigField(
            "String", "API_BASE_URL",
            "\"${providers.gradleProperty("hivpn.apiBaseUrl").getOrElse("")}\"",
        )
        // Base64 X.509 SubjectPublicKeyInfo of the ECDSA P-256 key that signs the directory.
        // Comma-separated "sha256/…" pins for the API host (at least two, or pinning is off).
        buildConfigField(
            "String", "API_PINS",
            "\"${providers.gradleProperty("hivpn.apiPins").getOrElse("")}\"",
        )
        buildConfigField(
            "String", "DIRECTORY_PUBLIC_KEY",
            "\"${providers.gradleProperty("hivpn.directoryPublicKey").getOrElse("")}\"",
        )
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    api(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
}
