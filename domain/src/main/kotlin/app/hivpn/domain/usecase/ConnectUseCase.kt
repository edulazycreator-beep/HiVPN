package app.hivpn.domain.usecase

import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.TargetId
import app.hivpn.domain.repository.ProfileRepository
import app.hivpn.domain.repository.SettingsRepository
import app.hivpn.domain.repository.VpnConnectionRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Resolves the active profile and the selected target, then asks the VPN layer to
 * connect. Returns false (without touching the tunnel) when nothing is selected.
 */
class ConnectUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val vpnConnection: VpnConnectionRepository,
) {
    suspend operator fun invoke(): Boolean {
        val settings = settingsRepository.settings.first()
        val profileId = settings.activeProfileId ?: profileRepository.ensureDefaultProfile()
        val profile = profileRepository.observeProfile(profileId).first()
            ?: ConnectionProfile.default()
        val target = profile.preferredServerId?.let { TargetId.Directory(it) }
            ?: settings.selectedTarget
            ?: return false
        vpnConnection.connect(profile, target)
        return true
    }
}
