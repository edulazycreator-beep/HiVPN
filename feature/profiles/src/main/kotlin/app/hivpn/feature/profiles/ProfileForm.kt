package app.hivpn.feature.profiles

import app.hivpn.domain.model.ConnectionProfile
import app.hivpn.domain.model.DnsPreset
import app.hivpn.domain.model.DnsSettings
import app.hivpn.domain.model.SplitTunnelSettings
import app.hivpn.domain.usecase.ValidateDnsServersUseCase
import app.hivpn.domain.util.Cidr

enum class DnsChoice { AUTOMATIC, CLOUDFLARE, GOOGLE, QUAD9, CUSTOM }
enum class SplitMode { ALL, ONLY_SELECTED, BYPASS_SELECTED }

/** Raw editor fields as typed; [validate] turns them into a profile or field errors. */
data class ProfileForm(
    val id: Long = 0,
    val name: String = "",
    val dns: DnsChoice = DnsChoice.AUTOMATIC,
    val customDns: String = "",
    val mtu: String = "",
    val keepalive: String = "",
    val allowLan: Boolean = false,
    val excludedRoutes: String = "",
    val splitMode: SplitMode = SplitMode.ALL,
    val packages: Set<String> = emptySet(),
    val preferredServerId: String? = null,
) {
    companion object {
        fun from(p: ConnectionProfile): ProfileForm {
            val dns = p.dns
            val preset = (dns as? DnsSettings.Custom)?.let { c -> DnsPreset.entries.firstOrNull { it.servers == c.servers } }
            return ProfileForm(
                id = p.id,
                name = p.name,
                dns = when {
                    dns is DnsSettings.Automatic -> DnsChoice.AUTOMATIC
                    preset == DnsPreset.CLOUDFLARE -> DnsChoice.CLOUDFLARE
                    preset == DnsPreset.GOOGLE -> DnsChoice.GOOGLE
                    preset == DnsPreset.QUAD9 -> DnsChoice.QUAD9
                    else -> DnsChoice.CUSTOM
                },
                customDns = (dns as? DnsSettings.Custom)?.servers?.joinToString("\n").orEmpty(),
                mtu = p.mtu?.toString().orEmpty(),
                keepalive = p.persistentKeepalive?.toString().orEmpty(),
                allowLan = p.allowLanAccess,
                excludedRoutes = p.excludedRoutes.joinToString("\n"),
                splitMode = when (p.splitTunnel) {
                    SplitTunnelSettings.AllApps -> SplitMode.ALL
                    is SplitTunnelSettings.OnlySelected -> SplitMode.ONLY_SELECTED
                    is SplitTunnelSettings.BypassSelected -> SplitMode.BYPASS_SELECTED
                },
                packages = when (val s = p.splitTunnel) {
                    SplitTunnelSettings.AllApps -> emptySet()
                    is SplitTunnelSettings.OnlySelected -> s.packages
                    is SplitTunnelSettings.BypassSelected -> s.packages
                },
                preferredServerId = p.preferredServerId,
            )
        }
    }
}

enum class FormError { NAME, DNS_EMPTY, DNS_TOO_MANY, DNS_INVALID, MTU, KEEPALIVE, ROUTE, NO_APPS }

data class Validation(val profile: ConnectionProfile?, val errors: Map<FormError, String?>)

/** Pure validation, unit-tested in ProfileFormTest. */
fun ProfileForm.validate(validateDns: ValidateDnsServersUseCase = ValidateDnsServersUseCase()): Validation {
    val errors = mutableMapOf<FormError, String?>()
    if (name.isBlank()) errors[FormError.NAME] = null

    val dnsSettings: DnsSettings = when (dns) {
        DnsChoice.AUTOMATIC -> DnsSettings.Automatic
        DnsChoice.CLOUDFLARE -> DnsSettings.Custom(DnsPreset.CLOUDFLARE.servers)
        DnsChoice.GOOGLE -> DnsSettings.Custom(DnsPreset.GOOGLE.servers)
        DnsChoice.QUAD9 -> DnsSettings.Custom(DnsPreset.QUAD9.servers)
        DnsChoice.CUSTOM -> when (val r = validateDns(customDns.split('\n', ',', ' '))) {
            is ValidateDnsServersUseCase.Result.Valid -> DnsSettings.Custom(r.servers)
            ValidateDnsServersUseCase.Result.Empty -> { errors[FormError.DNS_EMPTY] = null; DnsSettings.Automatic }
            ValidateDnsServersUseCase.Result.TooMany -> { errors[FormError.DNS_TOO_MANY] = null; DnsSettings.Automatic }
            is ValidateDnsServersUseCase.Result.InvalidAddress -> { errors[FormError.DNS_INVALID] = r.value; DnsSettings.Automatic }
        }
    }

    val mtuValue = mtu.trim().takeIf { it.isNotEmpty() }?.let { text ->
        text.toIntOrNull()?.takeIf { it in ConnectionProfile.MTU_MIN..ConnectionProfile.MTU_MAX }
            ?: run { errors[FormError.MTU] = null; null }
    }
    val keepaliveValue = keepalive.trim().takeIf { it.isNotEmpty() }?.let { text ->
        text.toIntOrNull()?.takeIf { it in 0..65535 }?.takeIf { it > 0 }
            ?: run { if (text != "0") errors[FormError.KEEPALIVE] = null; null }
    }

    val routes = excludedRoutes.split('\n', ',', ' ').map(String::trim).filter(String::isNotEmpty)
    routes.firstOrNull { Cidr.parse(it) == null }?.let { errors[FormError.ROUTE] = it }

    if (splitMode != SplitMode.ALL && packages.isEmpty()) errors[FormError.NO_APPS] = null

    if (errors.isNotEmpty()) return Validation(null, errors)
    return Validation(
        ConnectionProfile(
            id = id,
            name = name.trim(),
            protocol = app.hivpn.domain.model.VpnProtocol.WIREGUARD,
            preferredServerId = preferredServerId,
            dns = dnsSettings,
            mtu = mtuValue,
            splitTunnel = when (splitMode) {
                SplitMode.ALL -> SplitTunnelSettings.AllApps
                SplitMode.ONLY_SELECTED -> SplitTunnelSettings.OnlySelected(packages)
                SplitMode.BYPASS_SELECTED -> SplitTunnelSettings.BypassSelected(packages)
            },
            allowLanAccess = allowLan,
            excludedRoutes = routes.map { Cidr.parse(it)!!.toString() },
            persistentKeepalive = keepaliveValue,
        ),
        emptyMap(),
    )
}
