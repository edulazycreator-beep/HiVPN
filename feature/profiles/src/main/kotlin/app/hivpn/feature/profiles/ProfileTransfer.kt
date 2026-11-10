package app.hivpn.feature.profiles

import app.hivpn.domain.model.ConnectionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Export/import format for profiles. Contains only non-secret settings: no keys, no
 * account data, no server credentials. The field set is versioned so future readers can
 * reject what they don't understand.
 */
@Serializable
internal data class ProfileExport(
    val format: String = FORMAT,
    val version: Int = 1,
    val name: String,
    val dns: String,
    val customDns: List<String> = emptyList(),
    val mtu: Int? = null,
    val keepalive: Int? = null,
    val allowLan: Boolean = false,
    val excludedRoutes: List<String> = emptyList(),
    val splitMode: String = "ALL",
    val packages: List<String> = emptyList(),
) {
    companion object {
        const val FORMAT = "hivpn-profile"
    }
}

internal object ProfileTransfer {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun export(profile: ConnectionProfile): String {
        val form = ProfileForm.from(profile)
        return json.encodeToString(
            ProfileExport.serializer(),
            ProfileExport(
                name = form.name,
                dns = form.dns.name,
                customDns = if (form.dns == DnsChoice.CUSTOM) form.customDns.lines().filter(String::isNotBlank) else emptyList(),
                mtu = profile.mtu,
                keepalive = profile.persistentKeepalive,
                allowLan = profile.allowLanAccess,
                excludedRoutes = profile.excludedRoutes,
                splitMode = form.splitMode.name,
                packages = form.packages.sorted(),
            ),
        )
    }

    /** Parses exported JSON back into a form (id 0 = new). Validation happens in the editor. */
    fun import(text: String): ProfileForm? = runCatching {
        val e = json.decodeFromString(ProfileExport.serializer(), text)
        require(e.format == ProfileExport.FORMAT && e.version == 1)
        ProfileForm(
            name = e.name.take(40),
            dns = DnsChoice.valueOf(e.dns),
            customDns = e.customDns.joinToString("\n"),
            mtu = e.mtu?.toString().orEmpty(),
            keepalive = e.keepalive?.toString().orEmpty(),
            allowLan = e.allowLan,
            excludedRoutes = e.excludedRoutes.joinToString("\n"),
            splitMode = SplitMode.valueOf(e.splitMode),
            packages = e.packages.toSet(),
        )
    }.getOrNull()
}
