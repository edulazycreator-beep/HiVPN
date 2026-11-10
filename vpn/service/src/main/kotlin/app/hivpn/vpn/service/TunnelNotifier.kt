package app.hivpn.vpn.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.hivpn.core.common.ApplicationScope
import app.hivpn.domain.model.TargetId
import app.hivpn.domain.model.TunnelState
import app.hivpn.domain.repository.ImportedConfigRepository
import app.hivpn.domain.repository.ServerRepository
import app.hivpn.domain.repository.VpnConnectionRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows an ongoing notification whenever a tunnel is connecting, connected or
 * reconnecting: status, destination, a running timer and a Disconnect action.
 * The notification only reflects [VpnConnectionRepository.state]; it never invents state.
 */
@Singleton
class TunnelNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpn: VpnConnectionRepository,
    private val servers: ServerRepository,
    private val imported: ImportedConfigRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        createChannel()
        scope.launch {
            vpn.state.collect { state ->
                when (state) {
                    is TunnelState.Connecting -> post(state.serverId, R.string.notif_connecting, null)
                    is TunnelState.Connected -> post(state.serverId, R.string.notif_connected, state.connectedAtEpochMs)
                    is TunnelState.Reconnecting -> post(state.serverId, R.string.notif_reconnecting, null)
                    else -> NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                }
            }
        }
    }

    private suspend fun post(targetKey: String, status: Int, since: Long?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val disconnect = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, DisconnectReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_shield)
            .setContentTitle(context.getString(status))
            .setContentText(titleFor(targetKey))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.notif_disconnect), disconnect)
            .apply {
                if (since != null) setWhen(since).setShowWhen(true).setUsesChronometer(true)
                else setShowWhen(false)
            }
            .build()
        @Suppress("MissingPermission") // checked above
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private suspend fun titleFor(key: String): String = when (val t = TargetId.parse(key)) {
        is TargetId.Directory -> servers.observeServer(t.serverId).first()?.let { "${it.city}, ${it.countryName}" }
        is TargetId.Imported -> imported.observe(t.configId).first()?.name
        null -> null
    } ?: context.getString(R.string.tile_label)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "tunnel"
        const val NOTIFICATION_ID = 1
    }
}

@AndroidEntryPoint
class DisconnectReceiver : BroadcastReceiver() {
    @Inject lateinit var vpn: VpnConnectionRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                vpn.disconnect()
            } finally {
                pending.finish()
            }
        }
    }
}
