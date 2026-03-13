package com.andrerinas.headunitrevived.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.aap.AapService

import com.andrerinas.headunitrevived.location.GpsLocationService
import com.andrerinas.headunitrevived.main.MainActivity
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.i("BootCompleteReceiver: Boot completed. Action: ${intent.action}")

        val settings = Settings(context)
        val autoConnectLastSession = settings.autoConnectLastSession
        val autoConnectSingleUsb = settings.autoConnectSingleUsbDevice
        val autoStartSelfMode = settings.autoStartSelfMode

        AppLog.i("BootCompleteReceiver: Settings — autoConnectLastSession=$autoConnectLastSession, " +
                "autoConnectSingleUsb=$autoConnectSingleUsb, autoStartSelfMode=$autoStartSelfMode, " +
                "lastConnectionType=${settings.lastConnectionType}")

        val h = Handler(Looper.getMainLooper())
        h.postDelayed({
            AppLog.i("BootCompleteReceiver: Starting AapService after boot delay")
            val serviceIntent = Intent(context, AapService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)

            // Launch MainActivity if any USB auto-connect is enabled.
            // USB permissions are reset after reboot, so the permission dialog
            // needs a foreground Activity to display. Without this, boot auto-connect
            // silently fails because requestUsbPermission() has no visible window.
            if (autoConnectLastSession || autoConnectSingleUsb) {
                AppLog.i("BootCompleteReceiver: Launching MainActivity for USB permission dialog")
                try {
                    context.startActivity(Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    AppLog.w("BootCompleteReceiver: Could not launch MainActivity: ${e.message}")
                }
            }
        }, 10000)
    }
}
