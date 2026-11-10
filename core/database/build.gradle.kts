plugins {
    alias(libs.plugins.hivpn.android.library)
    alias(libs.plugins.hivpn.hilt)
    alias(libs.plugins.room)
}

room {
    // Schemas are committed so every migration can be tested (docs/TESTING.md).
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
