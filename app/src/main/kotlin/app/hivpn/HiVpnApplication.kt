package app.hivpn

import android.app.Application
import app.hivpn.core.security.HiLog
import app.hivpn.vpn.service.AlwaysOnHook
import app.hivpn.vpn.service.TunnelNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HiVpnApplication : Application() {

    @Inject lateinit var alwaysOn: AlwaysOnHook
    @Inject lateinit var notifier: TunnelNotifier

    override fun onCreate() {
        super.onCreate()
        HiLog.debugEnabled = BuildConfig.DEBUG
        // Must be registered before Android can start the VPN service for Always-on.
        alwaysOn.install()
        notifier.start()
    }
}
