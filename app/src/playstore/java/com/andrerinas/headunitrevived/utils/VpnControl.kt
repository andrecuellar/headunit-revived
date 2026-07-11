package com.andrerinas.headunitrevived.utils

import android.content.Context

/**
 * Play Store flavor stub of VpnControl.
 *
 * The GitHub flavor ships a loopback VPN (DummyVpnService) used by Self Mode
 * when the device has no active network. The Play Store build intentionally
 * omits the VPN component to comply with Google Play's VPN policy, so this
 * stub is a no-op and reports the VPN as unavailable. Callers in shared (main)
 * code gate on isVpnAvailable(), so Self Mode simply skips the VPN path here.
 */
object VpnControl {
    fun startVpn(context: Context) {
        AppLog.i("VpnControl: VPN not available in Play Store build; skipping start")
    }

    fun stopVpn(context: Context) {
        AppLog.i("VpnControl: VPN not available in Play Store build; skipping stop")
    }

    fun isVpnAvailable(): Boolean = false
}
