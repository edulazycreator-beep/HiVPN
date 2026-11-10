package app.hivpn.core.security

import android.util.Log

/**
 * Removes anything that could identify a user or unlock a tunnel before text reaches
 * logcat or a diagnostics report: WireGuard keys, bearer tokens, IP addresses and
 * hostnames.
 */
object LogRedactor {
    private val wgKey = Regex("""[A-Za-z0-9+/]{42}[AEIMQUYcgkosw480]=""")
    private val bearer = Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]+""")
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6 = Regex("""\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}\b|::(?:[0-9a-fA-F]{1,4}:?){1,7}""")
    private val hostname = Regex("""\b(?:[a-zA-Z0-9-]+\.)+(?:[a-zA-Z]{2,})\b""")

    fun redact(text: String): String = text
        .replace(wgKey, "<key>")
        .replace(bearer, "$1<token>")
        .replace(ipv6, "<ip6>")
        .replace(ipv4, "<ip>")
        .replace(hostname, "<host>")
}

/** The only logger the app uses. Debug output is redacted and dropped in release. */
object HiLog {
    var debugEnabled: Boolean = false

    fun d(tag: String, message: String) {
        if (debugEnabled) Log.d(tag, LogRedactor.redact(message))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, LogRedactor.redact(message + (error?.let { ": ${it.javaClass.simpleName}" } ?: "")))
    }
}
