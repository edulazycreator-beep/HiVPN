plugins {
    alias(libs.plugins.hivpn.android.application)
    alias(libs.plugins.hivpn.android.compose)
    alias(libs.plugins.hivpn.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.hivpn"

    defaultConfig {
        applicationId = providers.gradleProperty("hivpn.applicationId").get()
        versionName = providers.gradleProperty("hivpn.versionName").get()
        versionCode = providers.gradleProperty("hivpn.versionCode").get().toInt()
        vectorDrawables.useSupportLibrary = true
    }

    // Release signing comes only from environment variables (GitHub Secrets in CI).
    // No keystore or password is ever stored in the repository.
    val keystorePath = System.getenv("HIVPN_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("HIVPN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HIVPN_KEY_ALIAS")
                keyPassword = System.getenv("HIVPN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.data)
    implementation(projects.core.security)
    implementation(projects.core.network)
    implementation(projects.vpn.api)
    implementation(projects.vpn.wireguard)
    implementation(projects.vpn.service)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.dashboard)
    implementation(projects.feature.servers)
    implementation(projects.feature.settings)
    implementation(projects.feature.profiles)
    implementation(projects.feature.diagnostics)
    implementation(projects.feature.statistics)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.biometric)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
