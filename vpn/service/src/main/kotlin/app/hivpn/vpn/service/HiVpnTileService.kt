package app.hivpn.vpn.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.model.isActive
import app.hivpn.domain.repository.VpnConnectionRepository
import app.hivpn.domain.usecase.ConnectUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings toggle. Shows the real tunnel state; tapping connects or disconnects.
 * If Android's VPN consent has not been given yet, it opens the app instead, because the
 * consent dialog must be launched from an activity.
 */
@AndroidEntryPoint
class HiVpnTileService : TileService() {

    @Inject lateinit var vpn: VpnConnectionRepository
    @Inject lateinit var connect: ConnectUseCase

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch { vpn.state.collect(::render) }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val s = scope ?: return
        if (vpn.state.value.isActive) {
            s.launch { vpn.disconnect() }
            return
        }
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        s.launch { if (!connect()) openApp() }
    }

    private fun render(state: TunnelState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            is TunnelState.Connected -> Tile.STATE_ACTIVE
            is TunnelState.Connecting, is TunnelState.Reconnecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(
                when (state) {
                    is TunnelState.Connected -> R.string.notif_connected
                    is TunnelState.Connecting -> R.string.notif_connecting
                    is TunnelState.Reconnecting -> R.string.notif_reconnecting
                    else -> R.string.tile_off
                },
            )
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }
}
