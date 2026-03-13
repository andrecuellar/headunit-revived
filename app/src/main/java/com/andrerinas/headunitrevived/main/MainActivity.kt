package com.andrerinas.headunitrevived.main

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.app.BaseActivity
import androidx.lifecycle.lifecycleScope
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.SetupWizard
import com.andrerinas.headunitrevived.utils.SystemUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null

    private val viewModel: MainViewModel by viewModels()

    private val finishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == "com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("MainActivity: Received finish request. Closing.")
                finishAffinity()
            }
        }
    }

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent?): Boolean
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If an Android Auto session is active, bring the projection activity to front
        if (App.provide(this).commManager.isConnected) {
            AppLog.i("MainActivity: Active session detected in onCreate, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
            // DO NOT finish() here, just let it stay in background
        }

        setTheme(R.style.AppTheme)
        if (Settings(this).appTheme == Settings.AppTheme.EXTREME_DARK) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        // Start main service immediately to handle connections and wireless server
        val serviceIntent = Intent(this, AapService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        setFullscreen()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_content) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.navigateUp()) {
                    return
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        requestPermissions()
        viewModel.register()
        handleIntent(intent)
        setupWifiDirectInfo()
    }

    private fun setupWifiDirectInfo() {
        val tvInfo = findViewById<android.widget.TextView>(R.id.wifi_direct_info)
        val settings = Settings(this)

        lifecycleScope.launch {
            AapService.wifiDirectName.collectLatest { name ->
                val isHelperMode = settings.wifiConnectionMode == 2
                if (isHelperMode && name != null) {
                    tvInfo.text = "WiFi Direct: $name"
                    tvInfo.visibility = View.VISIBLE
                } else {
                    tvInfo.visibility = View.GONE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, finishReceiver,
            android.content.IntentFilter("com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(finishReceiver) } catch (e: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        AppLog.i("MainActivity received intent: ${intent.action}, data: ${intent.data}")

        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.data
            if (data?.scheme == "headunit" && data.host == "connect") {
                val ip = data.getQueryParameter("ip")
                if (!ip.isNullOrEmpty()) {
                    AppLog.i("Received connect intent for IP: $ip")
                    ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(this@MainActivity).commManager.connect(ip, 5277) }
                } else {
                    AppLog.i("Received connect intent without IP -> triggering last session auto-connect")
                    val autoIntent = Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    }
                    ContextCompat.startForegroundService(this, autoIntent)
                }
            } else if (data?.scheme == "headunit" && data.host == "disconnect") {
                AppLog.i("Received disconnect intent")
                val stopIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(this, stopIntent)
            } else if (data?.scheme == "headunit" && data.host == "exit") {
                AppLog.i("Received full exit intent")
                val exitIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(this, exitIntent)
                finishAffinity() // Close all activities in this task
            } else if (data?.scheme == "geo" || data?.scheme == "google.navigation" || data?.host == "maps.google.com") {
                AppLog.i("Received navigation intent: $data")
                // In the future, we could parse coordinates and send to AA via a custom message
                // For now, we just ensure the app is opened (which it is by reaching this point)
            }
        } else if (intent.action == "android.intent.action.NAVIGATE") {
            AppLog.i("Received generic NAVIGATE intent")
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityCompat.requestPermissions(
            this,
            permissionsToRequest.toTypedArray(),
            permissionRequestCode
        )
    }

    private fun setFullscreen() {
        val root = findViewById<View>(R.id.root)
        val appSettings = Settings(this)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()

        checkSetupFlow()

        // If an Android Auto session is active, bring the projection activity to front
        if (App.provide(this).commManager.isConnected) {
            AppLog.i("MainActivity: Active session detected, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
        }
    }

    fun checkSetupFlow() {
        val appSettings = Settings(this)
        if (!appSettings.hasAcceptedDisclaimer) {
            SafetyDisclaimerDialog.show(supportFragmentManager)
        } else if (!appSettings.hasCompletedSetupWizard) {
            SetupWizard(this) {
                // Refresh activity after setup
                recreate()
            }.start()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        AppLog.i("dispatchKeyEvent: keyCode=%d, action=%d", event.keyCode, event.action)
        
        // Always give the KeymapFragment (if active) a chance to see the key
        val handled = keyListener?.onKeyEvent(event) ?: false
        
        // If the key was handled by our listener (e.g. in KeymapFragment), stop here
        if (handled) return true
        
        // Otherwise continue with standard handling
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            AppLog.i("MainActivity finishing, resetting auto-start flag.")
            HomeFragment.resetAutoStart()
        }
    }

    companion object {
        private const val permissionRequestCode = 97
    }
}
