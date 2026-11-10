package app.hivpn.domain.model

/**
 * The one source of truth for what the tunnel is doing. Only the VPN service publishes
 * this; the UI observes it and never sets it, so no screen can show a state the tunnel
 * is not actually in.
 */
sealed interface TunnelState {
    data object Disconnected : TunnelState

    data class Connecting(val serverId: String) : TunnelState

    /**
     * Declared only after the first successful WireGuard handshake,
     * not when the TUN interface comes up.
     */
    data class Connected(
        val serverId: String,
        val connectedAtEpochMs: Long,
        val tunnelAddresses: List<String>,
    ) : TunnelState

    data class Reconnecting(val serverId: String, val attempt: Int) : TunnelState

    data object Disconnecting : TunnelState

    data class Failed(val error: TunnelError) : TunnelState
}

sealed interface TunnelError {
    /** The user declined the Android VPN consent dialog. */
    data object PermissionDenied : TunnelError

    /** No protocol engine is installed in this build (Phase 2 foundation build). */
    data object EngineUnavailable : TunnelError

    /** Another VPN app took over, or the user revoked VPN access in system settings. */
    data object RevokedBySystem : TunnelError

    data object NoServerSelected : TunnelError

    /** Directory servers need a Hi VPN account; imported configs do not. */
    data object AccountRequired : TunnelError

    data object DeviceLimitReached : TunnelError

    /** The Hi VPN API could not be reached or is not configured in this build. */
    data class ApiUnavailable(val detail: String) : TunnelError

    data object HandshakeTimeout : TunnelError

    data class InvalidConfiguration(val detail: String) : TunnelError

    data class Unknown(val detail: String) : TunnelError
}

val TunnelState.isActive: Boolean
    get() = this is TunnelState.Connecting ||
        this is TunnelState.Connected ||
        this is TunnelState.Reconnecting
