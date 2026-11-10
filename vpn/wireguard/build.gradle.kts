plugins {
    alias(libs.plugins.hivpn.android.library)
    alias(libs.plugins.hivpn.hilt)
}

dependencies {
    api(project(":vpn:api"))
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    implementation(project(":core:database"))
    // Official WireGuard userspace tunnel (wireguard-go + GoBackend). Apache-2.0 / MIT.
    // Its AAR manifest declares GoBackend$VpnService with BIND_VPN_SERVICE.
    api(libs.wireguard.tunnel)
}
