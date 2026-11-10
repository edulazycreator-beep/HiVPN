package app.hivpn.domain.model

data class AppSettings(
    val onboardingCompleted: Boolean,
    val themeMode: ThemeMode,
    val selectedTarget: TargetId?,
    val activeProfileId: Long?,
    /** Connect when the app is opened. Boot-time connection is done by Android's Always-on VPN. */
    val autoConnectOnLaunch: Boolean,
    val biometricLock: Boolean,
) {
    companion object {
        val Default = AppSettings(
            onboardingCompleted = false,
            themeMode = ThemeMode.SYSTEM,
            selectedTarget = null,
            activeProfileId = null,
            autoConnectOnLaunch = false,
            biometricLock = false,
        )
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
