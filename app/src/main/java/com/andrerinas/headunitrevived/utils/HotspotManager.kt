package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.dx.DexMaker
import com.android.dx.TypeId
import java.lang.reflect.Method
import com.andrerinas.headunitrevived.utils.SoftApConfigCompat

/**
 * Manages WiFi Hotspot (tethering) using reflection + dexmaker.
 */
object HotspotManager {
    private const val TAG = "HUREV_WIFI"
    private const val CALLBACK_CLASS = "android.net.ConnectivityManager\$OnStartTetheringCallback"

    private var cachedCallbackClass: Class<*>? = null

    fun setHotspotEnabled(context: Context, enabled: Boolean): Boolean {
        AppLog.i("HotspotManager: Setting hotspot enabled=$enabled (API ${Build.VERSION.SDK_INT}, canWriteSettings=${canWriteSystemSettings(context)})")

        // On Android 8+, WiFi must be disabled before tethering can start
        if (enabled) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (wm.isWifiEnabled) {
                    AppLog.i("HotspotManager: Disabling WiFi before enabling hotspot...")
                    wm.isWifiEnabled = false
                    Thread.sleep(500) // Let the radio settle
                }
            } catch (e: Exception) {
                AppLog.w("HotspotManager: Failed to disable WiFi: ${e.message}")
            }
        }

        // Use SoftApConfiguration for Android 30+ if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (SoftApConfigCompat.enableHotspot(context, enabled)) return true
        }
        // Newer API: TetheringManager (official) before ConnectivityManager fallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (tryTetheringManager(context, enabled)) return true
        }
        // The remaining reflection paths (startTethering / setWifiApEnabled) require the special
        // "Modify system settings" access (WRITE_SETTINGS). Without it the framework throws a
        // SecurityException, so check first and surface a clear, actionable message instead.
        if (canWriteSystemSettings(context)) {
            if (tryConnectivityManager(context, enabled)) return true
            if (tryLegacyWifiManager(context, enabled)) return true
        } else {
            AppLog.w("HotspotManager: Cannot enable the hotspot without the \"Modify system settings\" permission (WRITE_SETTINGS). Grant it in the setup wizard or Settings > Permissions.")
        }

        AppLog.w("HotspotManager: All hotspot attempts failed.")
        return false
    }

    /** WRITE_SETTINGS is a special access; a manifest declaration alone never grants it. */
    private fun canWriteSystemSettings(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            android.provider.Settings.System.canWrite(context)

    private fun tryConnectivityManager(context: Context, enabled: Boolean): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (!enabled) {
                val stopMethod = cm.javaClass.methods.find { it.name == "stopTethering" }
                if (stopMethod != null) {
                    stopMethod.isAccessible = true
                    stopMethod.invoke(cm, 0)
                    return true
                }
                return false
            }

            val startMethod = cm.javaClass.methods.find {
                it.name == "startTethering" && it.parameterTypes.size >= 4
            } ?: return false

            startMethod.isAccessible = true
            val callbackInst = createTetheringCallback(context)
            val handler = Handler(Looper.getMainLooper())

            return when (startMethod.parameterTypes.size) {
                4 -> {
                    startMethod.invoke(cm, 0, false, callbackInst, handler)
                    true
                }
                5 -> {
                    startMethod.invoke(cm, 0, false, callbackInst, handler, context.packageName)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            AppLog.e("HotspotManager: CM path failed", e)
            return false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createTetheringCallback(context: Context): Any? {
        try {
            cachedCallbackClass?.let { cls ->
                return cls.getDeclaredConstructor().newInstance()
            }

            val parentClass = Class.forName(CALLBACK_CLASS) ?: return null
            val dexMaker = DexMaker()
            val getByName: Method = TypeId::class.java.getDeclaredMethod("get", String::class.java)
            val getByClass: Method = TypeId::class.java.getDeclaredMethod("get", Class::class.java)

            val generatedType = getByName.invoke(null, "LTetheringCallback;") as TypeId<Any>
            val parentType = getByClass.invoke(null, parentClass) as TypeId<Any>

            dexMaker.declare(generatedType, "TetheringCallback.generated", java.lang.reflect.Modifier.PUBLIC, parentType)

            val constructor = generatedType.getConstructor() as com.android.dx.MethodId<Any, Void>
            val parentConstructor = parentType.getConstructor() as com.android.dx.MethodId<Any, Void>
            val code = dexMaker.declare(constructor, java.lang.reflect.Modifier.PUBLIC)
            val thisRef = code.getThis(generatedType)
            code.invokeDirect(parentConstructor, null, thisRef)
            code.returnVoid()

            val dexCache = context.codeCacheDir
            val classLoader = dexMaker.generateAndLoad(this.javaClass.classLoader, dexCache)
            val generatedClass = classLoader.loadClass("TetheringCallback")
            cachedCallbackClass = generatedClass

            return generatedClass.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            AppLog.e("HotspotManager: Dexmaker failed", e)
            return null
        }
    }

    private fun tryTetheringManager(context: Context, enabled: Boolean): Boolean {
        try {
            val tm = context.getSystemService("tethering") ?: return false
            if (enabled) {
                val startMethod = tm.javaClass.methods.find {
                    it.name == "startTethering" && it.parameterTypes.size == 3
                } ?: return false
                startMethod.invoke(tm, 0, context.mainExecutor, null)
                return true
            } else {
                val stopMethod = tm.javaClass.methods.find { it.name == "stopTethering" }
                stopMethod?.invoke(tm, 0)
                return true
            }
        } catch (e: Exception) {
            AppLog.e("HotspotManager: TetheringManager path failed", e)
            return false
        }
    }

    private fun tryLegacyWifiManager(context: Context, enabled: Boolean): Boolean {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            return method.invoke(wm, null, enabled) as Boolean
        } catch (_: Exception) { return false }
    }
}
