package app.hivpn.domain.util

/**
 * Pure-Kotlin IP literal validation (no DNS lookups, no Android dependency), used to
 * validate custom DNS servers before they are applied to a tunnel.
 */
object IpAddress {

    fun isValidIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all(Char::isDigit) &&
                (part.length == 1 || part[0] != '0') &&
                part.toInt() in 0..255
        }
    }

    fun isValidIpv6(value: String): Boolean {
        if (value.isEmpty() || value.contains('%')) return false
        // Allow an embedded IPv4 tail, e.g. ::ffff:192.0.2.1 — it counts as two groups.
        var text = value
        val lastColon = text.lastIndexOf(':')
        if (lastColon >= 0 && text.substring(lastColon + 1).contains('.')) {
            if (!isValidIpv4(text.substring(lastColon + 1))) return false
            text = text.substring(0, lastColon + 1) + "0:0"
        }
        val doubleColonCount = text.windowed(2).count { it == "::" }
        if (doubleColonCount > 1) return false
        if (text.contains(":::")) return false

        val groups: List<String> = if (doubleColonCount == 1) {
            val (head, tail) = text.split("::", limit = 2)
            val headGroups = if (head.isEmpty()) emptyList() else head.split(':')
            val tailGroups = if (tail.isEmpty()) emptyList() else tail.split(':')
            if (headGroups.size + tailGroups.size > 7) return false
            headGroups + tailGroups
        } else {
            val all = text.split(':')
            if (all.size != 8) return false
            all
        }
        return groups.all { group ->
            group.length in 1..4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }
    }

    fun isValid(value: String): Boolean = isValidIpv4(value) || isValidIpv6(value)
}
